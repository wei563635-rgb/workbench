package com.yiqi.aitoolbench.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.yiqi.aitoolbench.security.DataScope;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectAccessPolicyTest {
    @Test
    void ordinaryDepartmentPeerDoesNotAutomaticallySeeProject() {
        assertThat(ProjectAccessPolicy.canView(
                DataScope.PROJECT, "peer", List.of("dept-a"), "dept-a",
                "owner", "owner", List.of("owner", "member"))).isFalse();
    }

    @Test
    void departmentScopeSeesExactDepartmentButNotOtherDepartment() {
        assertThat(ProjectAccessPolicy.canView(
                DataScope.DEPARTMENT, "leader", List.of("dept-a"), "dept-a",
                "owner", "owner", List.of("owner"))).isTrue();
        assertThat(ProjectAccessPolicy.canView(
                DataScope.DEPARTMENT, "leader", List.of("dept-a"), "dept-b",
                "owner", "owner", List.of("owner"))).isFalse();
    }

    @Test
    void crossDepartmentMemberCanViewAndRunActiveProject() {
        assertThat(ProjectAccessPolicy.canView(
                DataScope.PROJECT, "member", List.of("dept-b"), "dept-a",
                "owner", "owner", List.of("owner", "member"))).isTrue();
        assertThat(ProjectAccessPolicy.canRun(
                "member", "进行中", List.of("owner", "member"))).isTrue();
    }

    @Test
    void onlyOwnerOrAllScopeWithWritePermissionCanManage() {
        assertThat(ProjectAccessPolicy.canManage(true, DataScope.PROJECT, "owner", "owner")).isTrue();
        assertThat(ProjectAccessPolicy.canManage(true, DataScope.DEPARTMENT, "leader", "owner")).isFalse();
        assertThat(ProjectAccessPolicy.canManage(true, DataScope.ALL, "admin", "owner")).isTrue();
        assertThat(ProjectAccessPolicy.canManage(false, DataScope.ALL, "auditor", "owner")).isFalse();
    }

    @Test
    void completedOrArchivedProjectCannotStartNewRun() {
        assertThat(ProjectAccessPolicy.canRun("member", "已完成", List.of("member"))).isFalse();
        assertThat(ProjectAccessPolicy.canRun("member", "归档", List.of("member"))).isFalse();
    }
}
