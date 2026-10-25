package com.kashi.grc.issue.workflow;

/**
 * An issue moved to CLOSED through IssueService — the sign-off step's "Close"
 * button. CloseIssueAction listens and closes whatever the issue was raised
 * from (an audit finding, a vendor-assessment remediation).
 */
public record IssueClosedEvent(Long issueId, Long tenantId, Long closedBy) {
}