package service

import (
	"context"
	"fmt"
	"net/http"
)

// imageUpstreamTransportError lets the image handler try another account when
// transport failed before response headers. A canceled caller cannot retry.
func imageUpstreamTransportError(ctx context.Context, safeMessage string) error {
	if ctx.Err() != nil {
		return fmt.Errorf("upstream request failed: %s", safeMessage)
	}
	return &UpstreamFailoverError{
		StatusCode:   http.StatusBadGateway,
		ResponseBody: []byte(`{"error":{"message":"upstream image transport failed"}}`),
	}
}
