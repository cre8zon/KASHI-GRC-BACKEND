package com.kashi.grc.content.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.content.domain.ContentRedirect;
import com.kashi.grc.content.repository.ContentRedirectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loops and chains. These are the two failure modes that are silent in
 * production — a loop makes the URL unreachable and crawlers drop it; a chain
 * leaks authority at every hop and nobody notices until a ranking moves.
 *
 * An in-memory fake rather than Mockito: the logic under test walks the
 * redirect table, and stubbing findByFromPath call-by-call would encode the
 * traversal order into the test, which is exactly the thing most likely to
 * change.
 */
class RedirectServiceTest {

    private FakeRepo repo;
    private RedirectService service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        service = new RedirectService(repo);
    }

    @Test
    @DisplayName("renaming a published slug writes a 301 from the old path")
    void writesRedirectOnRename() {
        service.recordSlugChange(1L, "old-title", "new-title", 7L);

        ContentRedirect r = repo.findByFromPath("/blog/old-title").orElseThrow();
        assertThat(r.getToPath()).isEqualTo("/blog/new-title");
        assertThat(r.getStatusCode()).isEqualTo(301);
        assertThat(r.getActive()).isTrue();
    }

    @Test
    @DisplayName("a second rename repoints the first redirect instead of chaining")
    void flattensChain() {
        service.recordSlugChange(1L, "a", "b", 7L);
        service.recordSlugChange(1L, "b", "c", 7L);

        // /blog/a must go straight to /blog/c, not via /blog/b.
        assertThat(repo.findByFromPath("/blog/a").orElseThrow().getToPath())
                .isEqualTo("/blog/c");
        assertThat(repo.findByFromPath("/blog/b").orElseThrow().getToPath())
                .isEqualTo("/blog/c");
    }

    @Test
    @DisplayName("renaming back to a previous slug is refused as a loop")
    void rejectsLoop() {
        service.recordSlugChange(1L, "a", "b", 7L);

        assertThatThrownBy(() -> service.create("/blog/b", "/blog/a", 301, "manual", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("loop");
    }

    @Test
    @DisplayName("publishing a slug that is the source of a live redirect is refused")
    void rejectsPublishingRedirectedSlug() {
        service.recordSlugChange(1L, "old", "new", 7L);

        assertThatThrownBy(() -> service.assertSlugNotRedirected("old"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("redirects to");
    }

    @Test
    @DisplayName("a no-op rename writes nothing")
    void ignoresUnchangedSlug() {
        service.recordSlugChange(1L, "same", "same", 7L);
        assertThat(repo.rows).isEmpty();
    }

    /** Minimal in-memory stand-in for the JPA repository. */
    static class FakeRepo implements ContentRedirectRepository {
        final Map<String, ContentRedirect> rows = new HashMap<>();
        private long seq = 0;

        @Override public Optional<ContentRedirect> findByFromPath(String p) {
            return Optional.ofNullable(rows.get(p));
        }
        @Override public List<ContentRedirect> findByActiveTrue() {
            return rows.values().stream().filter(ContentRedirect::getActive).toList();
        }
        @Override public List<ContentRedirect> findByToPath(String p) {
            return rows.values().stream().filter(r -> p.equals(r.getToPath())).toList();
        }
        @Override public List<ContentRedirect> findByPostId(Long id) {
            return rows.values().stream().filter(r -> id.equals(r.getPostId())).toList();
        }
        @Override public <S extends ContentRedirect> S save(S entity) {
            if (entity.getId() == null) entity.setId(++seq);
            rows.put(entity.getFromPath(), entity);
            return entity;
        }
        @Override public <S extends ContentRedirect> List<S> saveAll(Iterable<S> entities) {
            List<S> out = new ArrayList<>();
            entities.forEach(e -> out.add(save(e)));
            return out;
        }
        // Remaining JpaRepository methods are unused by this service.
        @Override public List<ContentRedirect> findAll() { return List.copyOf(rows.values()); }
        @Override public Optional<ContentRedirect> findById(Long id) { return Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) { }
        @Override public void delete(ContentRedirect e) { rows.remove(e.getFromPath()); }
        @Override public void deleteAll() { rows.clear(); }
        @Override public void deleteAll(Iterable<? extends ContentRedirect> e) { }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { }
        @Override public List<ContentRedirect> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public void flush() { }
        @Override public <S extends ContentRedirect> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends ContentRedirect> List<S> saveAllAndFlush(Iterable<S> e) { return saveAll(e); }
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public void deleteAllInBatch(Iterable<ContentRedirect> e) { }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { }
        @Override public ContentRedirect getOne(Long id) { return null; }
        @Override public ContentRedirect getById(Long id) { return null; }
        @Override public ContentRedirect getReferenceById(Long id) { return null; }
        @Override public List<ContentRedirect> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<ContentRedirect> findAll(org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends ContentRedirect> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { return Optional.empty(); }
        @Override public <S extends ContentRedirect> List<S> findAll(org.springframework.data.domain.Example<S> ex) { return List.of(); }
        @Override public <S extends ContentRedirect> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { return List.of(); }
        @Override public <S extends ContentRedirect> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends ContentRedirect> long count(org.springframework.data.domain.Example<S> ex) { return 0; }
        @Override public <S extends ContentRedirect> boolean exists(org.springframework.data.domain.Example<S> ex) { return false; }
        @Override public <S extends ContentRedirect, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { return null; }
    }
}
