package com.kashi.grc.firmverification.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.evidence.spi.EvidenceTargetAccessPolicy;
import com.kashi.grc.firmverification.domain.AuditFirmApplication;
import com.kashi.grc.firmverification.repository.AuditFirmApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who may upload to and read the evidence on an audit-firm application:
 * platform users (SIDE_SYSTEM) only. The firm emails its documents; a platform
 * user uploads them here. Attaching is allowed only while the application is
 * being prepared (DRAFT or INFO_REQUESTED), so evidence cannot change under a
 * reviewer.
 *
 * Plugs into the shared document endpoints through EvidenceTargetAccessPolicy,
 * so /v1/documents/request-upload and /confirm work for this entity type with
 * no change to the document module.
 */
@Component
@RequiredArgsConstructor
public class FirmVerificationDocumentPolicy implements EvidenceTargetAccessPolicy {

    private final AuditFirmApplicationRepository repository;

    @Override
    public boolean supports(String entityType) {
        return AuditFirmVerificationService.ENTITY_TYPE.equals(entityType);
    }

    @Override
    public void requireReadable(String entityType, Long entityId) {
        repository.findById(entityId).orElseThrow(this::denied);
        if (!has("SIDE_SYSTEM")) throw denied();
    }

    @Override
    public void requireCanAttach(String entityType, Long entityId, Long userId) {
        AuditFirmApplication a = repository.findById(entityId).orElseThrow(this::denied);
        if (!has("SIDE_SYSTEM")) throw denied();
        if (!AuditFirmVerificationService.EDITABLE.contains(a.getStatus())) {
            throw new BusinessException("APPLICATION_LOCKED",
                    "Evidence can only be changed while the application is a draft or more information was requested.",
                    HttpStatus.CONFLICT);
        }
    }

    @Override
    public void requireCanReview(String entityType, Long entityId, Long userId) {
        throw denied();
    }

    static boolean has(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        for (GrantedAuthority g : auth.getAuthorities()) {
            if (authority.equals(g.getAuthority())) return true;
        }
        return false;
    }

    private BusinessException denied() {
        return new BusinessException("FIRM_EVIDENCE_DENIED",
                "Only platform users can use files on an audit-firm application", HttpStatus.FORBIDDEN);
    }
}
