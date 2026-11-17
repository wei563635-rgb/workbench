//go:build unit

package middleware

import (
	"database/sql"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/Wei-Shaw/sub2api/internal/workbench"
	"github.com/lib/pq"
)

// The production extension fails closed without its quota database. Native
// middleware tests must initialize a disposable schema, not bypass admission.
func TestMain(m *testing.M) {
	dsn := os.Getenv("WORKBENCH_TEST_DSN")
	if dsn == "" {
		os.Exit(m.Run())
	}
	if strings.HasPrefix(dsn, "postgres://") || strings.HasPrefix(dsn, "postgresql://") {
		parsed, err := pq.ParseURL(dsn)
		if err != nil {
			fmt.Fprintln(os.Stderr, "invalid isolated quota test DSN")
			os.Exit(2)
		}
		dsn = parsed
	}
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "isolated quota test database unavailable")
		os.Exit(2)
	}
	schema := fmt.Sprintf("wb_middleware_%d", time.Now().UnixNano())
	quoted := pq.QuoteIdentifier(schema)
	_, err = db.Exec("CREATE SCHEMA " + quoted)
	if err != nil {
		fmt.Fprintln(os.Stderr, "isolated quota test schema creation failed")
		os.Exit(2)
	}
	_, err = db.Exec(`CREATE TABLE ` + quoted + `.usage_logs(id bigint PRIMARY KEY,user_id bigint,request_id text,model text,created_at timestamptz,input_tokens bigint,output_tokens bigint,cache_creation_tokens bigint,cache_read_tokens bigint,image_count bigint,duration_ms bigint)`)
	if err == nil {
		err = workbench.Initialize(dsn + " search_path=" + schema)
	}
	if err != nil {
		_, _ = db.Exec("DROP SCHEMA " + quoted + " CASCADE")
		db.Close()
		fmt.Fprintln(os.Stderr, "isolated quota test initialization failed")
		os.Exit(2)
	}
	code := m.Run()
	_, err = db.Exec("DROP SCHEMA " + quoted + " CASCADE")
	db.Close()
	if err != nil {
		fmt.Fprintln(os.Stderr, "isolated quota test cleanup failed")
		code = 2
	}
	os.Exit(code)
}
