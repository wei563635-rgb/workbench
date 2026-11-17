package com.yiqi.aitoolbench.relay;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

/** 中转额度的唯一口径；与其他供应商直连调用隔离。计算为 O(1)。 */
public final class RelayPolicy {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private RelayPolicy() {}

    public record Department(String id, long personalLimit, long poolLimit, int concurrency) {
        public Department { validate(personalLimit, concurrency); if (poolLimit < 0) throw new IllegalArgumentException("部门总额度不能为负数"); }
    }
    public record Employee(String id, String mode, long personalLimit, int concurrency, Long relayUserId) {
        public Employee {
            if (!"inherited".equals(mode) && !"independent".equals(mode)) throw new IllegalArgumentException("限额类型无效");
            validate(personalLimit, concurrency);
            if (relayUserId != null && relayUserId <= 0) throw new IllegalArgumentException("中转用户 ID 无效");
        }
    }
    public record Decision(boolean allowed, String reason, long personalRemaining, Long poolRemaining) {}

    public static Decision evaluate(boolean independent, long limit, long used, Long poolLimit, long poolUsed) {
        long remaining = Math.max(0, limit - used);
        Long poolRemaining = independent || poolLimit == null ? null : Math.max(0, poolLimit - poolUsed);
        String reason = used >= limit ? "PERSONAL_QUOTA_EXHAUSTED"
                : !independent && poolLimit != null && poolUsed >= poolLimit ? "DEPARTMENT_QUOTA_EXHAUSTED" : "OK";
        return new Decision("OK".equals(reason), reason, remaining, poolRemaining);
    }
    public static String month(Instant instant) { return YearMonth.from(instant.atZone(ZONE)).toString(); }
    public static Instant monthStart(Instant instant) { return YearMonth.from(instant.atZone(ZONE)).atDay(1).atStartOfDay(ZONE).toInstant(); }
    public static Instant nextMonth(Instant instant) { return YearMonth.from(instant.atZone(ZONE)).plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant(); }
    private static void validate(long limit, int concurrency) {
        if (limit < 0 || limit > 9_000_000_000_000L) throw new IllegalArgumentException("Token 限额超出允许范围");
        if (concurrency < 1 || concurrency > 100) throw new IllegalArgumentException("并发必须为 1 至 100");
    }
}
