package com.yiqi.aitoolbench.admin;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int page,
        int pageSize,
        long total,
        int totalPages
) {
    private static final int MAX_PAGE_SIZE = 100;

    public static <T> PageResponse<T> of(List<T> source, int requestedPage, int requestedPageSize) {
        List<T> items = source == null ? List.of() : source;
        int page = normalizePage(requestedPage);
        int pageSize = normalizePageSize(requestedPageSize);
        int total = items.size();
        int totalPages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        long offset = (long) (page - 1) * pageSize;
        List<T> pageItems = offset >= total
                ? List.of()
                : List.copyOf(items.subList((int) offset, Math.min(total, (int) offset + pageSize)));
        return new PageResponse<>(pageItems, page, pageSize, total, totalPages);
    }

    public static <T> PageResponse<T> page(List<T> items, int requestedPage, int requestedPageSize, long total) {
        int page = normalizePage(requestedPage);
        int pageSize = normalizePageSize(requestedPageSize);
        int totalPages = total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        return new PageResponse<>(items == null ? List.of() : List.copyOf(items), page, pageSize, total, totalPages);
    }

    public static int normalizePage(int page) {
        return Math.max(1, page);
    }

    public static int normalizePageSize(int pageSize) {
        return Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
    }
}
