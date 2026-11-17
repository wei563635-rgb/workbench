package com.yiqi.aitoolbench.project;

import com.yiqi.aitoolbench.security.DataScope;
import java.util.Collection;

/**
 * 项目权限只依赖稳定 ID。姓名仅用于展示，不参与任何授权判断。
 *
 * <p>所有判断均为 O(d + m)：d 为用户所属部门数，m 为项目成员数。</p>
 */
public final class ProjectAccessPolicy {
    private ProjectAccessPolicy() {}

    public static boolean canView(
            DataScope scope,
            String userId,
            Collection<String> userDepartmentIds,
            String projectDepartmentId,
            String ownerUserId,
            String createdBy,
            Collection<String> memberUserIds) {
        if (scope == DataScope.ALL) {
            return true;
        }
        if (isOwnerOrCreator(userId, ownerUserId, createdBy)) {
            return true;
        }
        if (scope.priority() >= DataScope.PROJECT.priority() && contains(memberUserIds, userId)) {
            return true;
        }
        return scope.priority() >= DataScope.DEPARTMENT.priority()
                && projectDepartmentId != null
                && userDepartmentIds != null
                && userDepartmentIds.contains(projectDepartmentId);
    }

    public static boolean canManage(
            boolean hasProjectWrite,
            DataScope scope,
            String userId,
            String ownerUserId) {
        return hasProjectWrite
                && (scope == DataScope.ALL || equalsNonBlank(userId, ownerUserId));
    }

    public static boolean canRun(
            String userId,
            String projectStatus,
            Collection<String> memberUserIds) {
        return "进行中".equals(projectStatus) && contains(memberUserIds, userId);
    }

    private static boolean isOwnerOrCreator(String userId, String ownerUserId, String createdBy) {
        return equalsNonBlank(userId, ownerUserId) || equalsNonBlank(userId, createdBy);
    }

    private static boolean contains(Collection<String> values, String value) {
        return value != null && values != null && values.contains(value);
    }

    private static boolean equalsNonBlank(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }
}
