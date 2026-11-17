package service

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/Wei-Shaw/sub2api/internal/config"
	"github.com/stretchr/testify/require"
)

func TestWorkbenchEvolinkFailureEvidence(t *testing.T) {
	for _, tc := range []struct {
		status int
		body   string
		safe   bool
	}{
		{401, "", true}, {429, "", true}, {403, `{"error":{"code":"account_deactivated"}}`, true},
		{403, `{"error":{"code":"content_policy_violation"}}`, false}, {400, "", false}, {500, "", false}, {502, "", false}, {0, "", false},
	} {
		ctx := WithWorkbenchFallbackState(context.Background())
		RecordWorkbenchFallbackFailure(ctx, 1, &UpstreamFailoverError{StatusCode: tc.status, ResponseBody: []byte(tc.body)})
		require.Equal(t, tc.safe, workbenchFallbackFailuresSafe(ctx, map[int64]struct{}{1: {}}))
	}
	ctx := WithWorkbenchFallbackState(context.Background())
	RecordWorkbenchFallbackFailure(ctx, 1, &UpstreamFailoverError{StatusCode: 502})
	RecordWorkbenchFallbackFailure(ctx, 1, &UpstreamFailoverError{StatusCode: 401})
	require.False(t, workbenchFallbackFailuresSafe(ctx, map[int64]struct{}{1: {}}), "unsafe history cannot be overwritten")
	require.False(t, workbenchFallbackFailuresSafe(context.Background(), map[int64]struct{}{1: {}}))
}

func workbenchEvolinkTestService(accounts []Account) *OpenAIGatewayService {
	cfg := &config.Config{}
	cfg.Gateway.Scheduling.LoadBatchEnabled = false
	return &OpenAIGatewayService{accountRepo: schedulerTestOpenAIAccountRepo{accounts: accounts}, cache: &schedulerTestGatewayCache{}, cfg: cfg,
		concurrencyService: NewConcurrencyService(schedulerTestConcurrencyCache{})}
}

func TestWorkbenchEvolinkSchedulerPrimaryFallbackAndRecovery(t *testing.T) {
	t.Setenv("WORKBENCH_EVOLINK_FALLBACK_ENABLED", "true")
	accounts := []Account{
		{ID: 1, Platform: PlatformOpenAI, Type: AccountTypeAPIKey, Status: StatusActive, Schedulable: true, Concurrency: 1, Priority: 1},
		{ID: 2, Platform: PlatformOpenAI, Type: AccountTypeAPIKey, Status: StatusActive, Schedulable: true, Concurrency: 1, Priority: 0, Extra: map[string]any{"workbench_evolink_fallback": true}},
	}
	svc := workbenchEvolinkTestService(accounts)
	ctx := WithWorkbenchFallbackState(context.Background())
	selectAccount := func(excluded map[int64]struct{}) (*AccountSelectionResult, error) {
		selected, _, err := svc.SelectAccountWithScheduler(ctx, nil, "", "", "gpt-6-luna", excluded, OpenAIUpstreamTransportAny, false)
		return selected, err
	}
	selected, err := selectAccount(nil)
	require.NoError(t, err)
	require.EqualValues(t, 1, selected.Account.ID)
	selected.ReleaseFunc()
	RecordWorkbenchFallbackFailure(ctx, 1, &UpstreamFailoverError{StatusCode: 401})
	selected, err = selectAccount(map[int64]struct{}{1: {}})
	require.NoError(t, err)
	require.EqualValues(t, 2, selected.Account.ID)
	selected.ReleaseFunc()
	selected, err = selectAccount(nil)
	require.NoError(t, err)
	require.EqualValues(t, 1, selected.Account.ID)
	selected.ReleaseFunc()
	t.Setenv("WORKBENCH_EVOLINK_FALLBACK_ENABLED", "false")
	selected, err = selectAccount(map[int64]struct{}{1: {}})
	require.Error(t, err)
	require.Nil(t, selected)
}

func TestWorkbenchEvolinkSchedulerRejectsUnsafeExhaustionAndBusyPool(t *testing.T) {
	t.Setenv("WORKBENCH_EVOLINK_FALLBACK_ENABLED", "true")
	accounts := []Account{
		{ID: 1, Platform: PlatformOpenAI, Type: AccountTypeAPIKey, Status: StatusActive, Schedulable: true, Concurrency: 1},
		{ID: 2, Platform: PlatformOpenAI, Type: AccountTypeAPIKey, Status: StatusActive, Schedulable: true, Concurrency: 1, Extra: map[string]any{"workbench_evolink_fallback": true}},
	}
	svc := workbenchEvolinkTestService(accounts)
	ctx := WithWorkbenchFallbackState(context.Background())
	RecordWorkbenchFallbackFailure(ctx, 1, &UpstreamFailoverError{StatusCode: 502})
	selected, _, err := svc.SelectAccountWithScheduler(ctx, nil, "", "", "gpt-6-luna", map[int64]struct{}{1: {}}, OpenAIUpstreamTransportAny, false)
	require.Error(t, err)
	require.Nil(t, selected)
	svc.concurrencyService = NewConcurrencyService(schedulerTestConcurrencyCache{acquireResults: map[int64]bool{1: false, 2: true}})
	selected, _, _ = svc.SelectAccountWithScheduler(WithWorkbenchFallbackState(context.Background()), nil, "", "", "gpt-6-luna", nil, OpenAIUpstreamTransportAny, false)
	if selected != nil {
		require.EqualValues(t, 1, selected.Account.ID)
		if selected.ReleaseFunc != nil {
			selected.ReleaseFunc()
		}
	}
}

func TestWorkbenchEvolinkImagePollingAndNativeUsage(t *testing.T) {
	posts, polls := 0, 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		require.Equal(t, "Bearer test-key", r.Header.Get("Authorization"))
		w.Header().Set("Content-Type", "application/json")
		if r.Method == http.MethodPost {
			posts++
			require.Equal(t, "/v1/images/generations", r.URL.Path)
			io.WriteString(w, `{"id":"task-1","status":"processing","usage":{"credits_reserved":1}}`)
			return
		}
		polls++
		require.Equal(t, "/v1/tasks/task-1", r.URL.Path)
		io.WriteString(w, `{"id":"task-1","status":"completed","results":["https://example.com/image.png"],"usage":{"total_tokens":214,"text_input_tokens":18,"image_output_tokens":196,"credits_used":0.3655}}`)
	}))
	defer server.Close()
	resp, err := runWorkbenchEvolinkImage(context.Background(), server.URL, "test-key", []byte(`{}`), server.Client().Do, time.Millisecond)
	require.NoError(t, err)
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	usage, ok := extractOpenAIUsageFromJSONBytes(body)
	require.True(t, ok)
	require.Equal(t, 18, usage.InputTokens)
	require.Equal(t, 196, usage.OutputTokens)
	require.Equal(t, 1, posts)
	require.Equal(t, 1, polls)
	require.Equal(t, "task-1", resp.Header.Get("x-request-id"))
}

func TestWorkbenchEvolinkImageSubmissionIsNeverRetried(t *testing.T) {
	calls := 0
	_, err := runWorkbenchEvolinkImage(context.Background(), "https://api.evolink.ai", "test", []byte(`{}`), func(*http.Request) (*http.Response, error) { calls++; return nil, errors.New("connection reset") }, time.Millisecond)
	require.ErrorContains(t, err, "outcome unknown")
	require.Equal(t, 1, calls)
}

func TestWorkbenchEvolinkImagePayloadAndMeteringValidation(t *testing.T) {
	body, err := workbenchEvolinkPayload([]byte(`{"model":"gpt-image-2.5-flare","images":[{"image_url":"https://example.com/ref.png"}]}`),
		&OpenAIImagesRequest{Model: "gpt-image-2.5-flare", Prompt: "keep prompt", N: 1, InputImageURLs: []string{"https://example.com/ref.png"}})
	require.NoError(t, err)
	require.Contains(t, string(body), `"image_urls"`)
	require.NotContains(t, string(body), `"images"`)
	_, err = workbenchEvolinkPayload([]byte(`{"moderation":"low"}`), &OpenAIImagesRequest{Model: "gpt-image-2.5-flare"})
	require.Error(t, err)
	var result map[string]any
	require.NoError(t, json.Unmarshal([]byte(`{"results":["https://example.com/out.png"],"usage":{"credits_reserved":10}}`), &result))
	_, err = normalizeWorkbenchEvolinkImage("task", result)
	require.ErrorContains(t, err, "token usage")
	result["usage"] = map[string]any{"total_tokens": float64(20), "image_output_tokens": float64(10), "text_cached_input_tokens": float64(5)}
	resp, err := normalizeWorkbenchEvolinkImage("task", result)
	require.NoError(t, err)
	data, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	usage, ok := extractOpenAIUsageFromJSONBytes(data)
	require.True(t, ok)
	require.Equal(t, 10, usage.InputTokens)
	require.Equal(t, 5, usage.CacheReadInputTokens)
	for _, state := range []string{"failed", "completed"} {
		calls := 0
		_, err := runWorkbenchEvolinkImage(context.Background(), "https://api.evolink.ai", "test", []byte(`{}`), func(*http.Request) (*http.Response, error) {
			calls++
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(`{"id":"task","status":"` + state + `"}`))}, nil
		}, time.Millisecond)
		require.Error(t, err)
		require.Equal(t, 1, calls)
	}
}
