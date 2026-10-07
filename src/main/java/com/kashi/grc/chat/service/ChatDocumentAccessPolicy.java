package com.kashi.grc.chat.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.evidence.spi.EvidenceTargetAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Files sent in chat are documents linked to the conversation
 * (entity type CHAT_CONVERSATION). The shared document endpoints ask here:
 *
 *   read     a member of the conversation, or anyone who can read a public channel
 *   attach   a member of a conversation that is not archived
 *   review   never — there is nothing to accept or reject in chat
 *
 * Unknown or foreign conversations get the same answer as a refusal.
 */
@Component
@RequiredArgsConstructor
public class ChatDocumentAccessPolicy implements EvidenceTargetAccessPolicy {

    private final ChatService chatService;

    @Override
    public boolean supports(String entityType) {
        return ChatService.DOC_ENTITY.equals(entityType);
    }

    @Override
    public void requireReadable(String entityType, Long entityId) {
        if (!chatService.canReadFiles(entityId)) throw denied();
    }

    @Override
    public void requireCanAttach(String entityType, Long entityId, Long userId) {
        if (!chatService.canAttachFiles(entityId, userId)) throw denied();
    }

    @Override
    public void requireCanReview(String entityType, Long entityId, Long userId) {
        throw denied();
    }

    private static BusinessException denied() {
        return new BusinessException("CHAT_FILE_DENIED", "You cannot use files in this conversation", HttpStatus.FORBIDDEN);
    }
}
