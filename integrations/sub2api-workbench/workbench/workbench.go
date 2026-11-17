// Package workbench implements token budgets for explicitly mapped employees.
package workbench

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/gin-gonic/gin"
	_ "github.com/lib/pq"
	"net/http"
	"strconv"
	"strings"
	"time"
)

//go:embed schema.sql
var schema string
var database *sql.DB

const lockID int64 = 873161028

func Initialize(dsn string) error {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return err
	}
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(2)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		db.Close()
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock($1)", lockID); err == nil {
		_, err = tx.ExecContext(ctx, schema)
	}
	if err == nil {
		err = tx.Commit()
	}
	if err != nil {
		db.Close()
		return err
	}
	database = db
	return nil
}

func Register(admin *gin.RouterGroup) {
	admin.GET("/workbench/capabilities", func(c *gin.Context) {
		success(c, gin.H{"contractVersion": "1", "tokenQuota": true, "departmentPool": true, "callRecords": true})
	})
	admin.PUT("/workbench/policy", applyPolicy)
	admin.GET("/workbench/usage", usage)
	admin.GET("/workbench/calls", callList)
	admin.GET("/workbench/calls/:id/audit", callAudit)
	admin.GET("/workbench/usage/:id/audit", audit)
	admin.POST("/workbench/keys", keyOperation)
	admin.DELETE("/workbench/keys", keyOperation)
}
func success(c *gin.Context, data any) { c.JSON(http.StatusOK, gin.H{"code": 0, "data": data}) }
func failure(c *gin.Context, status int, reason string) {
	c.AbortWithStatusJSON(status, gin.H{"code": status, "message": reason, "error": gin.H{"type": "workbench_quota", "code": reason, "message": reason}})
}

type Department struct {
	ID            string `json:"id"`
	PersonalLimit int64  `json:"personalLimit"`
	PoolLimit     int64  `json:"poolLimit"`
	Concurrency   int    `json:"concurrency"`
	Configured    bool   `json:"configured"`
}
type Employee struct {
	ID            string `json:"id"`
	UserID        int64  `json:"relayUserId"`
	DepartmentID  string `json:"departmentId"`
	Mode          string `json:"mode"`
	PersonalLimit int64  `json:"personalLimit"`
	Concurrency   int    `json:"concurrency"`
	Enabled       bool   `json:"enabled"`
	Configured    bool   `json:"configured"`
}
type Policy struct {
	Revision    int64        `json:"revision"`
	Departments []Department `json:"departments"`
	Employees   []Employee   `json:"employees"`
}

func validate(p Policy) error {
	if p.Revision < 1 {
		return errors.New("revision required")
	}
	ids := map[string]bool{}
	users := map[int64]bool{}
	for _, d := range p.Departments {
		if d.ID == "" || ids[d.ID] || d.PersonalLimit < 0 || d.PoolLimit < 0 || (d.Configured && (d.Concurrency < 1 || d.Concurrency > 100)) {
			return errors.New("invalid department")
		}
		ids[d.ID] = true
	}
	names := map[string]bool{}
	for _, u := range p.Employees {
		if u.ID == "" || u.UserID <= 0 || users[u.UserID] || names[u.ID] || u.PersonalLimit < 0 || (u.Configured && (u.Concurrency < 1 || u.Concurrency > 100)) || (u.Mode != "inherited" && u.Mode != "independent") {
			return errors.New("invalid employee")
		}
		users[u.UserID] = true
		names[u.ID] = true
		if u.Mode == "inherited" && u.Configured && !ids[u.DepartmentID] {
			return errors.New("department missing")
		}
	}
	return nil
}
func applyPolicy(c *gin.Context) {
	var p Policy
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 2<<20)
	if json.NewDecoder(c.Request.Body).Decode(&p) != nil || validate(p) != nil {
		failure(c, 400, "INVALID_POLICY")
		return
	}
	ctx := c.Request.Context()
	tx, err := database.BeginTx(ctx, nil)
	if err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock($1)", lockID); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return
	}
	var revision int64
	canonical, _ := json.Marshal(p)
	sum := sha256.Sum256(canonical)
	digest := hex.EncodeToString(sum[:])
	var previous sql.NullString
	if tx.QueryRowContext(ctx, "SELECT revision,policy_hash FROM wb_policy_state WHERE id=1 FOR UPDATE").Scan(&revision, &previous) != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return
	}
	if p.Revision < revision {
		failure(c, 409, "STALE_POLICY")
		return
	}
	if p.Revision == revision {
		if !previous.Valid || previous.String != digest {
			failure(c, 409, "POLICY_REVISION_CONFLICT")
			return
		}
		success(c, gin.H{"revision": revision})
		return
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM wb_department"); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return
	}
	for _, d := range p.Departments {
		if !d.Configured {
			continue
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO wb_department VALUES($1,$2,$3,$4)", d.ID, d.PersonalLimit, d.PoolLimit, d.Concurrency); err != nil {
			failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
			return
		}
	}
	// Missing former mappings remain disabled so deleting a mapping cannot bypass quota.
	if _, err = tx.ExecContext(ctx, "UPDATE wb_employee SET enabled=false"); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return
	}
	for _, u := range p.Employees {
		var exists bool
		if tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND deleted_at IS NULL)", u.UserID).Scan(&exists) != nil || !exists {
			failure(c, 400, "RELAY_USER_NOT_FOUND")
			return
		}
		var oldExternal, oldDept string
		err = tx.QueryRowContext(ctx, "SELECT external_id,COALESCE(department_id,'') FROM wb_employee WHERE user_id=$1", u.UserID).Scan(&oldExternal, &oldDept)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
			return
		}
		if oldExternal != "" && oldExternal != u.ID {
			failure(c, 409, "USER_MAPPING_IMMUTABLE")
			return
		}
		if oldExternal == "" || oldDept != u.DepartmentID {
			if _, err = tx.ExecContext(ctx, "INSERT INTO wb_membership VALUES($1,$2,$3,clock_timestamp())", u.UserID, u.ID, u.DepartmentID); err != nil {
				failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
				return
			}
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO wb_employee VALUES($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT(user_id) DO UPDATE SET department_id=EXCLUDED.department_id,mode=EXCLUDED.mode,personal_limit=EXCLUDED.personal_limit,concurrency=EXCLUDED.concurrency,enabled=EXCLUDED.enabled,configured=EXCLUDED.configured`, u.UserID, u.ID, u.DepartmentID, u.Mode, u.PersonalLimit, u.Concurrency, u.Enabled, u.Configured)
		if err != nil {
			failure(c, 409, "INVALID_USER_MAPPING")
			return
		}
		// Identity is known after explicit mapping; old department attribution remains unknown.
		if oldExternal == "" {
			_, err = tx.ExecContext(ctx, `INSERT INTO wb_usage SELECT id,$2,NULL,request_id,model,created_at,input_tokens,output_tokens,cache_creation_tokens+cache_read_tokens,COALESCE(image_count,0),COALESCE(duration_ms,0) FROM usage_logs WHERE user_id=$1 AND created_at>=now()-interval '180 days' ON CONFLICT(source_id) DO NOTHING`, u.UserID, u.ID)
			if err != nil {
				failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
				return
			}
		}
	}
	if _, err = tx.ExecContext(ctx, "UPDATE wb_policy_state SET revision=$1,policy_hash=$2 WHERE id=1", p.Revision, digest); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return
	}
	if tx.Commit() != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return
	}
	success(c, gin.H{"revision": p.Revision})
}

// monthStart uses fixed UTC+8, independent of host timezone and daylight saving.
func monthStart(now time.Time) time.Time {
	local := now.In(time.FixedZone("Asia/Shanghai", 8*3600))
	return time.Date(local.Year(), local.Month(), 1, 0, 0, 0, 0, local.Location())
}
func exhausted(limit, used int64) bool { return used >= limit }

// Managed is deliberately error-aware: database failure must never silently
// switch an employee from token accounting to wallet billing.
func Managed(ctx context.Context, userID int64) (bool, error) {
	if database == nil {
		return false, nil
	}
	var managed bool
	err := database.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM wb_employee WHERE user_id=$1)", userID).Scan(&managed)
	return managed, err
}

// Admit checks authoritative local usage and leases atomically. Queries use native
// user/time indexes; no synchronous workbench HTTP request is on the gateway path.
func Admit(c *gin.Context, userID int64, keyIDs ...int64) (func(), bool) {
	noop := func() {}
	if database == nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	// Reading completed artifacts and model discovery must remain possible at quota exhaustion.
	generation := c.Request.Method == http.MethodPost
	websocket := strings.EqualFold(c.GetHeader("Upgrade"), "websocket")
	if !generation && !websocket {
		managed, err := Managed(c.Request.Context(), userID)
		if err != nil {
			failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
			return noop, false
		}
		c.Set("workbench_managed", managed)
		return noop, true
	}
	ctx := c.Request.Context()
	tx, err := database.BeginTx(ctx, nil)
	if err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock($1)", lockID); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	var u Employee
	err = tx.QueryRowContext(ctx, "SELECT external_id,COALESCE(department_id,''),mode,personal_limit,concurrency,enabled,configured FROM wb_employee WHERE user_id=$1", userID).Scan(&u.ID, &u.DepartmentID, &u.Mode, &u.PersonalLimit, &u.Concurrency, &u.Enabled, &u.Configured)
	if errors.Is(err, sql.ErrNoRows) {
		return noop, true
	}
	if err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	c.Set("workbench_managed", true)
	if !u.Enabled || !u.Configured {
		failure(c, 403, "QUOTA_NOT_CONFIGURED")
		return noop, false
	}
	// A websocket can contain multiple generations. Reject rather than allow a
	// single admission to bypass later limits; clients can use Responses SSE.
	if websocket {
		failure(c, 400, "WORKBENCH_USE_RESPONSES_SSE")
		return noop, false
	}
	var used int64
	if err = tx.QueryRowContext(ctx, "SELECT COALESCE(sum(input_tokens+output_tokens+cache_creation_tokens+cache_read_tokens),0) FROM usage_logs WHERE user_id=$1 AND created_at >= $2", userID, monthStart(time.Now())).Scan(&used); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	if exhausted(u.PersonalLimit, used) {
		failure(c, 429, "PERSONAL_QUOTA_EXHAUSTED")
		return noop, false
	}
	if u.Mode == "inherited" {
		var limit, poolUsed int64
		if tx.QueryRowContext(ctx, "SELECT pool_limit FROM wb_department WHERE id=$1", u.DepartmentID).Scan(&limit) != nil {
			failure(c, 403, "QUOTA_NOT_CONFIGURED")
			return noop, false
		}
		err = tx.QueryRowContext(ctx, `SELECT COALESCE(sum(l.input_tokens+l.output_tokens+l.cache_creation_tokens+l.cache_read_tokens),0) FROM usage_logs l JOIN wb_employee e ON e.user_id=l.user_id WHERE e.department_id=$1 AND e.mode='inherited' AND l.created_at >= $2`, u.DepartmentID, monthStart(time.Now())).Scan(&poolUsed)
		if err != nil {
			failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
			return noop, false
		}
		if exhausted(limit, poolUsed) {
			failure(c, 429, "DEPARTMENT_QUOTA_EXHAUSTED")
			return noop, false
		}
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM wb_lease WHERE expires_at<now()"); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	var active int
	if tx.QueryRowContext(ctx, "SELECT count(*) FROM wb_lease WHERE user_id=$1", userID).Scan(&active) != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	if active >= u.Concurrency {
		failure(c, 429, "CONCURRENCY_EXHAUSTED")
		return noop, false
	}
	bytes := make([]byte, 16)
	if _, err = rand.Read(bytes); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	lease := hex.EncodeToString(bytes)
	if _, err = tx.ExecContext(ctx, "INSERT INTO wb_lease(id,user_id,expires_at) VALUES($1,$2,now()+interval '120 seconds')", lease, userID); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	if tx.Commit() != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		return noop, false
	}
	if len(keyIDs) > 0 {
		var purpose string
		if database.QueryRowContext(ctx, "SELECT purpose FROM wb_key WHERE key_id=$1 AND user_id=$2", keyIDs[0], userID).Scan(&purpose) == nil {
			c.Set("workbench_key_purpose", purpose)
		}
	}
	finishAudit, auditOK := capture(c, userID)
	if !auditOK {
		_, _ = database.ExecContext(context.Background(), "DELETE FROM wb_lease WHERE id=$1", lease)
		return noop, false
	}
	if _, err = database.ExecContext(ctx, "UPDATE wb_lease SET client_id=$2 WHERE id=$1", lease, c.Writer.Header().Get("X-Workbench-Request-ID")); err != nil {
		failure(c, 503, "QUOTA_STORE_UNAVAILABLE")
		finishAudit()
		return noop, false
	}
	done := make(chan struct{})
	renewCtx, cancel := context.WithCancel(c.Request.Context())
	c.Request = c.Request.WithContext(renewCtx)
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-renewCtx.Done():
				return
			case <-ticker.C:
				ctx, stop := context.WithTimeout(context.Background(), 5*time.Second)
				_, err := database.ExecContext(ctx, "UPDATE wb_lease SET expires_at=now()+interval '120 seconds' WHERE id=$1", lease)
				stop()
				if err != nil {
					cancel()
					return
				}
			}
		}
	}()
	return func() {
		finishAudit()
		close(done)
		cancel()
		ctx, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		// Keep successful, not-yet-accounted requests in flight until the native
		// usage transaction commits. Otherwise fast sequential calls bypass quotas
		// during the asynchronous billing queue delay. Stale leases expire safely.
		_, _ = database.ExecContext(ctx, "UPDATE wb_lease SET completed=true WHERE id=$1", lease)
		_, _ = database.ExecContext(ctx, "DELETE FROM wb_lease WHERE id=$1 AND (recorded OR $2)", lease, c.Writer.Status() >= 400)
	}, true
}

func Concurrency(userID int64, fallback int) int {
	if database == nil {
		return fallback
	}
	var n int
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if database.QueryRowContext(ctx, "SELECT concurrency FROM wb_employee WHERE user_id=$1 AND configured=true", userID).Scan(&n) == nil && n > 0 {
		return n
	}
	return fallback
}

func usage(c *gin.Context) {
	expireAudits()
	from, err := time.Parse(time.RFC3339Nano, c.Query("from"))
	if err != nil {
		failure(c, 400, "INVALID_DATE")
		return
	}
	until, err := time.Parse(time.RFC3339Nano, c.Query("until"))
	if err != nil || until.Before(from) {
		failure(c, 400, "INVALID_DATE")
		return
	}
	after, err := strconv.ParseInt(c.DefaultQuery("after", "0"), 10, 64)
	if err != nil || after < 0 {
		failure(c, 400, "INVALID_CURSOR")
		return
	}
	rows, err := database.QueryContext(c.Request.Context(), `SELECT u.source_id,u.user_id,u.department_id,u.request_id,u.model,u.occurred_at,u.input_tokens,u.output_tokens,u.cache_tokens,u.image_count,u.duration_ms,COALESCE(x.channel,'client'),x.run_id,x.tool_key FROM wb_usage u LEFT JOIN wb_employee e ON e.external_id=u.user_id LEFT JOIN wb_trace t ON t.user_id=e.user_id AND t.request_id=u.request_id LEFT JOIN wb_context x ON x.client_id=t.client_id WHERE u.occurred_at >= $1 AND u.occurred_at <= $2 AND u.source_id>$3 ORDER BY u.source_id LIMIT 501`, from, until, after)
	if err != nil {
		failure(c, 503, "USAGE_UNAVAILABLE")
		return
	}
	defer rows.Close()
	items := []gin.H{}
	cursor := after
	more := false
	for rows.Next() {
		var id, input, output, cache, images, duration int64
		var user, channel string
		var dept, request, model, run, tool sql.NullString
		var at time.Time
		if rows.Scan(&id, &user, &dept, &request, &model, &at, &input, &output, &cache, &images, &duration, &channel, &run, &tool) != nil {
			failure(c, 503, "USAGE_UNAVAILABLE")
			return
		}
		if len(items) == 500 {
			more = true
			break
		}
		cursor = id
		var department any
		if dept.Valid && dept.String != "" {
			department = dept.String
		}
		var runID, toolKey any
		if run.Valid && run.String != "" {
			runID = run.String
		}
		if tool.Valid && tool.String != "" {
			toolKey = tool.String
		}
		items = append(items, gin.H{"sourceId": strconv.FormatInt(id, 10), "userId": user, "departmentId": department, "requestId": request.String, "model": model.String, "occurredAt": at.UTC().Format(time.RFC3339Nano), "inputTokens": input, "outputTokens": output, "cacheTokens": cache, "imageCount": images, "durationMs": duration, "status": "recorded", "channel": channel, "runId": runID, "toolKey": toolKey})
	}
	if rows.Err() != nil {
		failure(c, 503, "USAGE_UNAVAILABLE")
		return
	}
	success(c, gin.H{"items": items, "nextCursor": cursor, "hasMore": more})
}
