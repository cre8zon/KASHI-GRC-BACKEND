package com.kashi.grc.workflow.service;

import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.enums.WorkflowStatus;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Resolves "who is currently working on this entity" — the affected users
 * for entity-scoped notifications that have no single obvious recipient
 * (e.g. DOCUMENT_UPLOADED: notify everyone with an open task on the entity
 * the document was attached to).
 *
 * Definition of participant (v1, deliberately narrow):
 *   assignees of PENDING tasks on ACTIVE workflow instances
 *   (IN_PROGRESS / PENDING / ON_HOLD) for the given entity.
 *
 * Kept as its own small service (not inside the 3k-line WorkflowEngineService)
 * so non-workflow modules like DocumentController can depend on it without
 * pulling the whole engine's dependency graph.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowParticipantService {

    private static final List<WorkflowStatus> ACTIVE_STATUSES =
            List.of(WorkflowStatus.IN_PROGRESS, WorkflowStatus.PENDING, WorkflowStatus.ON_HOLD);

    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final StepInstanceRepository     stepInstanceRepository;
    private final TaskInstanceRepository     taskInstanceRepository;

    /**
     * Distinct userIds with a PENDING task on any active workflow instance
     * of the entity. Empty list when the entity has no active workflow —
     * callers should treat that as "nobody to notify", not an error.
     */
    public List<Long> findActiveParticipants(String entityType, Long entityId, Long tenantId) {
        if (entityType == null || entityId == null) return List.of();

        List<Long> instanceIds = workflowInstanceRepository
                .findByTenantIdAndEntityTypeAndEntityId(tenantId, entityType, entityId).stream()
                .filter(wi -> ACTIVE_STATUSES.contains(wi.getStatus()))
                .map(WorkflowInstance::getId)
                .toList();
        if (instanceIds.isEmpty()) return List.of();

        List<Long> stepInstanceIds = instanceIds.stream()
                .flatMap(id -> stepInstanceRepository.findByWorkflowInstanceId(id).stream())
                .map(StepInstance::getId)
                .toList();
        if (stepInstanceIds.isEmpty()) return List.of();

        List<Long> userIds = taskInstanceRepository
                .findByStepInstanceIdInAndStatus(stepInstanceIds, TaskStatus.PENDING).stream()
                .map(t -> t.getAssignedUserId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        log.debug("[PARTICIPANTS] entity={}/{} | activeInstances={} | participants={}",
                entityType, entityId, instanceIds.size(), userIds.size());
        return userIds;
    }
}
