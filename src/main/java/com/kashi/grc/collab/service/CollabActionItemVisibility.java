package com.kashi.grc.collab.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.spi.ActionItemEntityVisibility;
import com.kashi.grc.collab.domain.CollabMeeting;
import com.kashi.grc.collab.domain.CollabProgramme;
import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.collab.repository.CollabMeetingRepository;
import com.kashi.grc.collab.repository.CollabProgrammeRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceRepository;
import com.kashi.grc.collab.service.CollabAccessService.Caller;
import com.kashi.grc.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Who sees the action items on Collaboration records — requests (on a
 * workspace, parent programme) and meeting follow-ups (on a meeting).
 *
 *   read      WORKSPACE  the caller can see the workspace
 *             PROGRAMME  the caller can see the workspace and the programme
 *             MEETING    the caller can see the meeting
 *   see all   WORKSPACE / PROGRAMME  a workspace manager
 *             MEETING                whoever runs the meeting
 *   otherwise only the items the caller is party to (filtered by the
 *             action-item controller).
 */
@Component
@RequiredArgsConstructor
public class CollabActionItemVisibility implements ActionItemEntityVisibility {

    private final CollabAccessService       access;
    private final CollabWorkspaceService    workspaceService;
    private final CollabMeetingService      meetingService;
    private final CollabWorkspaceRepository workspaceRepository;
    private final CollabProgrammeRepository programmeRepository;
    private final CollabMeetingRepository   meetingRepository;

    @Override
    public boolean supports(ActionItem.EntityType entityType) {
        return entityType == ActionItem.EntityType.COLLAB_WORKSPACE
                || entityType == ActionItem.EntityType.COLLAB_PROGRAMME
                || entityType == ActionItem.EntityType.COLLAB_MEETING;
    }

    @Override
    public void requireReadable(ActionItem.EntityType entityType, Long entityId) {
        Caller c = access.caller();
        boolean ok = switch (entityType) {
            case COLLAB_WORKSPACE -> workspace(c, entityId) != null;
            case COLLAB_PROGRAMME -> programmeVisible(c, entityId);
            case COLLAB_MEETING   -> meeting(c, entityId) != null;
            default -> true;
        };
        if (!ok) {
            throw new BusinessException("COLLAB_NOT_ACCESSIBLE",
                    "You do not have access to this record.", HttpStatus.FORBIDDEN);
        }
    }

    @Override
    public boolean seesAll(ActionItem.EntityType entityType, Long entityId, Long userId) {
        Caller c = access.caller();
        if (!c.userId().equals(userId)) return false;
        return switch (entityType) {
            case COLLAB_WORKSPACE -> {
                CollabWorkspace ws = workspace(c, entityId);
                yield ws != null && access.canManage(c, ws);
            }
            case COLLAB_PROGRAMME -> programmeRepository.findById(entityId)
                    .filter(p -> !p.isDeleted())
                    .map(p -> workspace(c, p.getWorkspaceId()))
                    .map(ws -> access.canManage(c, ws))
                    .orElse(false);
            case COLLAB_MEETING -> {
                CollabMeeting m = meeting(c, entityId);
                yield m != null && meetingService.canRun(c, m);
            }
            default -> false;
        };
    }

    private CollabWorkspace workspace(Caller c, Long id) {
        if (id == null) return null;
        return workspaceRepository.findByIdAndTenantIdAndIsDeletedFalse(id, c.tenantId())
                .filter(ws -> access.canSee(c, ws)).orElse(null);
    }

    private boolean programmeVisible(Caller c, Long programmeId) {
        CollabProgramme p = programmeRepository.findById(programmeId).orElse(null);
        if (p == null || p.isDeleted()) return false;
        CollabWorkspace ws = workspace(c, p.getWorkspaceId());
        return ws != null && workspaceService.visibleProgrammes(c, ws).stream()
                .anyMatch(x -> x.getId().equals(programmeId));
    }

    private CollabMeeting meeting(Caller c, Long id) {
        return meetingRepository.findByIdAndTenantIdAndIsDeletedFalse(id, c.tenantId())
                .filter(m -> meetingService.canSee(c, m)).orElse(null);
    }
}
