package com.kashi.grc.enquiry.repository;

import com.kashi.grc.enquiry.domain.Enquiry;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface EnquiryRepositoryCustom {

    /** Filters are optional; null or empty means "any". Newest first. */
    record Filter(Collection<String> types, Collection<String> statuses, String q,
                  Long assignedTo, LocalDateTime from, LocalDateTime to) {}

    List<Enquiry> search(Filter filter, int page, int size);

    long count(Filter filter);

    /** [groupValue, count] for one column ("status" or "enquiryType"), all time or since {@code since}. */
    Map<String, Long> countBy(String attribute, LocalDateTime since);

    /** Enquiries since {@code since}, grouped by where they came from: "Audit-firm referral", the utm_source, or "Direct / organic". */
    Map<String, Long> countBySource(LocalDateTime since);

    /** [createdAt, firstResponseAt] pairs answered since {@code since} — two columns, not whole rows. */
    List<LocalDateTime[]> responseTimes(LocalDateTime since);
}
