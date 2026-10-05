package com.kashi.grc.collab.service;

import com.kashi.grc.collab.domain.CollabMeeting;
import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.collab.repository.CollabMeetingAttendeeRepository;
import com.kashi.grc.collab.repository.CollabMeetingRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceMemberRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceRepository;
import com.kashi.grc.collab.service.CollabAccessService.Caller;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.evidence.spi.EvidenceTargetAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Documents on Collaboration records — the shared document endpoints asked
 * through the same rules as the collab screens.
 *
 *   COLLAB_WORKSPACE  read    can see the workspace
 *                     attach  a member of an ACTIVE workspace, or a manager
 *   COLLAB_MEETING    read    can see the meeting
 *                     attach  runs the meeting, or attends it
 *   COLLAB_REQUEST    read    can see the request (workspace + programme)
 *                     attach  its assignee, whoever raised it, or a manager
 *   review (accept / reject a link) — a workspace manager; for a meeting,
 *   whoever runs it.
 *
 * Unknown ids are refused with the same answer as a denial.
 */
@Component
@RequiredArgsConstructor
public class CollabEvidenceTargetAccessPolicy implements EvidenceTargetAccessPolicy {

    private static final String WORKSPACE = "COLLAB_WORKSPACE";
    private static final String MEETING   = "COLLAB_MEETING";
    private static final String REQUEST   = "COLLAB_REQUEST";

    private final CollabAccessService             access;
    private final CollabMeetingService            meetingService;
    private final CollabRequestService            requestService;
    private final CollabWorkspaceRepository       workspaceRepository;
    private final CollabWorkspaceMemberRepository memberRepository;
    private final CollabMeetingRepository         meetingRepository;
    private final CollabMeetingAttendeeRepository attendeeRepository;

    @Override
    public boolean supports(String entityType) {
        return WORKSPACE.equals(entityType) || MEETING.equals(entityType) || REQUEST.equals(entityType);
    }

    @Override
    public void requireReadable(String entityType, Long entityId) {
        Caller c = access.caller();
        boolean ok = switch (entityType) {
            case WORKSPACE -> workspace(c, entityId) != null;
            case MEETING   -> meeting(c, entityId) != null;
            case REQUEST   -> requestService.canSeeRequest(c, entityId);
            default -> false;
        };
        if (!ok) throw denied();
    }

    @Override
    public void requireCanAttach(String entityType, Long entityId, Long userId) {
        Caller c = access.caller();
        if (!c.userId().equals(userId)) throw denied();
        boolean ok = switch (entityType) {
            case WORKSPACE -> {
                CollabWorkspace ws = workspace(c, entityId);
                yield ws != null && CollabWorkspace.ACTIVE.equals(ws.getStatus())
                        && (memberRepository.findByWorkspaceIdAndUserId(ws.getId(), c.userId()).isPresent()
                            || access.canManage(c, ws));
            }
            case MEETING -> {
                CollabMeeting m = meeting(c, entityId);
                yield m != null && (meetingService.canRun(c, m)
                        || attendeeRepository.findByMeetingIdAndUserId(m.getId(), c.userId()).isPresent());
            }
            case REQUEST -> requestService.canWorkRequest(c, entityId);
            default -> false;
        };
        if (!ok) throw denied();
    }

    @Override
    public void requireCanReview(String entityType, Long entityId, Long userId) {
        Caller c = access.caller();
        if (!c.userId().equals(userId)) throw denied();
        boolean ok = switch (entityType) {
            case WORKSPACE -> {
                CollabWorkspace ws = workspace(c, entityId);
                yield ws != null && access.canManage(c, ws);
            }
            case MEETING -> {
                CollabMeeting m = meeting(c, entityId);
                yield m != null && meetingService.canRun(c, m);
            }
            case REQUEST -> {
                Long wsId = requestService.workspaceOf(entityId);
                CollabWorkspace ws = wsId == null ? null : workspace(c, wsId);
                yield ws != null && requestService.canSeeRequest(c, entityId) && access.canManage(c, ws);
            }
            default -> false;
        };
        if (!ok) throw denied();
    }

    private CollabWorkspace workspace(Caller c, Long id) {
        if (id == null) return null;
        return workspaceRepository.findByIdAndTenantIdAndIsDeletedFalse(id, c.tenantId())
                .filter(ws -> access.canSee(c, ws)).orElse(null);
    }

    private CollabMeeting meeting(Caller c, Long id) {
        return meetingRepository.findByIdAndTenantIdAndIsDeletedFalse(id, c.tenantId())
                .filter(m -> meetingService.canSee(c, m)).orElse(null);
    }

    private static BusinessException denied() {
        return new BusinessException("COLLAB_NOT_ACCESSIBLE",
                "You do not have access to this record.", HttpStatus.FORBIDDEN);
    }
}
