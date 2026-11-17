package workbench

import (
	"bytes"
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

type callContextKey struct{}
type callState struct {
	userID   int64
	id       string
	mu       sync.Mutex
	attempts []map[string]any
}

var logicalCallID = regexp.MustCompile(`^wb_[a-f0-9]{32}$`)

// Only generation endpoints enter the journal: task polls and model discovery are excluded.
func generationPath(method, path string) bool {
	if method != http.MethodPost {
		return false
	}
	path = strings.TrimPrefix(path, "/antigravity")
	path = strings.TrimPrefix(path, "/backend-api/codex")
	path = strings.TrimPrefix(path, "/v1/")
	path = strings.TrimPrefix(path, "/")
	switch path {
	case "chat/completions", "responses", "responses/compact", "messages", "embeddings", "images/generations", "images/edits", "images/generations/async", "images/edits/async", "images/batches", "videos", "videos/generations", "videos/edits", "videos/extensions", "tts", "stt", "web_search", "x_search", "alpha/search":
		return true
	}
	return strings.HasPrefix(path, "v1beta/models/") && (strings.HasSuffix(path, ":generateContent") || strings.HasSuffix(path, ":streamGenerateContent"))
}

// CaptureCalls precedes authentication. No headers, query credentials or IPs are persisted.
// Writes are bounded; persistence failure leaves an explicit operational error, never a synthetic success.
func CaptureCalls() gin.HandlerFunc {
	return func(c *gin.Context) {
		poll := c.Request.Method == http.MethodGet && taskPollPath(c.Request.URL.Path)
		websocket := c.Request.Method == http.MethodGet && strings.EqualFold(c.GetHeader("Upgrade"), "websocket") && strings.HasSuffix(c.Request.URL.Path, "/responses")
		if database == nil || (!generationPath(c.Request.Method, c.Request.URL.Path) && !poll && !websocket) {
			c.Next()
			return
		}
		if poll {
			state := &callState{}
			c.Request = c.Request.WithContext(context.WithValue(c.Request.Context(), callContextKey{}, state))
			writer := &auditWriter{ResponseWriter: c.Writer, complete: true}
			c.Writer = writer
			c.Next()
			if writer.Status() == 200 && state.userID > 0 {
				var body map[string]any
				if json.Unmarshal(writer.body.Bytes(), &body) == nil {
					task, _ := body["task_id"].(string)
					if task == "" {
						task, _ = body["id"].(string)
					}
					if task == "" {
						task = c.Request.URL.Path[strings.LastIndex(c.Request.URL.Path, "/")+1:]
					}
					status, _ := body["status"].(string)
					NativeTaskSaved(c.Request.Context(), task, state.userID, status, writer.Status(), writer.body.Bytes(), nil)
				}
			}
			return
		}
		b := make([]byte, 16)
		if _, err := rand.Read(b); err != nil {
			failure(c, 503, "CALL_AUDIT_UNAVAILABLE")
			return
		}
		state := &callState{id: hex.EncodeToString(b)}
		c.Request = c.Request.WithContext(context.WithValue(c.Request.Context(), callContextKey{}, state))
		c.Header("X-Workbench-Request-ID", state.id)
		started := time.Now()
		if c.Request.Body == nil {
			c.Request.Body = http.NoBody
		}
		raw, err := io.ReadAll(io.LimitReader(c.Request.Body, maxAuditBytes+1))
		if err != nil || len(raw) > maxAuditBytes {
			failure(c, 413, "AUDIT_REQUEST_TOO_LARGE")
			raw = nil
		}
		c.Request.Body = io.NopCloser(bytes.NewReader(raw))
		requestComplete := raw != nil && json.Valid(raw)
		var model string
		var request map[string]any
		if json.Unmarshal(raw, &request) == nil {
			model, _ = request["model"].(string)
		}
		if len(model) > 200 {
			model = ""
		}
		if model == "" && strings.Contains(c.Request.URL.Path, "/models/") {
			model = strings.Split(strings.Split(c.Request.URL.Path, "/models/")[1], ":")[0]
		}
		initial, _ := json.Marshal(map[string]any{"request": cleanJSON(raw), "response": nil, "complete": false})
		encrypted, sealErr := sealAudit(initial)
		if sealErr != nil {
			failure(c, 503, "CALL_AUDIT_UNAVAILABLE")
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_, err = database.ExecContext(ctx, "INSERT INTO wb_call(id,model,payload) VALUES($1,$2,$3)", state.id, model, encrypted)
		cancel()
		if err != nil {
			failure(c, 503, "CALL_AUDIT_UNAVAILABLE")
			return
		}
		writer := &auditWriter{ResponseWriter: c.Writer, complete: true}
		c.Writer = writer
		defer func() {
			status := callStatus(writer.Status(), writer.body.Bytes(), writer.Header().Get("Content-Type"), c.Request.Context().Err())
			if status == "running" && state.userID > 0 {
				var body map[string]any
				if json.Unmarshal(writer.body.Bytes(), &body) == nil {
					task, _ := body["task_id"].(string)
					if task == "" {
						task, _ = body["id"].(string)
					}
					if task != "" {
						NativeTaskSaved(c.Request.Context(), task, state.userID, "processing", writer.Status(), nil, nil)
					}
				}
			}
			state.mu.Lock()
			attempts := append([]map[string]any{}, state.attempts...)
			state.mu.Unlock()
			payload := map[string]any{"request": cleanJSON(raw), "response": cleanResponse(writer.body.Bytes(), writer.Header().Get("Content-Type")), "httpStatus": writer.Status(), "complete": requestComplete && writer.complete, "attempts": attempts}
			data, _ := json.Marshal(payload)
			encrypted, err := sealAudit(data)
			if err != nil {
				return
			}
			local, stop := context.WithTimeout(context.Background(), 10*time.Second)
			defer stop()
			_, err = database.ExecContext(local, "UPDATE wb_call SET status=CASE WHEN EXISTS(SELECT 1 FROM wb_call_task t WHERE t.event_id=wb_call.id AND t.status IN ('success','failed','cancelled')) THEN status ELSE $2 END,duration_ms=$3,http_status=$4,complete=$5,payload=$6,updated_at=now(),change_id=nextval('wb_call_changes') WHERE id=$1", state.id, status, time.Since(started).Milliseconds(), writer.Status(), requestComplete && writer.complete, encrypted)
			if err != nil {
				log.Print("workbench call finalization failed; record remains incomplete")
			}
		}()
		if !c.IsAborted() {
			c.Next()
		}
	}
}
func cleanResponse(raw []byte, contentType string) any {
	if !strings.Contains(contentType, "text/event-stream") {
		return cleanJSON(raw)
	}
	lines := strings.Split(string(raw), "\n")
	for i, line := range lines {
		if strings.HasPrefix(line, "data:") {
			v := cleanJSON([]byte(strings.TrimSpace(strings.TrimPrefix(line, "data:"))))
			b, _ := json.Marshal(v)
			lines[i] = "data: " + string(b)
		}
	}
	return strings.Join(lines, "\n")
}
func callStatus(code int, raw []byte, contentType string, err error) string {
	if code == 401 || code == 403 || code == 429 {
		return "rejected"
	}
	if code == 202 {
		return "running"
	}
	if code >= 400 {
		return "failed"
	}
	if err != nil {
		return "cancelled"
	}
	if strings.Contains(contentType, "text/event-stream") {
		completed := false
		for _, line := range strings.Split(string(raw), "\n") {
			if !strings.HasPrefix(line, "data:") {
				continue
			}
			data := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
			if data == "[DONE]" {
				completed = true
				continue
			}
			var event map[string]any
			if json.Unmarshal([]byte(data), &event) != nil {
				continue
			}
			kind, _ := event["type"].(string)
			if event["error"] != nil || kind == "error" || kind == "response.failed" {
				return "failed"
			}
			if kind == "response.completed" || kind == "message_stop" {
				completed = true
			}
			if candidates, ok := event["candidates"].([]any); ok {
				for _, candidate := range candidates {
					if c, ok := candidate.(map[string]any); ok && c["finishReason"] != nil {
						completed = true
					}
				}
			}
		}
		if completed {
			return "success"
		}
		return "unknown"
	}

	var body map[string]any
	if json.Unmarshal(raw, &body) != nil {
		return "unknown"
	}
	if body["error"] != nil {
		return "failed"
	}
	switch strings.ToLower(fmt.Sprint(body["status"])) {
	case "pending", "queued", "processing", "running", "submitted":
		return "running"
	case "failed", "error":
		return "failed"
	case "cancelled", "canceled":
		return "cancelled"
	}

	return "success"
}

// Called immediately after native key resolution, including disabled/expired keys.
// Client-supplied internal correlation headers are accepted only for registered internal keys.
func IdentifyCall(c *gin.Context, userID, keyID int64) {
	state, _ := c.Request.Context().Value(callContextKey{}).(*callState)
	if state == nil || database == nil {
		return
	}
	state.userID = userID
	if state.id == "" {
		return
	}
	var external, department, purpose sql.NullString
	_ = database.QueryRowContext(c.Request.Context(), "SELECT external_id,department_id FROM wb_employee WHERE user_id=$1", userID).Scan(&external, &department)
	_ = database.QueryRowContext(c.Request.Context(), "SELECT purpose FROM wb_key WHERE user_id=$1 AND key_id=$2", userID, keyID).Scan(&purpose)
	channel := "key"
	var logical, run, tool any
	if purpose.String == "internal" {
		channel = "workbench"
		if value := c.GetHeader("X-Workbench-Call-ID"); logicalCallID.MatchString(value) {
			logical = value
		}
		if v := c.GetHeader("X-Workbench-Run-ID"); len(v) <= 120 {
			run = v
		}
		if v := c.GetHeader("X-Workbench-Tool-Key"); len(v) <= 100 {
			tool = v
		}
	}
	_, err := database.ExecContext(c.Request.Context(), "UPDATE wb_call SET user_id=$2,external_id=$3,department_id=$4,channel=$5,logical_id=$6,run_id=$7,tool_key=$8,updated_at=now(),change_id=nextval('wb_call_changes') WHERE id=$1", state.id, userID, external, department, channel, logical, run, tool)
	if err != nil {
		log.Print("workbench call identity persistence failed")
	}
}

// Transport attempts are metadata only: never record URL, headers, proxy or credentials.
func RecordAttempt(ctx context.Context, accountID int64, status int, transportError bool, elapsed time.Duration) {
	state, _ := ctx.Value(callContextKey{}).(*callState)
	if state == nil || state.id == "" {
		return
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if len(state.attempts) >= 100 {
		return
	}
	state.attempts = append(state.attempts, map[string]any{"accountId": accountID, "httpStatus": status, "transportError": transportError, "durationMs": elapsed.Milliseconds()})
	if database != nil {
		local, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := database.ExecContext(local, "INSERT INTO wb_call_attempt(event_id,account_id,http_status,transport_error,duration_ms) VALUES($1,$2,$3,$4,$5)", state.id, accountID, status, transportError, elapsed.Milliseconds()); err != nil {
			log.Print("workbench upstream attempt persistence failed")
		}
	}
}
func callList(c *gin.Context) {
	from, e1 := time.Parse(time.RFC3339Nano, c.Query("from"))
	until, e2 := time.Parse(time.RFC3339Nano, c.Query("until"))
	after, e3 := strconv.ParseInt(c.DefaultQuery("after", "0"), 10, 64)
	if e1 != nil || e2 != nil || e3 != nil || after < 0 || !from.Before(until) {
		failure(c, 400, "INVALID_CALL_RANGE")
		return
	}
	// Refresh late native metering before selecting the bounded change feed.
	_, _ = database.ExecContext(c.Request.Context(), `UPDATE wb_call c SET source_usage_id=u.source_id,input_tokens=u.input_tokens,output_tokens=u.output_tokens,cache_tokens=u.cache_tokens,image_count=u.image_count,updated_at=now(),change_id=nextval('wb_call_changes') FROM wb_trace t,wb_usage u WHERE c.id=t.client_id AND c.user_id=t.user_id AND t.request_id=u.request_id AND c.external_id=u.user_id AND c.source_usage_id IS DISTINCT FROM u.source_id`)
	rows, err := database.QueryContext(c.Request.Context(), `SELECT id,change_id,logical_id,channel,external_id,department_id,run_id,tool_key,model,status,occurred_at,duration_ms,http_status,complete,source_usage_id,input_tokens,output_tokens,cache_tokens,image_count FROM wb_call WHERE updated_at>=$1 AND updated_at<=$2 AND change_id>$3 ORDER BY change_id LIMIT 501`, from, until, after)
	if err != nil {
		failure(c, 503, "CALL_STORE_UNAVAILABLE")
		return
	}
	defer rows.Close()
	items := []gin.H{}
	next := after
	more := false
	for rows.Next() {
		var id, channel, status string
		var seq int64
		var logical, user, dept, run, tool, model sql.NullString
		var at time.Time
		var duration, httpStatus, usage, input, output, cache, images sql.NullInt64
		var complete bool
		if err = rows.Scan(&id, &seq, &logical, &channel, &user, &dept, &run, &tool, &model, &status, &at, &duration, &httpStatus, &complete, &usage, &input, &output, &cache, &images); err != nil {
			failure(c, 503, "CALL_STORE_UNAVAILABLE")
			return
		}
		if len(items) == 500 {
			more = true
			break
		}
		next = seq
		row := gin.H{"eventId": id, "channel": channel, "status": status, "occurredAt": at.UTC().Format(time.RFC3339Nano), "complete": complete, "provider": "sub2api"}
		for k, v := range map[string]sql.NullString{"logicalId": logical, "userId": user, "departmentId": dept, "runId": run, "toolKey": tool, "model": model} {
			if v.Valid {
				row[k] = v.String
			}
		}
		for k, v := range map[string]sql.NullInt64{"durationMs": duration, "httpStatus": httpStatus, "inputTokens": input, "outputTokens": output, "cacheTokens": cache, "imageCount": images} {
			if v.Valid {
				row[k] = v.Int64
			}
		}
		if usage.Valid {
			row["sourceUsageId"] = strconv.FormatInt(usage.Int64, 10)
		}
		items = append(items, row)
	}
	if rows.Err() != nil {
		failure(c, 503, "CALL_STORE_UNAVAILABLE")
		return
	}
	success(c, gin.H{"items": items, "nextCursor": strconv.FormatInt(next, 10), "hasMore": more})
}
func callAudit(c *gin.Context) {
	var payload []byte
	if err := database.QueryRowContext(c.Request.Context(), "SELECT payload FROM wb_call WHERE id=$1 AND occurred_at>=now()-interval '180 days'", c.Param("id")).Scan(&payload); err != nil {
		failure(c, 404, "CALL_AUDIT_UNAVAILABLE")
		return
	}
	raw, err := openAudit(payload)
	if err != nil {
		failure(c, 503, "CALL_AUDIT_UNAVAILABLE")
		return
	}
	var value any
	if json.Unmarshal(raw, &value) != nil {
		failure(c, 503, "CALL_AUDIT_UNAVAILABLE")
		return
	}
	if object, ok := value.(map[string]any); ok {
		rows, err := database.QueryContext(c.Request.Context(), "SELECT account_id,http_status,transport_error,duration_ms FROM wb_call_attempt WHERE event_id=$1 ORDER BY id LIMIT 100", c.Param("id"))
		if err == nil {
			attempts := []gin.H{}
			for rows.Next() {
				var account, duration int64
				var status int
				var transport bool
				if rows.Scan(&account, &status, &transport, &duration) == nil {
					attempts = append(attempts, gin.H{"accountId": account, "httpStatus": status, "transportError": transport, "durationMs": duration})
				}
			}
			rows.Close()
			if len(attempts) > 0 {
				object["attempts"] = attempts
			}
			if len(attempts) == 100 {
				object["attemptsMayBeTruncated"] = true
			}
		}

		var taskPayload []byte
		if database.QueryRowContext(c.Request.Context(), "SELECT payload FROM wb_call_task WHERE event_id=$1 AND payload IS NOT NULL", c.Param("id")).Scan(&taskPayload) == nil {
			if raw, err := openAudit(taskPayload); err == nil {
				var task any
				if json.Unmarshal(raw, &task) == nil {
					object["taskResult"] = task
				}
			}
		}
	}
	success(c, value)
}

// Native image workers complete outside HTTP ingress. Their task maps back to the original call.
func NativeTaskSaved(ctx context.Context, id string, userID int64, status string, httpStatus int, result, taskError []byte) {
	if database == nil {
		return
	}
	state, _ := ctx.Value(callContextKey{}).(*callState)
	local, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if state != nil && state.id != "" {
		_, err := database.ExecContext(local, "INSERT INTO wb_call_task(id,user_id,event_id,status) VALUES($1,$2,$3,'running') ON CONFLICT(id) DO NOTHING", id, userID, state.id)
		if err != nil {
			log.Print("workbench task correlation failed")
			return
		}
	}
	terminal := ""
	switch status {
	case "completed", "succeeded", "success":
		terminal = "success"
	case "failed", "error":
		terminal = "failed"
	case "cancelled", "canceled":
		terminal = "cancelled"
	}
	if terminal == "" {
		return
	}
	payload, _ := json.Marshal(map[string]any{"result": cleanJSON(result), "error": cleanJSON(taskError), "status": terminal})
	sealed, err := sealAudit(payload)
	if err != nil {
		return
	}
	_, err = database.ExecContext(local, "UPDATE wb_call_task SET status=$3,http_status=$4,payload=$5 WHERE id=$1 AND user_id=$2", id, userID, terminal, httpStatus, sealed)
	if err == nil {
		_, err = database.ExecContext(local, "UPDATE wb_call SET status=$3,http_status=$4,updated_at=now(),change_id=nextval('wb_call_changes') WHERE id IN (SELECT event_id FROM wb_call_task WHERE id=$1 AND user_id=$2)", id, userID, terminal, httpStatus)
	}
	if err != nil {
		log.Print("workbench task completion persistence failed")
	}
}

func taskPollPath(path string) bool {
	path = strings.TrimPrefix(path, "/v1")
	return strings.HasPrefix(path, "/images/tasks/") || (strings.HasPrefix(path, "/images/batches/") && !strings.HasSuffix(path, "/download") && !strings.Contains(path, "/items")) || (strings.HasPrefix(path, "/videos/") && !strings.HasSuffix(path, "/content"))
}
