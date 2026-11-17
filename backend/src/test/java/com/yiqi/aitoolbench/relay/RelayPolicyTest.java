package com.yiqi.aitoolbench.relay;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RelayPolicyTest {
    @Test void independentEmployeeIgnoresExhaustedPoolButNotOwnLimit() {
        assertThat(RelayPolicy.evaluate(true,200,30,100L,100).allowed()).isTrue();
        assertThat(RelayPolicy.evaluate(true,200,200,100L,0).reason()).isEqualTo("PERSONAL_QUOTA_EXHAUSTED");
        assertThat(RelayPolicy.evaluate(false,200,30,100L,100).reason()).isEqualTo("DEPARTMENT_QUOTA_EXHAUSTED");
    }
    @Test void quotaIsInclusiveAtBoundaryAndZeroMeansBlocked() {
        assertThat(RelayPolicy.evaluate(false,50,49,100L,99).allowed()).isTrue();
        assertThat(RelayPolicy.evaluate(false,50,50,100L,99).allowed()).isFalse();
        assertThat(RelayPolicy.evaluate(true,0,0,null,0).allowed()).isFalse();
    }
    @Test void chineseCalendarMonthDoesNotFollowServerUtc() {
        var at=Instant.parse("2026-09-30T16:00:00Z");
        assertThat(RelayPolicy.month(at)).isEqualTo("2026-10");
        assertThat(RelayPolicy.monthStart(at)).isEqualTo(at);
        assertThat(RelayPolicy.nextMonth(at)).isEqualTo(Instant.parse("2026-10-31T16:00:00Z"));
    }
    @Test void resolvesPrimaryDepartmentRootAndRejectsCycles() {
        var root=new HashMap<String,Object>();root.put("parentId",null);
        var tree=new HashMap<String,Map<String,Object>>();tree.put("a",root);tree.put("b",Map.of("parentId","a"));tree.put("c",Map.of("parentId","b"));
        assertThat(RelayService.rootDepartment("c",tree)).isEqualTo("a");
        tree.put("a",Map.of("parentId","c"));assertThat(RelayService.rootDepartment("c",tree)).isNull();
    }
}
