package com.yiqi.aitoolbench;

import java.util.Collection;

/**
 * 品牌 HTML 查看权限按员工或直接归属部门命中，不继承父子部门。
 */
public final class BrandPageAccessPolicy {
    private BrandPageAccessPolicy() {
    }

    public static boolean canAccess(boolean unrestricted, String userId, Collection<String> userDepartmentIds,
                                    String createdBy, Collection<String> viewerDepartmentIds,
                                    Collection<String> viewerUserIds) {
        if (unrestricted) {
            return true;
        }
        if (userId != null && (userId.equals(createdBy)
                || viewerUserIds != null && viewerUserIds.contains(userId))) {
            return true;
        }
        return userDepartmentIds != null
                && viewerDepartmentIds != null
                && userDepartmentIds.stream().anyMatch(viewerDepartmentIds::contains);
    }
}
