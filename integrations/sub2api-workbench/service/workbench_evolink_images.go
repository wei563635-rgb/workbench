package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

const workbenchEvolinkMetadataLimit = 4 << 20

type workbenchEvolinkDo func(*http.Request) (*http.Response, error)

func workbenchEvolinkPayload(body []byte, parsed *OpenAIImagesRequest) ([]byte, error) {
	if parsed == nil || parsed.Multipart || parsed.Stream || len(parsed.Uploads) > 0 || parsed.MaskUpload != nil {
		return nil, fmt.Errorf("Evolink fallback supports non-streaming JSON image requests with reference URLs")
	}
	var input map[string]json.RawMessage
	if err := json.Unmarshal(body, &input); err != nil {
		return nil, fmt.Errorf("invalid fallback image JSON")
	}
	allowed := map[string]bool{"model": true, "prompt": true, "n": true, "size": true, "quality": true, "background": true,
		"images": true, "image_urls": true, "image": true, "mask": true, "response_format": true, "stream": true}
	for key := range input {
		if !allowed[key] {
			return nil, fmt.Errorf("Evolink fallback does not support image option %q", key)
		}
	}
	if parsed.Model != "gpt-image-2.5-flare" {
		return nil, fmt.Errorf("image model is not approved for Evolink fallback")
	}
	payload := map[string]any{"model": parsed.Model, "prompt": parsed.Prompt, "n": parsed.N}
	if parsed.Size != "" {
		payload["size"] = parsed.Size
	}
	if parsed.Quality != "" {
		payload["quality"] = parsed.Quality
	}
	if parsed.Background != "" {
		payload["background"] = parsed.Background
	}
	if len(parsed.InputImageURLs) > 0 {
		payload["image_urls"] = parsed.InputImageURLs
	}
	if parsed.MaskImageURL != "" {
		payload["mask_url"] = parsed.MaskImageURL
	}
	return json.Marshal(payload)
}

func readWorkbenchEvolinkJSON(resp *http.Response) (map[string]any, error) {
	if resp == nil || resp.Body == nil {
		return nil, fmt.Errorf("empty Evolink response")
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, workbenchEvolinkMetadataLimit+1))
	if err != nil || len(body) > workbenchEvolinkMetadataLimit {
		return nil, fmt.Errorf("Evolink response unreadable or too large")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("Evolink image request rejected (HTTP %d); not resubmitted", resp.StatusCode)
	}
	var result map[string]any
	if json.Unmarshal(body, &result) != nil {
		return nil, fmt.Errorf("invalid Evolink JSON response")
	}
	return result, nil
}

// Submission is performed exactly once. Subsequent calls only poll that same
// provider task, including after timeout/errors; no cross-provider retry occurs.
func runWorkbenchEvolinkImage(ctx context.Context, baseURL, token string, payload []byte, do workbenchEvolinkDo, interval time.Duration) (*http.Response, error) {
	base := strings.TrimRight(baseURL, "/")
	if !strings.HasSuffix(base, "/v1") {
		base += "/v1"
	}
	request := func(method, path string, body []byte) (*http.Response, error) {
		req, err := http.NewRequestWithContext(ctx, method, base+path, bytes.NewReader(body))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/json")
		return do(req)
	}
	resp, err := request(http.MethodPost, "/images/generations", payload)
	if err != nil {
		return nil, fmt.Errorf("Evolink image submission outcome unknown; not resubmitted")
	}
	result, err := readWorkbenchEvolinkJSON(resp)
	if err != nil {
		return nil, err
	}
	taskID, _ := result["id"].(string)
	if taskID == "" {
		taskID, _ = result["task_id"].(string)
	}
	if taskID == "" || len(taskID) > 200 || strings.ContainsAny(taskID, "/\\?#") {
		return nil, fmt.Errorf("Evolink did not return a valid task ID; not resubmitted")
	}
	for {
		status, _ := result["status"].(string)
		switch status {
		case "completed", "succeeded", "success":
			return normalizeWorkbenchEvolinkImage(taskID, result)
		case "failed", "cancelled", "canceled", "error":
			return nil, fmt.Errorf("Evolink image task %s; not resubmitted", status)
		case "pending", "queued", "processing", "in_progress":
		default:
			return nil, fmt.Errorf("Evolink image task status is unsupported; not resubmitted")
		}
		timer := time.NewTimer(interval)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, fmt.Errorf("Evolink image task polling timed out; not resubmitted")
		case <-timer.C:
		}
		resp, err = request(http.MethodGet, "/tasks/"+url.PathEscape(taskID), nil)
		if err != nil {
			return nil, fmt.Errorf("Evolink task polling failed; not resubmitted")
		}
		result, err = readWorkbenchEvolinkJSON(resp)
		if err != nil {
			return nil, err
		}
		if returned, _ := result["id"].(string); returned != "" && returned != taskID {
			return nil, fmt.Errorf("Evolink task ownership mismatch")
		}
	}
}

// Convert provider token facts, never credit reservations, to the native ledger.
// Metadata memory is bounded by 4 MiB; polling uses O(1) retained responses.
func normalizeWorkbenchEvolinkImage(taskID string, result map[string]any) (*http.Response, error) {
	usage, _ := result["usage"].(map[string]any)
	integer := func(key string) (int64, bool) {
		n, ok := usage[key].(float64)
		return int64(n), ok && n >= 0 && n < 1e12 && float64(int64(n)) == n
	}
	total, totalOK := integer("total_tokens")
	output, outputOK := integer("image_output_tokens")
	textCached, _ := integer("text_cached_input_tokens")
	imageCached, _ := integer("image_cached_input_tokens")
	imageInput, _ := integer("image_input_tokens")
	for _, field := range []string{"text_cached_input_tokens", "image_cached_input_tokens", "image_input_tokens"} {
		if _, exists := usage[field]; exists {
			if _, valid := integer(field); !valid {
				return nil, fmt.Errorf("Evolink image token usage is invalid; reconciliation required")
			}
		}
	}
	if !totalOK || !outputOK || total <= 0 || output > total || textCached+imageCached > total-output {
		return nil, fmt.Errorf("Evolink image completed without valid token usage; reconciliation required")
	}
	images, _ := result["results"].([]any)
	data := make([]map[string]string, 0, len(images))
	for _, image := range images {
		value, _ := image.(string)
		if row, ok := image.(map[string]any); ok {
			value, _ = row["url"].(string)
		}
		u, err := url.Parse(value)
		if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil {
			return nil, fmt.Errorf("Evolink image completed with an invalid result URL")
		}
		data = append(data, map[string]string{"url": value})
	}
	if len(data) == 0 || len(data) > 10 {
		return nil, fmt.Errorf("Evolink image completed without valid images")
	}
	body, err := json.Marshal(map[string]any{"created": result["created"], "data": data, "model": "gpt-image-2.5-flare",
		"usage": map[string]any{"input_tokens": total - output, "output_tokens": output, "total_tokens": total,
			"input_tokens_details":  map[string]int64{"cached_tokens": textCached + imageCached, "image_tokens": imageInput},
			"output_tokens_details": map[string]int64{"image_tokens": output}},
		"_workbench_upstream": map[string]any{"provider": "evolink", "task_id": taskID, "usage": usage}})
	if err != nil {
		return nil, err
	}
	return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": []string{"application/json"}, "X-Request-Id": []string{taskID}}, Body: io.NopCloser(bytes.NewReader(body))}, nil
}

func (s *OpenAIGatewayService) forwardWorkbenchEvolinkImage(ctx context.Context, c *gin.Context, account *Account, body []byte, parsed *OpenAIImagesRequest) (*OpenAIForwardResult, error) {
	start := time.Now()
	payload, err := workbenchEvolinkPayload(body, parsed)
	if err != nil {
		return nil, err
	}
	base, err := s.validateUpstreamBaseURL(account.GetOpenAIBaseURL())
	if err != nil {
		return nil, err
	}
	u, err := url.Parse(base)
	if err != nil || u.Scheme != "https" || u.User != nil || u.RawQuery != "" || u.Fragment != "" ||
		(u.Path != "" && u.Path != "/v1") || (u.Host != "api.evolink.ai" && u.Host != "direct.evolink.ai") {
		return nil, fmt.Errorf("Evolink fallback requires an approved HTTPS upstream")
	}
	upstreamCtx, release := detachUpstreamContext(ctx)
	defer release()
	upstreamCtx, cancel := context.WithTimeout(upstreamCtx, 6*time.Minute)
	defer cancel()
	token, _, err := s.GetAccessToken(upstreamCtx, account)
	if err != nil {
		return nil, err
	}
	proxy := ""
	if account.ProxyID != nil && account.Proxy != nil {
		proxy = account.Proxy.URL()
	}
	resp, err := runWorkbenchEvolinkImage(upstreamCtx, base, token, payload, func(req *http.Request) (*http.Response, error) {
		return s.doOpenAIUpstream(req, proxy, account)
	}, 2*time.Second)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	SetOpsUpstreamModel(c, parsed.Model)
	usage, count, sizes, err := s.handleOpenAIImagesNonStreamingResponse(upstreamCtx, resp, c, account, parsed)
	if err != nil {
		return nil, err
	}
	return &OpenAIForwardResult{RequestID: resp.Header.Get("x-request-id"), UpstreamHeaders: resp.Header, Usage: usage,
		Model: parsed.Model, UpstreamModel: parsed.Model, ResponseHeaders: resp.Header.Clone(), Duration: time.Since(start),
		ImageCount: count, ImageSize: parsed.SizeTier, ImageInputSize: parsed.Size, ImageOutputSizes: sizes}, nil
}
