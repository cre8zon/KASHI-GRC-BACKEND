package com.kashi.grc.common.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * Deserialisation constructor — required by Redis, not by the API.
     *
     * Every cached list was failing to come back out of Redis:
     *   SerializationException: Cannot construct instance of PaginatedResponse
     *   (no Creators, like default constructor, exist)
     *
     * ResilientRedisCache swallows that as a miss, so nothing broke visibly —
     * auditTemplateList and workflowBlueprintList simply hit the database on
     * every single request while appearing to be cached. Writes succeeded, reads
     * never did.
     *
     * The fields are final and the class has only Page/PageDetails constructors,
     * which Jackson cannot use. @JsonCreator gives it a way in without dropping
     * final or adding a no-arg constructor that would leave the object in a state
     * the other constructors guarantee against.
     */
    @JsonCreator
    public PaginatedResponse(@JsonProperty("items")      List<T>    items,
                             @JsonProperty("pagination") Pagination pagination) {
        this.items      = items;
        this.pagination = pagination;
    }

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

        /**
         * Same reason as the outer @JsonCreator: final fields and no no-arg
         * constructor. Package-private is kept — Jackson does not need it public,
         * and widening it would invite construction from outside build().
         */
        @JsonCreator
        Pagination(@JsonProperty("currentPage") int cur,
                   @JsonProperty("pageSize")    int size,
                   @JsonProperty("totalItems")  long total,
                   @JsonProperty("totalPages")  int pages,
                   @JsonProperty("hasNext")     boolean next,
                   @JsonProperty("hasPrevious") boolean prev) {
            this.currentPage = cur;  this.pageSize  = size;
            this.totalItems  = total; this.totalPages = pages;
            this.hasNext     = next;  this.hasPrevious = prev;
        }
    }
}