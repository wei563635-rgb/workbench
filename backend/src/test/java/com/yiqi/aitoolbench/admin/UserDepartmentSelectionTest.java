package com.yiqi.aitoolbench.admin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDepartmentSelectionTest {

    @Test
    void placesPrimaryDepartmentFirstAndRemovesDuplicates() {
        var result = UserDepartmentSelection.normalize(
                List.of("org_design", "org_market", "org_design"),
                "org_market",
                Set.of("org_market", "org_design"));

        assertThat(result).containsExactly("org_market", "org_design");
    }

    @Test
    void requiresAtLeastOneDepartmentAndPrimaryDepartment() {
        assertThatThrownBy(() -> UserDepartmentSelection.normalize(List.of(), null, Set.of("org_market")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少选择一个部门");
    }

    @Test
    void rejectsPrimaryOutsideSelectionAndDisabledDepartment() {
        assertThatThrownBy(() -> UserDepartmentSelection.normalize(
                List.of("org_design"), "org_market", Set.of("org_market", "org_design")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主部门必须属于已选部门");

        assertThatThrownBy(() -> UserDepartmentSelection.normalize(
                List.of("org_disabled"), "org_disabled", Set.of("org_market")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或未启用");
    }
}
