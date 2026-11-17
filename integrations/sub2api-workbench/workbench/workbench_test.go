package workbench

import (
	"bytes"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/gin-gonic/gin"
	"net/http/httptest"
	"os"
	"testing"
	"time"
)

func TestMonthAndThreshold(t *testing.T) {
	at := time.Date(2026, 9, 30, 16, 0, 0, 0, time.UTC)
	if !monthStart(at).Equal(at) {
		t.Fatal("month must reset at UTC+8 midnight")
	}
	if !exhausted(0, 0) || !exhausted(50, 50) || exhausted(50, 49) {
		t.Fatal("inclusive quota boundary")
	}
	if validate(Policy{Revision: 1, Employees: []Employee{{ID: "u", UserID: 1, Mode: "bogus"}}}) == nil {
		t.Fatal("invalid mode accepted")
	}
}

// Run only against a disposable database supplied by the isolated test harness.
func TestPostgresQuotaLifecycle(t *testing.T) {
	t.Setenv("WORKBENCH_AUDIT_KEY", base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32)))
	dsn := os.Getenv("WORKBENCH_TEST_DSN")
	if dsn == "" {
		t.Skip("isolated PostgreSQL DSN not provided")
	}
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	// Every invocation owns a fresh schema; never erase an existing test database.
	schemaName := fmt.Sprintf("wb_test_%d", time.Now().UnixNano())
	if _, err = db.Exec("CREATE SCHEMA " + schemaName); err != nil {
		t.Fatal(err)
	}
	defer db.Exec("DROP SCHEMA " + schemaName + " CASCADE")
	dsn += " search_path=" + schemaName
	scoped, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer scoped.Close()
	db = scoped
	_, err = db.Exec(`CREATE TABLE IF NOT EXISTS users(id bigint PRIMARY KEY,deleted_at timestamptz);
 CREATE TABLE IF NOT EXISTS usage_logs(id bigint PRIMARY KEY,user_id bigint,request_id text,model text,created_at timestamptz,input_tokens bigint,output_tokens bigint,cache_creation_tokens bigint,cache_read_tokens bigint,image_count bigint,duration_ms bigint);
 INSERT INTO users VALUES(1,NULL),(2,NULL) ON CONFLICT DO NOTHING;`)
	if err != nil {
		t.Fatal(err)
	}
	if err = Initialize(dsn); err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	gin.SetMode(gin.TestMode)
	apply := func(p Policy) {
		t.Helper()
		raw, _ := json.Marshal(p)
		w := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(w)
		c.Request = httptest.NewRequest("PUT", "/", bytes.NewReader(raw))
		applyPolicy(c)
		if w.Code != 200 {
			t.Fatalf("policy failed: %d %s", w.Code, w.Body.String())
		}
	}
	p := Policy{Revision: 1, Departments: []Department{{ID: "a", PersonalLimit: 100, PoolLimit: 100, Concurrency: 1, Configured: true}}, Employees: []Employee{{ID: "u", UserID: 1, DepartmentID: "a", Mode: "inherited", PersonalLimit: 100, Concurrency: 1, Enabled: true, Configured: true}, {ID: "v", UserID: 2, DepartmentID: "a", Mode: "inherited", PersonalLimit: 100, Concurrency: 1, Enabled: true, Configured: true}}}
	apply(p)
	apply(p) // Exact retry is idempotent; a conflicting body at the same revision is not.
	conflicting := Policy{Revision: 1}
	conflictBody, _ := json.Marshal(conflicting)
	conflictWriter := httptest.NewRecorder()
	conflictContext, _ := gin.CreateTestContext(conflictWriter)
	conflictContext.Request = httptest.NewRequest("PUT", "/", bytes.NewReader(conflictBody))
	applyPolicy(conflictContext)
	if conflictWriter.Code != 409 {
		t.Fatal("conflicting revision incorrectly acknowledged")
	}
	admit := func(id int64) (func(), bool, int, string) {
		w := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(w)
		c.Request = httptest.NewRequest("POST", "/v1/responses", nil)
		release, ok := Admit(c, id)
		status := w.Code
		if ok {
			c.Writer.WriteHeader(400)
		}
		return release, ok, status, w.Body.String()
	}
	release, ok, _, _ := admit(1)
	if !ok {
		t.Fatal("initial request rejected")
	}
	_, ok, status, _ := admit(1)
	if ok || status != 429 {
		t.Fatal("second key bypassed user concurrency")
	}
	release()
	// A completed HTTP response remains in flight until native accounting commits.
	pendingWriter := httptest.NewRecorder()
	pending, _ := gin.CreateTestContext(pendingWriter)
	pending.Request = httptest.NewRequest("POST", "/v1/responses", nil)
	complete, accepted := Admit(pending, 1)
	if !accepted {
		t.Fatal("pending request not admitted")
	}
	LinkUsage(pending.Request.Context(), 1, "pending")
	complete()
	_, accepted, _, _ = admit(1)
	if accepted {
		t.Fatal("async accounting gap bypassed concurrency")
	}
	if _, err = db.Exec("INSERT INTO usage_logs VALUES(99,1,'pending','model',now(),0,0,0,0,0,1)"); err != nil {
		t.Fatal(err)
	}
	release, accepted, _, _ = admit(1)
	if !accepted {
		t.Fatal("native usage commit did not release lease")
	}
	release()
	_, err = db.Exec(`INSERT INTO usage_logs VALUES(1,1,'r1','model',now(),30,0,0,0,0,1),(2,2,'r2','model',now(),70,0,0,0,0,1)`)
	if err != nil {
		t.Fatal(err)
	}
	_, ok, _, reason := admit(1)
	if ok || !bytes.Contains([]byte(reason), []byte("DEPARTMENT_QUOTA_EXHAUSTED")) {
		t.Fatal("department quota did not reject", reason)
	}
	p.Revision = 2
	p.Employees[0].Mode = "independent"
	p.Employees[0].PersonalLimit = 200
	apply(p)
	release, ok, _, reason = admit(1)
	if !ok {
		t.Fatal("independent employee incorrectly blocked", reason)
	}
	release()
	release, ok, _, reason = admit(2)
	if !ok {
		t.Fatal("retroactive pool removal failed", reason)
	}
	release()
	p.Revision = 3
	p.Employees[0].Mode = "inherited"
	p.Employees[0].PersonalLimit = 100
	apply(p)
	_, ok, _, reason = admit(2)
	if ok {
		t.Fatal("restoring inheritance did not refill pool")
	}
	var department string
	if err = db.QueryRow("SELECT department_id FROM wb_usage WHERE source_id=1").Scan(&department); err != nil || department != "a" {
		t.Fatal("historical attribution missing", err)
	}
	p.Revision = 4
	p.Employees = p.Employees[1:]
	apply(p)
	_, ok, status, _ = admit(1)
	if ok || status != 403 {
		t.Fatal("removing mapping enabled bypass")
	}
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest("GET", fmt.Sprintf("/usage?from=%s&until=%s&after=0", time.Now().Add(-time.Hour).UTC().Format(time.RFC3339), time.Now().Add(time.Hour).UTC().Format(time.RFC3339)), nil)
	usage(c)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	if !bytes.Contains(w.Body.Bytes(), []byte(`"sourceId":"1"`)) {
		t.Fatal("usage sync lost source ID")
	}
}

func TestAuditEncryptionAndRedaction(t *testing.T) {
	t.Setenv("WORKBENCH_AUDIT_KEY", base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{3}, 32)))
	payload, _ := json.Marshal(cleanJSON([]byte(`{"messages":[{"content":"hello"}],"api_key":"secret","nested":{"refresh_token":"hidden"}}`)))
	if bytes.Contains(payload, []byte("secret")) || bytes.Contains(payload, []byte("hidden")) {
		t.Fatal("credential persisted")
	}
	encrypted, err := sealAudit(payload)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(encrypted, []byte("hello")) {
		t.Fatal("plaintext audit")
	}
	decoded, err := openAudit(encrypted)
	if err != nil || !bytes.Equal(decoded, payload) {
		t.Fatal("audit roundtrip failed")
	}
	encrypted[len(encrypted)-1] ^= 1
	if _, err = openAudit(encrypted); err == nil {
		t.Fatal("tampered audit accepted")
	}
}
