package service

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/Wei-Shaw/sub2api/internal/config"
	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/require"
)

type workbenchEvolinkLiveHTTP struct {
	HTTPUpstream
	client *http.Client
}

func (h workbenchEvolinkLiveHTTP) Do(req *http.Request, _ string, _ int64, _ int) (*http.Response, error) {
	return h.client.Do(req)
}

// Opt-in supplier smoke. The key is supplied in process memory by the remote
// credential loader; no key, prompt, image URL or image bytes are printed.
func TestWorkbenchEvolinkLiveImageRoundTrip(t *testing.T) {
	key := os.Getenv("WORKBENCH_EVOLINK_LIVE_KEY")
	if key == "" {
		t.Skip("explicit supplier smoke credential required")
	}
	gin.SetMode(gin.TestMode)
	account := &Account{ID: 0, Platform: PlatformOpenAI, Type: AccountTypeAPIKey, Concurrency: 1,
		Credentials: map[string]any{"api_key": key, "base_url": "https://api.evolink.ai"},
		Extra:       map[string]any{"workbench_evolink_fallback": true}}
	client := &http.Client{Timeout: 75 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	svc := &OpenAIGatewayService{cfg: &config.Config{}, httpUpstream: workbenchEvolinkLiveHTTP{client: client}}
	generate := func(reference string) string {
		t.Helper()
		payload := map[string]any{"model": "gpt-image-2.5-flare", "prompt": "A plain red circle on a white background. No text.", "size": "1024x1024", "n": 1, "quality": "low", "response_format": "url"}
		endpoint := "/v1/images/generations"
		if reference != "" {
			endpoint = "/v1/images/edits"
			payload["prompt"] = "Change the red circle to blue. Keep the white background. No text."
			payload["images"] = []map[string]string{{"image_url": reference}}
		}
		body, err := json.Marshal(payload)
		require.NoError(t, err)
		rec := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(rec)
		c.Request = httptest.NewRequest(http.MethodPost, endpoint, bytes.NewReader(body))
		c.Request.Header.Set("Content-Type", "application/json")
		parsed, err := svc.ParseOpenAIImagesRequest(c, body)
		require.NoError(t, err)
		result, err := svc.ForwardImages(context.Background(), c, account, body, parsed, "")
		require.NoError(t, err)
		require.Equal(t, 200, rec.Code)
		require.Greater(t, result.Usage.InputTokens+result.Usage.OutputTokens, 0)
		require.Equal(t, 1, result.ImageCount)
		var response struct {
			Data []struct {
				URL string `json:"url"`
			} `json:"data"`
		}
		require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &response))
		require.Len(t, response.Data, 1)
		t.Logf("native image adapter completed: reference=%t input_tokens=%d output_tokens=%d", reference != "", result.Usage.InputTokens, result.Usage.OutputTokens)
		return response.Data[0].URL
	}
	reference := generate("")
	generate(reference)
	// Native Responses passthrough uses the same account/base URL. Verify that
	// the supplier accepts the exact text model without substituting another.
	body := bytes.NewBufferString(`{"model":"gpt-6-luna","input":"Reply with exactly OK.","max_output_tokens":64}`)
	req, err := http.NewRequest(http.MethodPost, "https://api.evolink.ai/v1/responses", body)
	require.NoError(t, err)
	req.Header.Set("Authorization", "Bearer "+key)
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	require.NoError(t, err)
	defer resp.Body.Close()
	require.Equal(t, 200, resp.StatusCode)
	data, err := io.ReadAll(io.LimitReader(resp.Body, workbenchEvolinkMetadataLimit))
	require.NoError(t, err)
	usage, ok := extractOpenAIUsageFromJSONBytes(data)
	require.True(t, ok)
	require.Greater(t, usage.InputTokens+usage.OutputTokens, 0)
	t.Logf("responses supplier smoke completed: input_tokens=%d output_tokens=%d", usage.InputTokens, usage.OutputTokens)
}
