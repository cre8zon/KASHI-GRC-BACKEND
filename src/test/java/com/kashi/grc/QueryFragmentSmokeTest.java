package com.kashi.grc;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.comment.domain.EntityComment;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.workflow.enums.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * QUERY FRAGMENT SMOKE TEST — invokes every Criteria-API fragment method once.
 *
 * WHY THIS EXISTS: JPQL @Query strings were validated by Hibernate at startup;
 * Criteria queries are built at runtime, so a wrong field name in root.get("...")
 * only fails when the method executes. This test executes ALL 143 fragment
 * methods so a clean run restores the boot-time guarantee the JPQL migration
 * traded away.
 *
 * SAFETY: read queries use -1L / "smoke" params (match nothing); bulk
 * CriteriaUpdate/Delete methods target id = -1 (update/delete 0 rows) AND the
 * class-level @Transactional rolls everything back. Safe against a dev DB.
 *
 * WHAT IT DOES NOT PROVE: result correctness (right rows, right order). It
 * proves executability: field names, joins, subqueries, type bindings, SQL
 * generation. Correctness of the high-traffic paths is covered by normal app
 * usage; the deliberate behavior changes are listed in MIGRATION_NOTES.md.
 *
 * RUN: mvn test -Dtest=QueryFragmentSmokeTest -Dspring.profiles.active=dev
 */
@SpringBootTest
@Transactional
class QueryFragmentSmokeTest {

    private static final LocalDateTime NOW = LocalDateTime.now();
    private static final LocalDate TODAY = LocalDate.now();

    @Autowired com.kashi.grc.actionitem.repository.ActionItemBlueprintRepository actionItemBlueprintRepository;
    @Autowired com.kashi.grc.actionitem.repository.ActionItemRepository actionItemRepository;
    @Autowired com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository assessmentQuestionInstanceRepository;
    @Autowired com.kashi.grc.assessment.repository.AssessmentResponseRepository assessmentResponseRepository;
    @Autowired com.kashi.grc.assessment.repository.AssessmentSectionInstanceRepository assessmentSectionInstanceRepository;
    @Autowired com.kashi.grc.assessment.repository.ContributorSectionSubmissionRepository contributorSectionSubmissionRepository;
    @Autowired com.kashi.grc.assessment.repository.QuestionCommentRepository questionCommentRepository;
    @Autowired com.kashi.grc.assessment.repository.ReviewerAssistantSectionSubmissionRepository reviewerAssistantSectionSubmissionRepository;
    @Autowired com.kashi.grc.assessment.repository.SectionQuestionMappingRepository sectionQuestionMappingRepository;
    @Autowired com.kashi.grc.audit.repository.AuditControlInstanceRepository auditControlInstanceRepository;
    @Autowired com.kashi.grc.audit.repository.AuditControlInstanceTestMappingRepository auditControlInstanceTestMappingRepository;
    @Autowired com.kashi.grc.audit.repository.AuditEngagementRepository auditEngagementRepository;
    @Autowired com.kashi.grc.audit.repository.AuditPolicyControlMappingRepository auditPolicyControlMappingRepository;
    @Autowired com.kashi.grc.audit.repository.AuditPolicyInstanceControlMappingRepository auditPolicyInstanceControlMappingRepository;
    @Autowired com.kashi.grc.audit.repository.AuditPolicyInstanceRepository auditPolicyInstanceRepository;
    @Autowired com.kashi.grc.audit.repository.AuditPolicyRepository auditPolicyRepository;
    @Autowired com.kashi.grc.audit.repository.AuditSectionInstanceRepository auditSectionInstanceRepository;
    @Autowired com.kashi.grc.audit.repository.AuditSectionRepository auditSectionRepository;
    @Autowired com.kashi.grc.audit.repository.AuditTestInstanceRepository auditTestInstanceRepository;
    @Autowired com.kashi.grc.audit.repository.AuditTestRepository auditTestRepository;
    @Autowired com.kashi.grc.comment.repository.EntityCommentRepository entityCommentRepository;
    @Autowired com.kashi.grc.document.repository.DocumentLinkRepository documentLinkRepository;
    @Autowired com.kashi.grc.document.repository.DocumentRepository documentRepository;
    @Autowired com.kashi.grc.evidence.repository.EvidenceLinkRepository evidenceLinkRepository;
    @Autowired com.kashi.grc.evidence.repository.EvidenceRecordRepository evidenceRecordRepository;
    @Autowired com.kashi.grc.guard.repository.GuardRuleRepository guardRuleRepository;
    @Autowired com.kashi.grc.guard.repository.SodRuleRepository sodRuleRepository;
    @Autowired com.kashi.grc.integration.repository.EngagementIntegrationSnapshotRepository engagementIntegrationSnapshotRepository;
    @Autowired com.kashi.grc.integration.repository.TenantIntegrationCheckRepository tenantIntegrationCheckRepository;
    @Autowired com.kashi.grc.issue.repository.IssueRepository issueRepository;
    @Autowired com.kashi.grc.uiconfig.repository.DashboardWidgetRepository dashboardWidgetRepository;
    @Autowired com.kashi.grc.uiconfig.repository.FeatureFlagRepository featureFlagRepository;
    @Autowired com.kashi.grc.uiconfig.repository.UiActionRepository uiActionRepository;
    @Autowired com.kashi.grc.uiconfig.repository.UiComponentRepository uiComponentRepository;
    @Autowired com.kashi.grc.uiconfig.repository.UiLayoutRepository uiLayoutRepository;
    @Autowired com.kashi.grc.uiconfig.repository.UiNavigationRepository uiNavigationRepository;
    @Autowired com.kashi.grc.uiconfig.repository.UiOptionRepository uiOptionRepository;
    @Autowired com.kashi.grc.uiconfig.repository.UiStateRepository uiStateRepository;
    @Autowired com.kashi.grc.usermanagement.repository.DelegationRepository delegationRepository;
    @Autowired com.kashi.grc.usermanagement.repository.PermissionGrantRepository permissionGrantRepository;
    @Autowired com.kashi.grc.usermanagement.repository.PermissionRepository permissionRepository;
    @Autowired com.kashi.grc.usermanagement.repository.RoleRepository roleRepository;
    @Autowired com.kashi.grc.usermanagement.repository.UserAttributeRepository userAttributeRepository;
    @Autowired com.kashi.grc.usermanagement.repository.UserPermissionOverrideRepository userPermissionOverrideRepository;
    @Autowired com.kashi.grc.workflow.repository.StepInstanceRepository stepInstanceRepository;
    @Autowired com.kashi.grc.workflow.repository.TaskInstanceRepository taskInstanceRepository;
    @Autowired com.kashi.grc.workflow.repository.TaskSectionAssignmentRepository taskSectionAssignmentRepository;
    @Autowired com.kashi.grc.workflow.repository.TaskSectionCompletionRepository taskSectionCompletionRepository;
    @Autowired com.kashi.grc.workflow.repository.WorkflowInstanceRepository workflowInstanceRepository;
    @Autowired com.kashi.grc.workflow.repository.WorkflowStepAssignerRoleRepository workflowStepAssignerRoleRepository;
    @Autowired com.kashi.grc.workflow.repository.WorkflowStepObserverRoleRepository workflowStepObserverRoleRepository;
    @Autowired com.kashi.grc.workflow.repository.WorkflowStepRoleRepository workflowStepRoleRepository;
    @Autowired com.kashi.grc.workflow.repository.WorkflowStepSectionRepository workflowStepSectionRepository;

    private final List<String> failures = new ArrayList<>();

    private void check(String name, Runnable call) {
        try {
            call.run();
        } catch (Exception e) {
            failures.add(name + "  →  " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Test
    void everyCriteriaFragmentMethodExecutes() {
        check("ActionItemBlueprintRepository.findBySourceTypeAndTenant", () -> actionItemBlueprintRepository.findBySourceTypeAndTenant(ActionItem.SourceType.values()[0], -1L));
        check("ActionItemBlueprintRepository.findVisibleToTenant", () -> actionItemBlueprintRepository.findVisibleToTenant(-1L));
        check("ActionItemRepository.countOpenForUser", () -> actionItemRepository.countOpenForUser(-1L, -1L));
        check("ActionItemRepository.existsByAssignedToAndAssessmentId", () -> actionItemRepository.existsByAssignedToAndAssessmentId(-1L, -1L));
        check("ActionItemRepository.existsOpenForEntity", () -> actionItemRepository.existsOpenForEntity("smoke", -1L));
        check("ActionItemRepository.existsOpenForSource", () -> actionItemRepository.existsOpenForSource(ActionItem.SourceType.values()[0], -1L));
        check("AssessmentQuestionInstanceRepository.findByTenantIdAndQuestionTagSnapshot", () -> assessmentQuestionInstanceRepository.findByTenantIdAndQuestionTagSnapshot(-1L, "smoke"));
        check("AssessmentQuestionInstanceRepository.sumWeightByAssessmentId", () -> assessmentQuestionInstanceRepository.sumWeightByAssessmentId(-1L));
        check("AssessmentResponseRepository.countAnsweredByAssessmentId", () -> assessmentResponseRepository.countAnsweredByAssessmentId(-1L));
        check("AssessmentResponseRepository.countByAssessmentIdAndSectionInstanceId", () -> assessmentResponseRepository.countByAssessmentIdAndSectionInstanceId(-1L, -1L));
        check("AssessmentResponseRepository.countEvaluatedInSections", () -> assessmentResponseRepository.countEvaluatedInSections(-1L, List.of(-1L)));
        check("AssessmentResponseRepository.countTotalInSections", () -> assessmentResponseRepository.countTotalInSections(List.of(-1L)));
        check("AssessmentResponseRepository.sumReviewerAdjustedScoreByAssessmentId", () -> assessmentResponseRepository.sumReviewerAdjustedScoreByAssessmentId(-1L));
        check("AssessmentResponseRepository.sumScoreEarnedByAssessmentId", () -> assessmentResponseRepository.sumScoreEarnedByAssessmentId(-1L));
        check("AssessmentResponseRepository.updateResponderStatus", () -> assessmentResponseRepository.updateResponderStatus(-1L, -1L, "smoke"));
        check("AssessmentSectionInstanceRepository.findDistinctAssignedResponderIds", () -> assessmentSectionInstanceRepository.findDistinctAssignedResponderIds(-1L));
        check("AssessmentSectionInstanceRepository.findDistinctAssignedReviewerIds", () -> assessmentSectionInstanceRepository.findDistinctAssignedReviewerIds(-1L));
        check("ContributorSectionSubmissionRepository.countDistinctSectionsWithAssignments", () -> contributorSectionSubmissionRepository.countDistinctSectionsWithAssignments(-1L, -1L));
        check("QuestionCommentRepository.findByAssessmentAndQuestion", () -> questionCommentRepository.findByAssessmentAndQuestion(-1L, -1L));
        check("ReviewerAssistantSectionSubmissionRepository.countDistinctSectionsWithAssignments", () -> reviewerAssistantSectionSubmissionRepository.countDistinctSectionsWithAssignments(-1L, -1L));
        check("SectionQuestionMappingRepository.countQuestionsForTemplate", () -> sectionQuestionMappingRepository.countQuestionsForTemplate(-1L));
        check("AuditControlInstanceRepository.countByResultForEngagement", () -> auditControlInstanceRepository.countByResultForEngagement(-1L));
        check("AuditControlInstanceRepository.countFindingsLinkedByEngagement", () -> auditControlInstanceRepository.countFindingsLinkedByEngagement(-1L));
        check("AuditControlInstanceRepository.countTestedByEngagement", () -> auditControlInstanceRepository.countTestedByEngagement(-1L));
        check("AuditControlInstanceRepository.countTestedByEngagementId", () -> auditControlInstanceRepository.countTestedByEngagementId(-1L));
        check("AuditControlInstanceRepository.findByEngagementIdAndSectionAuditorId", () -> auditControlInstanceRepository.findByEngagementIdAndSectionAuditorId(-1L, -1L));
        check("AuditControlInstanceRepository.findByEngagementIdAndSectionPathStartingWith", () -> auditControlInstanceRepository.findByEngagementIdAndSectionPathStartingWith(-1L, "smoke"));
        check("AuditControlInstanceRepository.findBySectionInstanceId_OriginalSectionId", () -> auditControlInstanceRepository.findBySectionInstanceId_OriginalSectionId(-1L));
        check("AuditControlInstanceRepository.findByTenantIdAndControlTagSnapshot", () -> auditControlInstanceRepository.findByTenantIdAndControlTagSnapshot(-1L, "smoke"));
        check("AuditControlInstanceRepository.findDistinctAssignedAuditeeIdsByEngagementId", () -> auditControlInstanceRepository.findDistinctAssignedAuditeeIdsByEngagementId(-1L));
        check("AuditControlInstanceRepository.findDueForEvidenceReminder", () -> auditControlInstanceRepository.findDueForEvidenceReminder(TODAY));
        check("AuditControlInstanceTestMappingRepository.findControlInstanceIdsByTestInstanceId", () -> auditControlInstanceTestMappingRepository.findControlInstanceIdsByTestInstanceId(-1L));
        check("AuditControlInstanceTestMappingRepository.findRequiredTestInstanceIdsByControlInstanceId", () -> auditControlInstanceTestMappingRepository.findRequiredTestInstanceIdsByControlInstanceId(-1L));
        check("AuditEngagementRepository.countActiveByProjectId", () -> auditEngagementRepository.countActiveByProjectId(-1L));
        check("AuditEngagementRepository.countByStatusForTenant", () -> auditEngagementRepository.countByStatusForTenant(-1L));
        check("AuditEngagementRepository.nextEngagementRefSequence", () -> auditEngagementRepository.nextEngagementRefSequence(-1L));
        check("AuditPolicyControlMappingRepository.findControlIdsByPolicyId", () -> auditPolicyControlMappingRepository.findControlIdsByPolicyId(-1L));
        check("AuditPolicyInstanceControlMappingRepository.findControlInstanceIdsByPolicyInstanceId", () -> auditPolicyInstanceControlMappingRepository.findControlInstanceIdsByPolicyInstanceId(-1L));
        check("AuditPolicyInstanceControlMappingRepository.findPolicyInstanceIdsByControlInstanceId", () -> auditPolicyInstanceControlMappingRepository.findPolicyInstanceIdsByControlInstanceId(-1L));
        check("AuditPolicyInstanceRepository.findByEngagementIdAndTenantId", () -> auditPolicyInstanceRepository.findByEngagementIdAndTenantId(-1L, -1L));
        check("AuditPolicyRepository.countForTenant", () -> auditPolicyRepository.countForTenant(-1L));
        check("AuditPolicyRepository.findByPolicyRefForTenant", () -> auditPolicyRepository.findByPolicyRefForTenant("smoke", -1L));
        check("AuditPolicyRepository.findByTenantIdAndStatus", () -> auditPolicyRepository.findByTenantIdAndStatus(-1L, AuditPolicy.PolicyStatus.APPROVED));
        check("AuditPolicyRepository.findByTenantIdOrderByTitleAsc", () -> auditPolicyRepository.findByTenantIdOrderByTitleAsc(-1L));
        check("AuditPolicyRepository.findDueForReview", () -> auditPolicyRepository.findDueForReview(-1L, TODAY));
        check("AuditPolicyRepository.searchByTitle", () -> auditPolicyRepository.searchByTitle(-1L, "smoke"));
        check("AuditSectionInstanceRepository.countSubmittedByEngagement", () -> auditSectionInstanceRepository.countSubmittedByEngagement(-1L));
        check("AuditSectionInstanceRepository.countTotalByEngagement", () -> auditSectionInstanceRepository.countTotalByEngagement(-1L));
        check("AuditSectionInstanceRepository.findAllDescendants", () -> auditSectionInstanceRepository.findAllDescendants(-1L, "smoke"));
        check("AuditSectionInstanceRepository.findDistinctAssignedAuditeeIdsByEngagementId", () -> auditSectionInstanceRepository.findDistinctAssignedAuditeeIdsByEngagementId(-1L));
        check("AuditSectionInstanceRepository.findDistinctAssignedAuditorIdsByEngagementId", () -> auditSectionInstanceRepository.findDistinctAssignedAuditorIdsByEngagementId(-1L));
        check("AuditSectionRepository.findAllDescendants", () -> auditSectionRepository.findAllDescendants(-1L, "smoke"));
        check("AuditSectionRepository.findAllUnderAncestor", () -> auditSectionRepository.findAllUnderAncestor(-1L));
        check("AuditSectionRepository.findRootSections", () -> auditSectionRepository.findRootSections(-1L));
        check("AuditSectionRepository.findSubtree", () -> auditSectionRepository.findSubtree("smoke"));
        check("AuditTestInstanceRepository.findByEngagementIdAndTenantId", () -> auditTestInstanceRepository.findByEngagementIdAndTenantId(-1L, -1L));
        check("AuditTestRepository.countForTenant", () -> auditTestRepository.countForTenant(-1L));
        check("AuditTestRepository.searchByName", () -> auditTestRepository.searchByName(-1L, "smoke"));
        check("EntityCommentRepository.countOpenRevisionRequests", () -> entityCommentRepository.countOpenRevisionRequests(-1L));
        check("EntityCommentRepository.findVisible", () -> entityCommentRepository.findVisible(EntityComment.EntityType.values()[0], -1L, List.of(EntityComment.Visibility.values())));
        check("DocumentLinkRepository.countActiveAttachments", () -> documentLinkRepository.countActiveAttachments("smoke", -1L));
        check("DocumentLinkRepository.countActiveAttachmentsBulk", () -> documentLinkRepository.countActiveAttachmentsBulk("smoke", List.of(-1L)));
        check("DocumentLinkRepository.findActiveByEntity", () -> documentLinkRepository.findActiveByEntity("smoke", -1L, "smoke"));
        check("DocumentLinkRepository.findAllActiveByEntity", () -> documentLinkRepository.findAllActiveByEntity("smoke", -1L));
        check("DocumentLinkRepository.findReportVersions", () -> documentLinkRepository.findReportVersions("smoke", -1L));
        check("DocumentRepository.findAbandonedUploads", () -> documentRepository.findAbandonedUploads(NOW));
        check("DocumentRepository.markDeleted", () -> documentRepository.markDeleted(-1L));
        check("DocumentRepository.markSuperseded", () -> documentRepository.markSuperseded(-1L));
        check("EvidenceLinkRepository.countAcceptedForEntity", () -> evidenceLinkRepository.countAcceptedForEntity("smoke", -1L));
        check("EvidenceLinkRepository.expireByEvidenceRecordId", () -> evidenceLinkRepository.expireByEvidenceRecordId(-1L));
        check("EvidenceLinkRepository.findControlEvidenceUsedByTest", () -> evidenceLinkRepository.findControlEvidenceUsedByTest(-1L, -1L));
        check("EvidenceLinkRepository.findPendingReviewForTenant", () -> evidenceLinkRepository.findPendingReviewForTenant(-1L));
        check("EvidenceLinkRepository.findTestsUsingControlEvidence", () -> evidenceLinkRepository.findTestsUsingControlEvidence(-1L, -1L));
        check("EvidenceRecordRepository.countActiveByTenantAndTag", () -> evidenceRecordRepository.countActiveByTenantAndTag(-1L, "smoke"));
        check("GuardRuleRepository.findActiveRulesForTag", () -> guardRuleRepository.findActiveRulesForTag("smoke", -1L));
        check("SodRuleRepository.countActiveForTenant", () -> sodRuleRepository.countActiveForTenant(-1L));
        check("SodRuleRepository.existsConflictBetween", () -> sodRuleRepository.existsConflictBetween(-1L, "smoke", "smoke"));
        check("SodRuleRepository.findActiveByTenantId", () -> sodRuleRepository.findActiveByTenantId(-1L));
        check("SodRuleRepository.findActiveRulesForEntityType", () -> sodRuleRepository.findActiveRulesForEntityType("smoke"));
        check("SodRuleRepository.findByTenantId", () -> sodRuleRepository.findByTenantId(-1L));
        check("SodRuleRepository.findByTenantIdAndSeverity", () -> sodRuleRepository.findByTenantIdAndSeverity(-1L, "smoke"));
        check("SodRuleRepository.findConflictBetween", () -> sodRuleRepository.findConflictBetween(-1L, -1L, -1L));
        check("SodRuleRepository.findViolationsForProposedRole", () -> sodRuleRepository.findViolationsForProposedRole(-1L, -1L, Set.of(-1L)));
        check("EngagementIntegrationSnapshotRepository.countFailingByEngagementId", () -> engagementIntegrationSnapshotRepository.countFailingByEngagementId(-1L, -1L));
        check("EngagementIntegrationSnapshotRepository.countNeverRunByEngagementId", () -> engagementIntegrationSnapshotRepository.countNeverRunByEngagementId(-1L, -1L));
        check("EngagementIntegrationSnapshotRepository.countPassingByEngagementId", () -> engagementIntegrationSnapshotRepository.countPassingByEngagementId(-1L, -1L));
        check("EngagementIntegrationSnapshotRepository.deactivateByEngagementId", () -> engagementIntegrationSnapshotRepository.deactivateByEngagementId(-1L, -1L));
        check("TenantIntegrationCheckRepository.countFailingByTenantAndIntegration", () -> tenantIntegrationCheckRepository.countFailingByTenantAndIntegration(-1L, "smoke"));
        check("TenantIntegrationCheckRepository.countNeverRunByTenantAndIntegration", () -> tenantIntegrationCheckRepository.countNeverRunByTenantAndIntegration(-1L, "smoke"));
        check("TenantIntegrationCheckRepository.countPassingByTenantAndIntegration", () -> tenantIntegrationCheckRepository.countPassingByTenantAndIntegration(-1L, "smoke"));
        check("TenantIntegrationCheckRepository.deactivateByTenantAndIntegration", () -> tenantIntegrationCheckRepository.deactivateByTenantAndIntegration(-1L, "smoke"));
        check("IssueRepository.closeIssue", () -> issueRepository.closeIssue(-1L, -1L, Issue.Status.CLOSED, NOW, -1L));
        check("IssueRepository.countByStatusForTenant", () -> issueRepository.countByStatusForTenant(-1L));
        check("IssueRepository.countOpenBySeverityForTenant", () -> issueRepository.countOpenBySeverityForTenant(-1L));
        check("IssueRepository.countSlaBreachedForTenant", () -> issueRepository.countSlaBreachedForTenant(-1L));
        check("IssueRepository.findActiveBreachedForReescalation", () -> issueRepository.findActiveBreachedForReescalation(NOW));
        check("IssueRepository.findBreachedIssues", () -> issueRepository.findBreachedIssues(NOW));
        check("IssueRepository.nextIssueRefSequence", () -> issueRepository.nextIssueRefSequence(-1L));
        check("DashboardWidgetRepository.findActiveByTenant", () -> dashboardWidgetRepository.findActiveByTenant(-1L));
        check("FeatureFlagRepository.findEnabledForTenant", () -> featureFlagRepository.findEnabledForTenant(-1L));
        check("UiActionRepository.findAllByScreenAndTenant", () -> uiActionRepository.findAllByScreenAndTenant("smoke", -1L));
        check("UiActionRepository.findAllByTenant", () -> uiActionRepository.findAllByTenant(-1L));
        check("UiActionRepository.findByScreenAndTenant", () -> uiActionRepository.findByScreenAndTenant("smoke", -1L));
        check("UiComponentRepository.findByScreenForTenant", () -> uiComponentRepository.findByScreenForTenant("smoke", -1L));
        check("UiLayoutRepository.findAllByScreenAndTenant", () -> uiLayoutRepository.findAllByScreenAndTenant("smoke", -1L));
        check("UiLayoutRepository.findAllByTenant", () -> uiLayoutRepository.findAllByTenant(-1L));
        check("UiNavigationRepository.findAllForTenant", () -> uiNavigationRepository.findAllForTenant(-1L));
        check("UiOptionRepository.findByComponentKeyAndTenant", () -> uiOptionRepository.findByComponentKeyAndTenant("smoke", -1L));
        check("UiOptionRepository.findByComponentKeysAndTenant", () -> uiOptionRepository.findByComponentKeysAndTenant(List.of("smoke"), -1L));
        check("UiStateRepository.findByScreenAndType", () -> uiStateRepository.findByScreenAndType("smoke", "smoke", -1L));
        check("UiStateRepository.findByScreenForTenant", () -> uiStateRepository.findByScreenForTenant("smoke", -1L));
        check("DelegationRepository.countActiveDelegationsByMe", () -> delegationRepository.countActiveDelegationsByMe(-1L));
        check("DelegationRepository.countActiveDelegationsToMe", () -> delegationRepository.countActiveDelegationsToMe(-1L));
        check("DelegationRepository.findActive", () -> delegationRepository.findActive(-1L, -1L, "smoke", NOW));
        check("PermissionGrantRepository.deleteByPermissionId", () -> permissionGrantRepository.deleteByPermissionId(-1L));
        check("PermissionGrantRepository.findActiveGrantsByRoleIds", () -> permissionGrantRepository.findActiveGrantsByRoleIds(List.of(-1L)));
        check("PermissionGrantRepository.findByRoleIdWithPermission", () -> permissionGrantRepository.findByRoleIdWithPermission(-1L));
        check("PermissionGrantRepository.findGrantsForUserRoles", () -> permissionGrantRepository.findGrantsForUserRoles(List.of(-1L)));
        check("PermissionRepository.findAllByUserId", () -> permissionRepository.findAllByUserId(-1L));
        check("RoleRepository.countUsersWithRole", () -> roleRepository.countUsersWithRole(-1L));
        check("RoleRepository.findAllForTenant", () -> roleRepository.findAllForTenant(-1L));
        check("RoleRepository.findAllForTenantBySide", () -> roleRepository.findAllForTenantBySide(-1L, null));
        check("UserAttributeRepository.deleteByUserId", () -> userAttributeRepository.deleteByUserId(-1L));
        check("UserAttributeRepository.deleteByUserIdAndAttributeKey", () -> userAttributeRepository.deleteByUserIdAndAttributeKey(-1L, "smoke"));
        check("UserAttributeRepository.findByUserId", () -> userAttributeRepository.findByUserId(-1L));
        check("UserAttributeRepository.findByUserIdAndAttributeKey", () -> userAttributeRepository.findByUserIdAndAttributeKey(-1L, "smoke"));
        check("UserPermissionOverrideRepository.deleteByPermissionId", () -> userPermissionOverrideRepository.deleteByPermissionId(-1L));
        check("UserPermissionOverrideRepository.findActiveByUserId", () -> userPermissionOverrideRepository.findActiveByUserId(-1L, NOW));
        check("StepInstanceRepository.findAllSlaBreached", () -> stepInstanceRepository.findAllSlaBreached(NOW));
        check("StepInstanceRepository.findByIdForUpdate", () -> stepInstanceRepository.findByIdForUpdate(-1L));
        check("StepInstanceRepository.findStuckSteps", () -> stepInstanceRepository.findStuckSteps());
        check("TaskInstanceRepository.existsByUserIdAndWorkflowInstanceId", () -> taskInstanceRepository.existsByUserIdAndWorkflowInstanceId(-1L, -1L));
        check("TaskInstanceRepository.existsByUserIdAndWorkflowInstanceIdAndStatusIn", () -> taskInstanceRepository.existsByUserIdAndWorkflowInstanceIdAndStatusIn(-1L, -1L, List.of(TaskStatus.PENDING)));
        check("TaskInstanceRepository.findActorTasksForInstance", () -> taskInstanceRepository.findActorTasksForInstance(-1L, -1L));
        check("TaskSectionAssignmentRepository.countIncomplete", () -> taskSectionAssignmentRepository.countIncomplete(-1L, "smoke"));
        check("TaskSectionCompletionRepository.countCompletedRequired", () -> taskSectionCompletionRepository.countCompletedRequired(-1L));
        check("TaskSectionCompletionRepository.countTotalRequired", () -> taskSectionCompletionRepository.countTotalRequired(-1L));
        check("TaskSectionCompletionRepository.findIncompleteRequired", () -> taskSectionCompletionRepository.findIncompleteRequired(-1L));
        check("WorkflowInstanceRepository.findActiveByEntityTypeAndEntityId", () -> workflowInstanceRepository.findActiveByEntityTypeAndEntityId("smoke", -1L));
        check("WorkflowStepAssignerRoleRepository.deleteByStepId", () -> workflowStepAssignerRoleRepository.deleteByStepId(-1L));
        check("WorkflowStepObserverRoleRepository.deleteByStepId", () -> workflowStepObserverRoleRepository.deleteByStepId(-1L));
        check("WorkflowStepRoleRepository.deleteByStepId", () -> workflowStepRoleRepository.deleteByStepId(-1L));
        check("WorkflowStepSectionRepository.deleteByStepId", () -> workflowStepSectionRepository.deleteByStepId(-1L));

        if (!failures.isEmpty()) {
            fail("Fragment methods failed to execute (" + failures.size() + "):\n  "
                    + String.join("\n  ", failures));
        }
    }
}
