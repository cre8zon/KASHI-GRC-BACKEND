package com.kashi.grc.common.dto;

import lombok.Getter;
import org.springframework.data.domain.Page;
import java.util.List;

/**
 * Unified paginated response for all list endpoints.
 * Supports Spring Data Page<T> and raw Criteria API results.
 */
@Getter
public class PaginatedResponse<T> {

    private final List<T>    items;
    private final Pagination pagination;

    /** Used when mapping from Spring Data Page */
    public PaginatedResponse(Page<T> page) {
        this.items      = page.getContent();
        this.pagination = build(page.getNumber() + 1, page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.hasNext(), page.hasPrevious());
    }

    public PaginatedResponse(List<T> items, Page<?> page) {
        this.items      = items;
        this.pagination = build(page.getNumber() + 1, page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.hasNext(), page.hasPrevious());
    }

    /** Used by DbRepository / CriteriaQueryHelper — raw list + count + PageDetails */
    public PaginatedResponse(List<T> items, long totalCount, PageDetails pd) {
        this.items  = items;
        int take    = (pd.getTake() != null && pd.getTake() > 0) ? pd.getTake() : 10;
        long skip   = pd.getSkip() != null ? pd.getSkip() : 0L;
        int curPage = take > 0 ? (int)(skip / take) + 1 : 1;
        int pages   = take > 0 ? (int)((totalCount + take - 1) / take) : 1;
        this.pagination = build(curPage, take, totalCount, pages,
                (long) curPage * take < totalCount, curPage > 1);
    }

    private static Pagination build(int cur, int size, long total, int pages, boolean next, boolean prev) {
        return new Pagination(cur, size, total, pages, next, prev);
    }

    @Getter
    public static class Pagination {
        private final int     currentPage;
        private final int     pageSize;
        private final long    totalItems;
        private final int     totalPages;
        private final boolean hasNext;
        private final boolean hasPrevious;

        Pagination(int cur, int size, long total, int pages, boolean next, boolean prev) {
            this.currentPage = cur;  this.pageSize  = size;
            this.totalItems  = total; this.totalPages = pages;
            this.hasNext     = next;  this.hasPrevious = prev;
        }
    }
}
