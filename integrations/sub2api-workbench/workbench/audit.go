package workbench

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"github.com/Wei-Shaw/sub2api/internal/pkg/ctxkey"
	"github.com/gin-gonic/gin"
	"io"
	"log"
	"os"
	"regexp"
	"strings"
	"time"
)

var secretPattern = regexp.MustCompile(`(?i)(sk-[a-z0-9_-]{8,}|Bearer\s+[a-z0-9._~+/-]{8,})`)
var secretFields = map[string]bool{"password": true, "apikey": true, "accesstoken": true, "refreshtoken": true, "authorization": true, "cookie": true, "secret": true, "privatekey": true, "key": true, "token": true, "toolsession": true, "servicecredential": true}

func clean(value any) any {
	switch v := value.(type) {
	case map[string]any:
		for k, x := range v {
			normalized := strings.ToLower(strings.NewReplacer("_", "", "-", "").Replace(k))
			if secretFields[normalized] {
				v[k] = "[REDACTED]"
			} else {
				v[k] = clean(x)
			}
		}
		return v
	case []any:
		for i, x := range v {
			v[i] = clean(x)
		}
		return v
	case string:
		return secretPattern.ReplaceAllString(v, "[REDACTED]")
	default:
		return value
	}
}
func cleanJSON(raw []byte) any {
	var v any
	if json.Unmarshal(raw, &v) == nil {
		return clean(v)
	}
	return secretPattern.ReplaceAllString(string(raw), "[REDACTED]")
}
func auditCipher() (cipher.AEAD, error) {
	key, err := base64.StdEncoding.DecodeString(os.Getenv("WORKBENCH_AUDIT_KEY"))
	if err != nil {
		return nil, err
	}
	if len(key) != 32 {
		return nil, io.ErrUnexpectedEOF
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}
func sealAudit(raw []byte) ([]byte, error) {
	a, err := auditCipher()
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, a.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return nil, err
	}
	return a.Seal(nonce, nonce, raw, nil), nil
}
func openAudit(raw []byte) ([]byte, error) {
	a, err := auditCipher()
	if err != nil {
		return nil, err
	}
	if len(raw) < a.NonceSize() {
		return nil, io.ErrUnexpectedEOF
	}
	return a.Open(nil, raw[:a.NonceSize()], raw[a.NonceSize():], nil)
}

// AuditWriter never stores request headers or API keys. A visible completeness
// marker prevents oversized/failed captures from masquerading as a full record.
type auditWriter struct {
	gin.ResponseWriter
	body     bytes.Buffer
	complete bool
}

const maxAuditBytes = 64 << 20

func (w *auditWriter) Write(raw []byte) (int, error) {
	if w.body.Len()+len(raw) <= maxAuditBytes {
		_, _ = w.body.Write(raw)
	} else {
		w.complete = false
	}
	return w.ResponseWriter.Write(raw)
}
func (w *auditWriter) WriteString(raw string) (int, error) { return w.Write([]byte(raw)) }

func capture(c *gin.Context, userID int64) (func(), bool) {
	if _, err := auditCipher(); err != nil {
		failure(c, 503, "AUDIT_KEY_NOT_CONFIGURED")
		return func() {}, false
	}
	raw, err := io.ReadAll(io.LimitReader(c.Request.Body, maxAuditBytes+1))
	if err != nil || len(raw) > maxAuditBytes {
		failure(c, 413, "AUDIT_REQUEST_TOO_LARGE")
		return func() {}, false
	}
	c.Request.Body = io.NopCloser(bytes.NewReader(raw))
	b := make([]byte, 18)
	if _, err = rand.Read(b); err != nil {
		failure(c, 503, "AUDIT_UNAVAILABLE")
		return func() {}, false
	}
	clientID := base64.RawURLEncoding.EncodeToString(b)
	if state, _ := c.Request.Context().Value(callContextKey{}).(*callState); state != nil {
		clientID = state.id
	}
	c.Request = c.Request.WithContext(context.WithValue(c.Request.Context(), ctxkey.ClientRequestID, clientID))
	c.Header("X-Workbench-Request-ID", clientID)
	started := time.Now()
	initial, _ := json.Marshal(map[string]any{"request": cleanJSON(raw), "response": nil, "complete": false, "requestId": clientID})
	encrypted, err := sealAudit(initial)
	if err != nil {
		failure(c, 503, "AUDIT_UNAVAILABLE")
		return func() {}, false
	}
	if _, err = database.ExecContext(c.Request.Context(), "INSERT INTO wb_audit VALUES($1,$2,$3,0,false,$4)", clientID, userID, started, encrypted); err != nil {
		failure(c, 503, "AUDIT_UNAVAILABLE")
		return func() {}, false
	}
	channel := "client"
	var run, tool any
	if c.GetString("workbench_key_purpose") == "internal" {
		channel = "internal"
		run = c.GetHeader("X-Workbench-Run-ID")
		tool = c.GetHeader("X-Workbench-Tool-Key")
	}
	if _, err = database.ExecContext(c.Request.Context(), "INSERT INTO wb_context VALUES($1,$2,$3,$4)", clientID, channel, run, tool); err != nil {
		failure(c, 503, "AUDIT_UNAVAILABLE")
		return func() {}, false
	}
	writer := &auditWriter{ResponseWriter: c.Writer, complete: true}
	c.Writer = writer
	return func() {
		var output any
		if strings.Contains(writer.Header().Get("Content-Type"), "text/event-stream") {
			// Each SSE data line is independent JSON; redact structured credential fields.
			lines := strings.Split(writer.body.String(), "\n")
			for i, line := range lines {
				if strings.HasPrefix(line, "data:") {
					v := cleanJSON([]byte(strings.TrimSpace(strings.TrimPrefix(line, "data:"))))
					b, _ := json.Marshal(v)
					lines[i] = "data: " + string(b)
				}
			}
			output = strings.Join(lines, "\n")
		} else {
			output = cleanJSON(writer.body.Bytes())
		}
		payload, _ := json.Marshal(map[string]any{"request": cleanJSON(raw), "response": output, "status": writer.Status(), "complete": writer.complete, "requestId": clientID})
		encrypted, err := sealAudit(payload)
		if err != nil {
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if _, err = database.ExecContext(ctx, "UPDATE wb_audit SET status=$2,complete=$3,payload=$4 WHERE client_id=$1", clientID, writer.Status(), writer.complete, encrypted); err != nil {
			log.Print("workbench audit response persistence failed; record remains incomplete")
		}
	}, true
}

// Called before the native repository queues or inserts a usage record. The
// client trace follows the detached billing context, never a guessed timestamp.
func LinkUsage(ctx context.Context, userID int64, requestID string) {
	if database == nil || requestID == "" {
		return
	}
	clientID, _ := ctx.Value(ctxkey.ClientRequestID).(string)
	if clientID == "" {
		return
	}
	local, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _ = database.ExecContext(local, "INSERT INTO wb_trace VALUES($1,$2,$3) ON CONFLICT(user_id,request_id) DO NOTHING", userID, requestID, clientID)
}
func audit(c *gin.Context) {
	var payload []byte
	err := database.QueryRowContext(c.Request.Context(), `SELECT a.payload FROM wb_audit a JOIN wb_trace t ON t.client_id=a.client_id AND t.user_id=a.user_id JOIN wb_employee e ON e.user_id=t.user_id JOIN wb_usage u ON u.user_id=e.external_id AND u.request_id=t.request_id WHERE u.source_id=$1 AND a.created_at>=now()-interval '180 days'`, c.Param("id")).Scan(&payload)
	if err == sql.ErrNoRows {
		failure(c, 404, "AUDIT_NOT_AVAILABLE")
		return
	}
	if err != nil {
		failure(c, 503, "AUDIT_UNAVAILABLE")
		return
	}
	raw, err := openAudit(payload)
	if err != nil {
		failure(c, 503, "AUDIT_UNAVAILABLE")
		return
	}
	var value any
	if json.Unmarshal(raw, &value) != nil {
		failure(c, 503, "AUDIT_UNAVAILABLE")
		return
	}
	success(c, value)
}
func expireAudits() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	_, _ = database.ExecContext(ctx, "DELETE FROM wb_audit WHERE created_at<now()-interval '180 days'")
	_, _ = database.ExecContext(ctx, "UPDATE wb_call SET payload=NULL,complete=false WHERE occurred_at<now()-interval '180 days' AND payload IS NOT NULL")
	_, _ = database.ExecContext(ctx, "UPDATE wb_call_task SET payload=NULL WHERE event_id IN (SELECT id FROM wb_call WHERE occurred_at<now()-interval '180 days') AND payload IS NOT NULL")
}
