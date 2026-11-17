//go:build unit

package service

import (
	"context"
	"errors"
	"net/http"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestWorkbenchImageTransportErrorCanFailOver(t *testing.T) {
	body := []byte(`{"model":"gpt-image-2.5-flare","prompt":"a red square"}`)
	c, rec := newOpenAIImagesTestContext(t, body)
	upstream := &httpUpstreamRecorder{err: errors.New("read tcp: connection reset by peer")}
	svc := newOpenAIImagesTestService(upstream)
	parsed, parseErr := svc.ParseOpenAIImagesRequest(c, body)
	require.NoError(t, parseErr)
	result, err := svc.ForwardImages(context.Background(), c, directImagesTestAccount(), body, parsed, "")
	require.Nil(t, result)
	var failover *UpstreamFailoverError
	require.ErrorAs(t, err, &failover)
	require.Equal(t, http.StatusBadGateway, failover.StatusCode)
	require.NotContains(t, string(failover.ResponseBody), "connection reset")
	require.Empty(t, rec.Body.String(), "handler must still be able to switch accounts")
}

func TestWorkbenchImageTransportErrorCanceledCallerDoesNotFailOver(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	err := imageUpstreamTransportError(ctx, "connection reset by peer")
	var failover *UpstreamFailoverError
	require.False(t, errors.As(err, &failover))
}
