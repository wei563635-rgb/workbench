package com.yiqi.aitoolbench.admin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class UserDepartmentSelection {
    private UserDepartmentSelection() {
    }

    public static List<String> normalize(List<String> selectedIds, String primaryId, Set<String> enabledIds) {
        var selected = new LinkedHashSet<String>();
        if (selectedIds != null) {
            selectedIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(selected::add);
        }
        if (selected.isEmpty() || primaryId == null || primaryId.isBlank()) {
            throw new IllegalArgumentException("至少选择一个部门并指定主部门");
        }
        if (!selected.contains(primaryId)) {
            throw new IllegalArgumentException("主部门必须属于已选部门");
        }
        var invalid = selected.stream().filter(id -> !enabledIds.contains(id)).findFirst();
        if (invalid.isPresent()) {
            throw new IllegalArgumentException("部门不存在或未启用: " + invalid.get());
        }
        selected.remove(primaryId);
        var normalized = new java.util.ArrayList<String>();
        normalized.add(primaryId);
        normalized.addAll(selected);
        return List.copyOf(normalized);
    }
}
