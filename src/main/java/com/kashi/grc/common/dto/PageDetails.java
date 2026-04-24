package com.kashi.grc.common.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;

/**
 * Pagination, filter, search and sort parameters.
 * All list fields are initialised to empty lists — callers never need null-checks.
 *
 * Query-string format (passed as URL params):
 *   skip=0 &amp; take=20
 *   search=name=cloud;email=@acme    (LIKE match, OR-combined across fields)
 *   filterBy=status=ACTIVE           (exact match, AND-combined)
 *   sortBy=name                      (field name)
 *   sortDirection=asc                (asc | desc)
 */
@Getter
@Setter
public class PageDetails {
    private Long  skip          = 0L;
    private Integer take        = 10;
    private ArrayList<NameValue> filterBy      = new ArrayList<>();
    private ArrayList<NameValue> search        = new ArrayList<>();
    private ArrayList<NameValue> sortBy        = new ArrayList<>();
    private ArrayList<NameValue> sortDirection = new ArrayList<>();
}
