package com.kashi.grc.firmverification.controller;

import com.kashi.grc.firmverification.domain.AuditFirmApplication;

import java.util.*;

final class FirmVerificationViews {

    private FirmVerificationViews() {}

    static Map<String, Object> application(AuditFirmApplication a, String tenantName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("tenantId", a.getTenantId());
        m.put("tenantName", tenantName);
        m.put("status", a.getStatus());
        m.put("firmLegalName", a.getFirmLegalName());
        m.put("firmType", a.getFirmType());
        m.put("registrationBody", a.getRegistrationBody());
        m.put("registrationNumber", a.getRegistrationNumber());
        m.put("registrationValidUntil", a.getRegistrationValidUntil());
        m.put("jurisdiction", a.getJurisdiction());
        m.put("website", a.getWebsite());
        m.put("domain", a.getDomain());
        m.put("contactName", a.getContactName());
        m.put("contactEmail", a.getContactEmail());
        m.put("contactDesignation", a.getContactDesignation());
        m.put("frameworks", a.getFrameworksCsv() == null ? List.of()
                : Arrays.stream(a.getFrameworksCsv().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        m.put("partnerCount", a.getPartnerCount());
        m.put("statement", a.getStatement());
        m.put("attested", a.isAttested());
        m.put("emailSentAt", a.getEmailSentAt());
        m.put("emailSentTo", a.getEmailSentTo());
        m.put("emailExpiresAt", a.getEmailExpiresAt());
        m.put("emailConfirmedAt", a.getEmailConfirmedAt());
        m.put("emailSendCount", a.getEmailSendCount());
        m.put("linkPending", a.getEmailTokenHash() != null);
        m.put("createdBy", a.getCreatedBy());
        m.put("createdAt", a.getCreatedAt());
        m.put("submittedBy", a.getSubmittedBy());
        m.put("submittedAt", a.getSubmittedAt());
        m.put("reviewerId", a.getReviewerId());
        m.put("reviewStartedAt", a.getReviewStartedAt());
        m.put("checklistJson", a.getChecklistJson());
        m.put("infoRequest", a.getInfoRequest());
        m.put("decidedBy", a.getDecidedBy());
        m.put("decidedAt", a.getDecidedAt());
        m.put("decisionNote", a.getDecisionNote());
        m.put("verifiedUntil", a.getVerifiedUntil());
        m.put("revokedAt", a.getRevokedAt());
        m.put("revokeReason", a.getRevokeReason());
        m.put("previousApplicationId", a.getPreviousApplicationId());
        return m;
    }
}
