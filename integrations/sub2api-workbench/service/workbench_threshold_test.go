package service

import (
	"testing"
	"time"
)

func TestWorkbenchWindowThreshold(t *testing.T) {
	now := time.Now()
	reset := now.Add(time.Hour).Format(time.RFC3339)
	account := &Account{Platform: PlatformOpenAI, Extra: map[string]any{"workbench_threshold_5h": 80, "workbench_threshold_7d": 90, "codex_5h_used_percent": 85, "codex_7d_used_percent": 70, "codex_5h_reset_at": reset, "codex_7d_reset_at": reset, "codex_usage_updated_at": now.Format(time.RFC3339)}}
	result := EvaluateAccountSchedulingThreshold(account, nil, now)
	if !result.ShouldPause || result.Window != "5h" || result.ThresholdPercent != 80 {
		t.Fatalf("independent window rule not applied: %+v", result)
	}
	if EvaluateAccountSchedulingThreshold(account, nil, now.Add(2*time.Hour)).ShouldPause {
		t.Fatal("expired window did not restore")
	}
	account.Extra["codex_5h_used_percent"] = 10
	account.Extra["codex_7d_used_percent"] = 95
	if result = EvaluateAccountSchedulingThreshold(account, nil, now); !result.ShouldPause || result.Window != "7d" {
		t.Fatal("7d threshold ignored")
	}
	account.Platform = PlatformGemini
	if EvaluateAccountSchedulingThreshold(account, nil, now).ShouldPause {
		t.Fatal("fabricated Google window affected scheduling")
	}
}
