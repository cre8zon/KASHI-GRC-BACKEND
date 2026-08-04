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
            List<OptionSnapshot> options) {}

    public record OptionSnapshot(
            Long libraryOptionId,
            String optionValue,
            Double score,
            Integer orderNo) {}
}