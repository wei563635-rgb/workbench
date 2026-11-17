package service

import (
	"context"
	"errors"
	"os"
	"strings"
	"sync"
)

type workbenchFallbackContextKey struct{}
type workbenchFallbackState struct {
	sync.Mutex
	safeAccounts map[int64]bool
	unsafe       bool
}

// WithWorkbenchFallbackState shares failure evidence across native account retries.
func WithWorkbenchFallbackState(ctx context.Context) context.Context {
	return context.WithValue(ctx, workbenchFallbackContextKey{}, &workbenchFallbackState{safeAccounts: make(map[int64]bool)})
}

// RecordWorkbenchFallbackFailure never authorizes cross-provider retry after an
// uncertain transport/5xx failure or a content/parameter rejection.
func RecordWorkbenchFallbackFailure(ctx context.Context, accountID int64, failure *UpstreamFailoverError) {
	state, _ := ctx.Value(workbenchFallbackContextKey{}).(*workbenchFallbackState)
	if state == nil {
		return
	}
	state.Lock()
	defer state.Unlock()
	safe := failure != nil && (failure.StatusCode == 401 || failure.StatusCode == 429 ||
		(failure.StatusCode == 403 && isOpenAIUpstreamAccessStateError("", failure.ResponseBody)))
	state.safeAccounts[accountID] = safe
	state.unsafe = state.unsafe || !safe
}

func isWorkbenchEvolinkAccount(account *Account) bool {
	return account != nil && account.Platform == PlatformOpenAI && account.Type == AccountTypeAPIKey &&
		account.Extra["workbench_evolink_fallback"] == true
}

func workbenchFallbackEnabled() bool {
	return strings.EqualFold(strings.TrimSpace(os.Getenv("WORKBENCH_EVOLINK_FALLBACK_ENABLED")), "true")
}

func workbenchFallbackModel(model string) bool {
	return model == "gpt-6-luna" || model == "gpt-image-2.5-flare"
}

func workbenchFallbackFailuresSafe(ctx context.Context, excluded map[int64]struct{}) bool {
	state, _ := ctx.Value(workbenchFallbackContextKey{}).(*workbenchFallbackState)
	if state == nil {
		return len(excluded) == 0
	}
	state.Lock()
	defer state.Unlock()
	if state.unsafe {
		return false
	}
	for id := range excluded {
		if !state.safeAccounts[id] {
			return false
		}
	}
	return true
}

// O(N) over the cached group account list. Fallback accounts are excluded from
// the primary pass even while disabled; concurrency pressure never activates them.
func (s *OpenAIGatewayService) selectAccountWithScheduler(
	ctx context.Context, groupID *int64, previousResponseID string, sessionHash string,
	requestedModel string, excludedIDs map[int64]struct{}, requiredTransport OpenAIUpstreamTransport,
	requiredCapability OpenAIEndpointCapability, requiredImageCapability OpenAIImagesCapability,
	requireCompact bool, platform string, previousResponseCanMove bool, useUpstreamTokenCost bool,
) (*AccountSelectionResult, OpenAIAccountScheduleDecision, error) {
	selectNative := func(excluded map[int64]struct{}, sticky string) (*AccountSelectionResult, OpenAIAccountScheduleDecision, error) {
		return s.selectAccountWithSchedulerPrimary(ctx, groupID, previousResponseID, sticky, requestedModel, excluded,
			requiredTransport, requiredCapability, requiredImageCapability, requireCompact, platform, previousResponseCanMove, useUpstreamTokenCost)
	}
	if NormalizeOpenAICompatiblePlatform(platform) != PlatformOpenAI {
		return selectNative(excludedIDs, sessionHash)
	}
	accounts, err := s.listSchedulableAccounts(ctx, groupID, platform)
	if err != nil {
		return nil, OpenAIAccountScheduleDecision{}, err
	}
	primaryExcluded := cloneExcludedAccountIDs(excludedIDs)
	if primaryExcluded == nil {
		primaryExcluded = make(map[int64]struct{})
	}
	hasFallback := false
	for i := range accounts {
		if isWorkbenchEvolinkAccount(&accounts[i]) {
			primaryExcluded[accounts[i].ID] = struct{}{}
			hasFallback = true
		}
	}
	if !hasFallback {
		return selectNative(excludedIDs, sessionHash)
	}
	selection, decision, primaryErr := selectNative(primaryExcluded, sessionHash)
	if primaryErr == nil && selection != nil && selection.Account != nil {
		return selection, decision, nil
	}
	if primaryErr != nil && !errors.Is(primaryErr, ErrNoAvailableAccounts) && !errors.Is(primaryErr, ErrNoAvailableCompactAccounts) {
		return selection, decision, primaryErr
	}
	if !workbenchFallbackEnabled() || !workbenchFallbackModel(requestedModel) || previousResponseID != "" || requireCompact ||
		(requiredTransport != OpenAIUpstreamTransportAny && requiredTransport != OpenAIUpstreamTransportHTTPSSE) || requiredImageCapability == OpenAIImagesCapabilityNative ||
		!workbenchFallbackFailuresSafe(ctx, excludedIDs) {
		return selection, decision, primaryErr
	}

	checkCtx := s.withOpenAIQuotaAutoPauseContext(ctx)
	req := OpenAIAccountScheduleRequest{GroupID: groupID, Platform: platform, RequestedModel: requestedModel,
		RequiredTransport: requiredTransport, RequiredCapability: requiredCapability, RequiredImageCapability: requiredImageCapability,
		RequirePrivacySet: s.openAIGroupRequiresPrivacySet(checkCtx, groupID)}
	checker := &defaultOpenAIAccountScheduler{service: s}
	fallbackExcluded := cloneExcludedAccountIDs(excludedIDs)
	if fallbackExcluded == nil {
		fallbackExcluded = make(map[int64]struct{})
	}
	for i := range accounts {
		account := &accounts[i]
		if isWorkbenchEvolinkAccount(account) {
			continue
		}
		// Eligible but busy primary accounts must queue/fail normally, never spill
		// paid traffic to Evolink. Excluded primaries require explicit safe evidence.
		_, excluded := excludedIDs[account.ID]
		if !excluded && account.IsSchedulable() && checker.isAccountRequestCompatible(checkCtx, account, req) &&
			checker.isAccountTransportCompatible(account, requiredTransport) {
			return selection, decision, primaryErr
		}
		fallbackExcluded[account.ID] = struct{}{}
	}
	// Do not retain a sticky fallback binding when the primary pool recovers.
	return selectNative(fallbackExcluded, "")
}
