package com.yiqi.aitoolbench.admin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationHierarchyTest {

    private final Map<String, String> parents = Map.of(
            "root", "",
            "design", "root",
            "visual", "design");

    @Test
    void acceptsRootOrUnrelatedParent() {
        assertThatCode(() -> OrganizationHierarchy.validateParent("visual", "root", parents))
                .doesNotThrowAnyException();
        assertThatCode(() -> OrganizationHierarchy.validateParent("root", null, parents))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSelfAndDescendantAsParent() {
        assertThatThrownBy(() -> OrganizationHierarchy.validateParent("design", "design", parents))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自己");

        assertThatThrownBy(() -> OrganizationHierarchy.validateParent("design", "visual", parents))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("下级部门");
    }

    @Test
    void rejectsMissingParent() {
        assertThatThrownBy(() -> OrganizationHierarchy.validateParent("design", "missing", parents))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上级部门不存在");
    }

    @Test
    void rejectsBrokenExistingParentChain() {
        assertThatThrownBy(() -> OrganizationHierarchy.validateParent(
                "root", "visual", Map.of("root", "", "visual", "missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("父链引用了不存在的部门");
    }
}
