package service

import "time"

// Window-specific thresholds use native trusted quota snapshots and reset
// checks. No guessed capacity or Google pseudo-window enters scheduling.
func workbenchWindowThreshold(account *Account, now time.Time) (AccountSchedulingThresholdDecision, bool) {
	decision := AccountSchedulingThresholdDecision{Platform: PlatformOpenAI}
	if account == nil || account.Platform != PlatformOpenAI {
		return decision, false
	}
	_, five := account.Extra["workbench_threshold_5h"]
	_, seven := account.Extra["workbench_threshold_7d"]
	if !five && !seven {
		return decision, false
	}
	for _, candidate := range openAIThresholdCandidates(account, now) {
		if candidate == nil {
			continue
		}
		threshold, ok := parseAccountSchedulingThresholdValue(account.Extra["workbench_threshold_"+candidate.window])
		if !ok || !candidateMatchesThreshold(candidate, threshold, now) {
			continue
		}
		if decision.Until == nil || candidate.until.After(*decision.Until) {
			decision.ShouldPause = true
			decision.Window = candidate.window
			decision.ThresholdPercent = threshold
			decision.UsedPercent = candidate.usedPercent
			decision.Until = candidate.until
		}
	}
	return decision, true
}
