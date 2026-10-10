package com.kashi.grc.assessment.dto.internal;

import java.util.List;

/**
 * Everything AssessmentTemplateStructureCacheService needs to hand back to
 * instantiation (ExecuteAssessmentAction) in one object: sections, and per
 * section, its questions (library fields + this-mapping's weight/mandatory/
 * orderNo together), and per question, its options.
 *
 * Plain records, no JPA entities — this is exactly what
 * GenericJackson2JsonRedisSerializer needs to round-trip cleanly through
 * Redis, and it keeps this cache layer decoupled from entity/schema changes
 * that don't affect what instantiation actually reads.
 */
public record TemplateStructureSnapshot(List<SectionSnapshot> sections) {

    public record SectionSnapshot(
            Long librarySectionId,
            String sectionName,
            Integer orderNo,
            List<QuestionSnapshot> questions) {}

    public record QuestionSnapshot(
            Long libraryQuestionId,
            String questionText,
            String responseType,
            String questionTag,
            Double weight,
            boolean mandatory,
            Integer orderNo,
            /**
             * Snapshot of AssessmentQuestion.requiresEvidence.
             *
             * Missing here was the whole bug. assessment_question_instances
             * .requires_evidence is NOT NULL with no DB default, and
             * ExecuteAssessmentAction inserts that table with hand-written SQL
             * naming its columns explicitly. The column was added to the entity
             * and to the library question, but never threaded through this
             * record, so instantiation could not have supplied it even if the
             * INSERT had asked for it — MySQL in strict mode then rejected the
             * whole batch with 1364, and no assessment could be created.
             *
             * Carried as a snapshot for the same reason as questionTag: turning
             * evidence on for a library question next quarter must not
             * retroactively make an already-submitted answer incomplete.
             */
            boolean requiresEvidence,
            List<OptionSnapshot> options) {}

    public record OptionSnapshot(
            Long libraryOptionId,
            String optionValue,
            Double score,
            Integer orderNo) {}
}