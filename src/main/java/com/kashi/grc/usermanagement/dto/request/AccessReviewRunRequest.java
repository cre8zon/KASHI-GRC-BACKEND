package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.Valid;
import lombok.Data;

@Data
public class AccessReviewRunRequest {
    public String reviewType;
    public boolean includeInactiveUsers;
    public boolean includeVendorUsers;
    @Valid
    public ReviewPeriod reviewPeriod;

    @Data
    public static class ReviewPeriod {
        public String startDate;
        public String endDate;
    }
}
