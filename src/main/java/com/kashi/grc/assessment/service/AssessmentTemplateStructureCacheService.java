package com.kashi.grc.assessment.service;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.dto.internal.TemplateStructureSnapshot;
import com.kashi.grc.assessment.dto.internal.TemplateStructureSnapshot.OptionSnapshot;
import com.kashi.grc.assessment.dto.internal.TemplateStructureSnapshot.QuestionSnapshot;
import com.kashi.grc.assessment.dto.internal.TemplateStructureSnapshot.SectionSnapshot;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.common.cache.CacheNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Caches the full section→question→option library structure for a template,
 * so instantiation (ExecuteAssessmentAction) goes from 5 read queries to 1
 * Redis GET on a cache hit — the read-side counterpart to the JDBC
 * batch-insert work already done on the write side of that same flow.
 *
 * KEY IS GLOBAL, NOT TENANT-SCOPED: templateId is a table primary key,
 * globally unique regardless of whether the template row itself is a GLOBAL
 * or TENANT-scoped library entry (see GlobalOrTenantEntity). Tenant-scoping
 * this key (the app's default via TenantAwareKeyGenerator) would create a
 * separate cached copy of the same structure per tenant that happens to use
 * a shared global template — pure waste, since the underlying rows are
 * identical. The explicit key below opts out of that default the same way
 * TagExpansionService's UCF catalogue cache does.
 *
 * INVALIDATION: TTL-only for now (30 min, see CacheConfig) — template
 * library edits are rare admin actions and 30 minutes of staleness on a
 * freshly-edited-but-not-yet-published template is an acceptable trade for
 * not having to wire @CacheEvict into every template/section/question/option
 * admin CRUD endpoint individually. evictTemplateStructure() below exists
 * for the one call site that DOES already know it just changed a template
 * (the library admin service) to opt into immediate invalidation instead of
 * waiting on the TTL, without requiring every other write path to remember to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentTemplateStructureCacheService {

    private final TemplateSectionMappingRepository  templateSectionMappingRepository;
    private final AssessmentSectionRepository        sectionRepository;
    private final SectionQuestionMappingRepository   sectionQuestionMappingRepository;
    private final AssessmentQuestionRepository       questionRepository;
    private final QuestionOptionMappingRepository    questionOptionMappingRepository;
    private final AssessmentQuestionOptionRepository optionRepository;

    @Cacheable(cacheNames = CacheNames.ASSESSMENT_TEMPLATE_STRUCTURE, key = "'template:' + #templateId")
    public TemplateStructureSnapshot getStructure(Long templateId) {
        List<TemplateSectionMapping> sectionMappings =
                templateSectionMappingRepository.findByTemplateIdOrderByOrderNo(templateId);

        List<Long> sectionIds = sectionMappings.stream()
                .map(TemplateSectionMapping::getSectionId).toList();
        Map<Long, AssessmentSection> sectionMap = sectionRepository.findAllById(sectionIds)
                .stream().collect(Collectors.toMap(AssessmentSection::getId, s -> s));

        List<SectionQuestionMapping> allQuestionMappings =
                sectionQuestionMappingRepository.findBySectionIdInOrderByOrderNo(sectionIds);
        List<Long> questionIds = allQuestionMappings.stream()
                .map(SectionQuestionMapping::getQuestionId).toList();
        Map<Long, AssessmentQuestion> questionMap = questionRepository.findAllById(questionIds)
                .stream().collect(Collectors.toMap(AssessmentQuestion::getId, q -> q));

        List<QuestionOptionMapping> allOptionMappings =
                questionOptionMappingRepository.findByQuestionIdInOrderByOrderNo(questionIds);
        List<Long> optionIds = allOptionMappings.stream()
                .map(QuestionOptionMapping::getOptionId).toList();
        Map<Long, AssessmentQuestionOption> optionMap = optionRepository.findAllById(optionIds)
                .stream().collect(Collectors.toMap(AssessmentQuestionOption::getId, o -> o));

        Map<Long, List<SectionQuestionMapping>> questionsBySectionId = allQuestionMappings.stream()
                .collect(Collectors.groupingBy(SectionQuestionMapping::getSectionId));
        Map<Long, List<QuestionOptionMapping>> optionsByQuestionId = allOptionMappings.stream()
                .collect(Collectors.groupingBy(QuestionOptionMapping::getQuestionId));

        List<SectionSnapshot> sections = sectionMappings.stream()
                .map(tsm -> sectionMap.get(tsm.getSectionId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(section -> buildSectionSnapshot(section, sectionMappings, questionsBySectionId,
                        questionMap, optionsByQuestionId, optionMap))
                .toList();

        log.debug("[TEMPLATE-STRUCTURE] Built structure from MySQL | templateId={} | sections={}",
                templateId, sections.size());
        return new TemplateStructureSnapshot(sections);
    }

    private SectionSnapshot buildSectionSnapshot(
            AssessmentSection section,
            List<TemplateSectionMapping> sectionMappings,
            Map<Long, List<SectionQuestionMapping>> questionsBySectionId,
            Map<Long, AssessmentQuestion> questionMap,
            Map<Long, List<QuestionOptionMapping>> optionsByQuestionId,
            Map<Long, AssessmentQuestionOption> optionMap) {

        Integer sectionOrderNo = sectionMappings.stream()
                .filter(tsm -> tsm.getSectionId().equals(section.getId()))
                .map(TemplateSectionMapping::getOrderNo)
                .findFirst().orElse(0);

        List<QuestionSnapshot> questions = questionsBySectionId
                .getOrDefault(section.getId(), List.of()).stream()
                .map(sqm -> {
                    AssessmentQuestion q = questionMap.get(sqm.getQuestionId());
                    if (q == null) return null; // orphaned mapping
                    List<OptionSnapshot> options = optionsByQuestionId
                            .getOrDefault(q.getId(), List.of()).stream()
                            .map(qom -> {
                                AssessmentQuestionOption opt = optionMap.get(qom.getOptionId());
                                if (opt == null) return null;
                                return new OptionSnapshot(opt.getId(), opt.getOptionValue(),
                                        opt.getScore(), qom.getOrderNo());
                            })
                            .filter(java.util.Objects::nonNull)
                            .toList();
                    return new QuestionSnapshot(q.getId(), q.getQuestionText(), q.getResponseType(),
                            q.getQuestionTag(), sqm.getWeight(), sqm.isMandatory(), sqm.getOrderNo(), options);
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        return new SectionSnapshot(section.getId(), section.getName(), sectionOrderNo, questions);
    }

    /** Called by the library admin path after a template/section/question/option edit. */
    @CacheEvict(cacheNames = CacheNames.ASSESSMENT_TEMPLATE_STRUCTURE, key = "'template:' + #templateId")
    public void evictTemplateStructure(Long templateId) {
        log.info("[TEMPLATE-STRUCTURE] Evicted | templateId={}", templateId);
    }
}