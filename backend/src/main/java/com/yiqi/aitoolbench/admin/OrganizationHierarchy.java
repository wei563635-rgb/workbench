package com.yiqi.aitoolbench.admin;

import java.util.HashSet;
import java.util.Map;

public final class OrganizationHierarchy {
    private OrganizationHierarchy() {
    }

    public static void validateParent(String departmentId, String parentId, Map<String, String> parentById) {
        if (parentId == null || parentId.isBlank()) {
            return;
        }
        if (departmentId.equals(parentId)) {
            throw new IllegalArgumentException("上级部门不能选择自己");
        }
        if (!parentById.containsKey(parentId)) {
            throw new IllegalArgumentException("上级部门不存在: " + parentId);
        }
        var visited = new HashSet<String>();
        var cursor = parentId;
        while (cursor != null && !cursor.isBlank() && visited.add(cursor)) {
            if (!parentById.containsKey(cursor)) {
                throw new IllegalArgumentException("组织父链引用了不存在的部门: " + cursor);
            }
            if (departmentId.equals(cursor)) {
                throw new IllegalArgumentException("上级部门不能选择当前部门的下级部门");
            }
            cursor = parentById.get(cursor);
        }
        if (cursor != null && !cursor.isBlank()) {
            throw new IllegalArgumentException("组织层级存在循环，请先修复现有数据");
        }
    }
}
