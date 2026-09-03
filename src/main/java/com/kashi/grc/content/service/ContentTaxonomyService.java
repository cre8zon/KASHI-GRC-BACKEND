package com.kashi.grc.content.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.content.domain.ContentAuthor;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.ContentCategory;
import com.kashi.grc.content.domain.ContentTag;
import com.kashi.grc.content.repository.ContentAuthorRepository;
import com.kashi.grc.content.repository.ContentCategoryRepository;
import com.kashi.grc.content.repository.ContentTagRepository;
import com.kashi.grc.content.repository.PostTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Categories, tags and authors. Small on purpose — the interesting rules live
 * in PostService, and taxonomy that grows its own logic is taxonomy that has
 * started to be a content type.
 *
 * ── THE TAG INDEXABILITY THRESHOLD ───────────────────────────────────────────
 * A tag page with two posts on it is a thin duplicate of the category page it
 * sits under, and shipping dozens of them is how a site accumulates pages that
 * dilute its own relevance. Tags become indexable automatically once they carry
 * enough published posts to be worth landing on. An editor can still override
 * it, but the default is off rather than on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentTaxonomyService {

    /** Below this, a tag page is a thin duplicate of its category. */
    private static final int INDEXABLE_THRESHOLD = 4;

    private final ContentCategoryRepository categoryRepository;
    private final ContentTagRepository tagRepository;
    private final ContentAuthorRepository authorRepository;
    private final PostTagRepository postTagRepository;
    private final SlugService slugService;

    public List<ContentCategory> allCategories() {
        return categoryRepository.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional
    public ContentCategory saveCategory(ContentCategory c) {
        if (c.getSlug() == null || c.getSlug().isBlank()) {
            c.setSlug(slugService.unique(slugService.slugify(c.getName()),
                    categoryRepository::existsBySlug));
        } else {
            c.setSlug(slugService.slugify(c.getSlug()));
        }
        boolean taken = c.getId() == null
                ? categoryRepository.existsBySlug(c.getSlug())
                : categoryRepository.existsBySlugAndIdNot(c.getSlug(), c.getId());
        if (taken) throw new BusinessException("SLUG_TAKEN", "Another category uses that slug");
        return categoryRepository.save(c);
    }

    public List<ContentTag> allTags() {
        return tagRepository.findAllByOrderByNameAsc();
    }

    @Transactional
    public ContentTag saveTag(ContentTag t) {
        if (t.getSlug() == null || t.getSlug().isBlank()) {
            t.setSlug(slugService.unique(slugService.slugify(t.getName()),
                    tagRepository::existsBySlug));
        }
        return tagRepository.save(t);
    }

    public List<ContentAuthor> allAuthors() {
        return authorRepository.findByActiveTrueOrderByDisplayNameAsc();
    }

    @Transactional
    public ContentAuthor saveAuthor(ContentAuthor a) {
        if (a.getSlug() == null || a.getSlug().isBlank()) {
            a.setSlug(slugService.unique(slugService.slugify(a.getDisplayName()),
                    authorRepository::existsBySlug));
        }
        boolean taken = a.getId() == null
                ? authorRepository.existsBySlug(a.getSlug())
                : authorRepository.existsBySlugAndIdNot(a.getSlug(), a.getId());
        if (taken) throw new BusinessException("SLUG_TAKEN", "Another author uses that slug");
        return authorRepository.save(a);
    }

    /**
     * Recompute which tags are worth indexing. Cheap, and run after publishing
     * rather than on a timer so a tag crossing the threshold takes effect at
     * the next build rather than the next week.
     */
    @Transactional
    public void refreshTagIndexability() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : postTagRepository.countPublishedPerTag(PostStatus.PUBLISHED)) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        List<ContentTag> tags = tagRepository.findAll();
        for (ContentTag tag : tags) {
            boolean shouldIndex = counts.getOrDefault(tag.getId(), 0L) >= INDEXABLE_THRESHOLD;
            if (!Boolean.valueOf(shouldIndex).equals(tag.getIndexable())) {
                tag.setIndexable(shouldIndex);
            }
        }
        tagRepository.saveAll(tags);
    }

    public Map<Long, Long> publishedCountPerTag() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : postTagRepository.countPublishedPerTag(PostStatus.PUBLISHED)) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }
}