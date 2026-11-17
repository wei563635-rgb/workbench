package workbench

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

func callTestDB(t *testing.T) {
	t.Helper()
	dsn := os.Getenv("WORKBENCH_TEST_DSN")
	if dsn == "" {
		t.Skip("isolated PostgreSQL DSN required")
	}
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatal(err)
	}
	schema := fmt.Sprintf("wb_calls_%d", time.Now().UnixNano())
	if _, err = db.Exec("CREATE SCHEMA " + schema); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if database != nil {
			database.Close()
			database = nil
		}
		db.Exec("DROP SCHEMA " + schema + " CASCADE")
		db.Close()
	})
	scoped, err := sql.Open("postgres", dsn+" search_path="+schema)
	if err != nil {
		t.Fatal(err)
	}
	defer scoped.Close()
	_, err = scoped.Exec(`CREATE TABLE usage_logs(id bigint PRIMARY KEY,user_id bigint,request_id text,model text,created_at timestamptz,input_tokens bigint,output_tokens bigint,cache_creation_tokens bigint,cache_read_tokens bigint,image_count bigint,duration_ms bigint);`)
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("WORKBENCH_AUDIT_KEY", base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{3}, 32)))
	if err = Initialize(dsn + " search_path=" + schema); err != nil {
		t.Fatal(err)
	}
	gin.SetMode(gin.TestMode)
}
func TestCallStatusesAndGenerationBoundary(t *testing.T) {
	tests := []struct {
		code                int
		body, content, want string
	}{
		{200, `{"choices":[]}`, "application/json", "success"},
		{200, "data: {\"error\":{\"message\":\"failed\"}}\n\n", "text/event-stream", "failed"},
		{200, "data: {\"delta\":\"partial\"}\n\n", "text/event-stream", "unknown"},
		{200, "data: [DONE]\n\n", "text/event-stream", "success"},
		{429, `{"error":"quota"}`, "application/json", "rejected"},
		{502, `{}`, "application/json", "failed"},
	}
	for _, tc := range tests {
		if got := callStatus(tc.code, []byte(tc.body), tc.content, nil); got != tc.want {
			t.Fatalf("got %s want %s", got, tc.want)
		}
	}
	if generationPath("GET", "/v1/images/tasks/1") || generationPath("POST", "/api/v1/admin/accounts/1/test") {
		t.Fatal("polls and admin tests must not create calls")
	}
	if !generationPath("POST", "/v1beta/models/gemini-3:streamGenerateContent") {
		t.Fatal("Google generation omitted")
	}
}
func TestCallJournalCapturesRejectsAndIgnoresForgedInternalHeaders(t *testing.T) {
	callTestDB(t)
	_, err := database.Exec(`INSERT INTO wb_employee VALUES(42,'employee','department','independent',100,1,true,false);INSERT INTO wb_key VALUES(7,42,'client')`)
	if err != nil {
		t.Fatal(err)
	}
	r := gin.New()
	r.Use(CaptureCalls())
	r.POST("/v1/responses", func(c *gin.Context) {
		IdentifyCall(c, 42, 7)
		_, ok := Admit(c, 42, 7)
		if ok {
			t.Error("unconfigured employee admitted")
		}
	})
	w := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/v1/responses", strings.NewReader(`{"model":"gpt-6-luna","input":"private prompt","api_key":"sk-abcdefghijklmnop"}`))
	req.Header.Set("X-Workbench-Call-ID", "wb_01234567890123456789012345678901")
	req.Header.Set("X-Workbench-Run-ID", "forged")
	r.ServeHTTP(w, req)
	if w.Code != 403 {
		t.Fatalf("expected 403 got %d", w.Code)
	}
	var status, channel string
	var logical, run sql.NullString
	var payload []byte
	var input sql.NullInt64
	if err = database.QueryRow("SELECT status,channel,logical_id,run_id,payload,input_tokens FROM wb_call").Scan(&status, &channel, &logical, &run, &payload, &input); err != nil {
		t.Fatal(err)
	}
	if status != "rejected" || channel != "key" || logical.Valid || run.Valid || input.Valid {
		t.Fatalf("bad rejected call metadata: %s %s", status, channel)
	}
	if bytes.Contains(payload, []byte("private prompt")) {
		t.Fatal("payload persisted in plaintext")
	}
	plain, err := openAudit(payload)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(plain, []byte("private prompt")) || bytes.Contains(plain, []byte("sk-abcdefghijklmnop")) {
		t.Fatal("redaction lost prompt or exposed credential")
	}
	var count int
	if err = database.QueryRow("SELECT count(*) FROM wb_usage").Scan(&count); err != nil || count != 0 {
		t.Fatal("rejection changed token usage")
	}
}
func TestCallJournalInternalCorrelationStreamingAndLateUsage(t *testing.T) {
	callTestDB(t)
	_, err := database.Exec(`INSERT INTO wb_employee VALUES(42,'employee','department','independent',100,1,true,true);INSERT INTO wb_key VALUES(7,42,'internal')`)
	if err != nil {
		t.Fatal(err)
	}
	var event string
	r := gin.New()
	r.Use(CaptureCalls())
	r.POST("/v1/responses", func(c *gin.Context) {
		IdentifyCall(c, 42, 7)
		event = c.Writer.Header().Get("X-Workbench-Request-ID")
		state := c.Request.Context().Value(callContextKey{}).(*callState)
		RecordAttempt(c.Request.Context(), 3, 401, false, time.Millisecond)
		RecordAttempt(c.Request.Context(), 4, 200, false, time.Millisecond)
		if state.id != event {
			t.Error("correlation changed")
		}
		c.Header("Content-Type", "text/event-stream")
		c.Writer.WriteString("data: {\"type\":\"response.completed\"}\n\n")
		c.Writer.Flush()
	})
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/v1/responses", strings.NewReader(`{"model":"gpt-6-luna","input":"prompt"}`))
	req.Header.Set("X-Workbench-Call-ID", "wb_01234567890123456789012345678901")
	r.ServeHTTP(w, req)
	if w.Body.String() != "data: {\"type\":\"response.completed\"}\n\n" {
		t.Fatal("capture modified stream")
	}
	var logical, status string
	var payload []byte
	if err = database.QueryRow("SELECT logical_id,status,payload FROM wb_call").Scan(&logical, &status, &payload); err != nil {
		t.Fatal(err)
	}
	if logical != "wb_01234567890123456789012345678901" || status != "success" {
		t.Fatalf("bad correlation/status %s %s", logical, status)
	}
	plain, _ := openAudit(payload)
	var audit map[string]any
	json.Unmarshal(plain, &audit)
	if len(audit["attempts"].([]any)) != 2 {
		t.Fatal("attempts missing")
	}
	// Native metering arrives after the response; an update is visible in the change feed.
	_, err = database.Exec("INSERT INTO wb_trace VALUES(42,'upstream-id',$1)", event)
	if err != nil {
		t.Fatal(err)
	}
	_, err = database.Exec(`INSERT INTO usage_logs VALUES(9,42,'upstream-id','gpt-6-luna',now(),10,20,2,3,0,50)`)
	if err != nil {
		t.Fatal(err)
	}
	var tokens int64
	if err = database.QueryRow("SELECT input_tokens+output_tokens+cache_tokens FROM wb_call").Scan(&tokens); err != nil || tokens != 35 {
		t.Fatalf("late usage lost: %d %v", tokens, err)
	}
}
func TestCallAttemptCancellation(t *testing.T) {
	if callStatus(200, []byte(`{}`), "application/json", context.Canceled) != "cancelled" {
		t.Fatal("cancellation misreported")
	}
}

func TestNativeTaskCompletionUpdatesOriginalCall(t *testing.T) {
	callTestDB(t)
	_, err := database.Exec(`INSERT INTO wb_employee VALUES(42,'employee','department','independent',100,1,true,true);INSERT INTO wb_key VALUES(7,42,'client')`)
	if err != nil {
		t.Fatal(err)
	}
	r := gin.New()
	r.Use(CaptureCalls())
	r.POST("/v1/images/generations/async", func(c *gin.Context) {
		IdentifyCall(c, 42, 7)
		NativeTaskSaved(c.Request.Context(), "task1", 42, "processing", 0, nil, nil)
		c.JSON(202, gin.H{"task_id": "task1", "status": "processing"})
	})
	// Register parameterized routes before serving: Gin caches context parameter capacity.
	r.GET("/audit/:id", callAudit)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("POST", "/v1/images/generations/async", strings.NewReader(`{"model":"image-model","prompt":"test"}`)))
	var status, id string
	if err = database.QueryRow("SELECT id,status FROM wb_call").Scan(&id, &status); err != nil || status != "running" {
		t.Fatalf("async submit not running: %s %v", status, err)
	}
	NativeTaskSaved(context.Background(), "task1", 42, "completed", 200, []byte(`{"data":[{"url":"https://example.com/result.png"}]}`), nil)
	if err = database.QueryRow("SELECT status FROM wb_call").Scan(&status); err != nil || status != "success" {
		t.Fatalf("async completion missing: %s %v", status, err)
	}
	w = httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("GET", "/audit/"+id, nil))
	if w.Code != 200 || !strings.Contains(w.Body.String(), "result.png") {
		t.Fatalf("completed task output not readable: status=%d body=%s", w.Code, w.Body.String())
	}
	var count int
	if err = database.QueryRow("SELECT count(*) FROM wb_call").Scan(&count); err != nil || count != 1 {
		t.Fatal("async completion created another call")
	}
}
