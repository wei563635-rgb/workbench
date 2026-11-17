package repository

import (
	"github.com/Wei-Shaw/sub2api/internal/pkg/tlsfingerprint"
	"github.com/Wei-Shaw/sub2api/internal/workbench"
	"net/http"
	"time"
)

func (s *httpUpstreamService) Do(req *http.Request, proxyURL string, accountID int64, concurrency int) (*http.Response, error) {
	start := time.Now()
	response, err := s.doWithoutCallRecord(req, proxyURL, accountID, concurrency)
	if req != nil {
		status := 0
		if response != nil {
			status = response.StatusCode
		}
		workbench.RecordAttempt(req.Context(), accountID, status, err != nil, time.Since(start))
	}
	return response, err
}
func (s *httpUpstreamService) DoWithTLS(req *http.Request, proxyURL string, accountID int64, concurrency int, profile *tlsfingerprint.Profile) (*http.Response, error) {
	if profile == nil || (req != nil && req.URL != nil && req.URL.Scheme == "http") {
		return s.Do(req, proxyURL, accountID, concurrency)
	}
	start := time.Now()
	response, err := s.doWithTLSWithoutCallRecord(req, proxyURL, accountID, concurrency, profile)
	if req != nil {
		status := 0
		if response != nil {
			status = response.StatusCode
		}
		workbench.RecordAttempt(req.Context(), accountID, status, err != nil, time.Since(start))
	}
	return response, err
}
