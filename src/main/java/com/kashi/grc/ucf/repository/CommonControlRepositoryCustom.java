package com.kashi.grc.ucf.repository;

import com.kashi.grc.ucf.domain.CommonControl;

import java.util.List;

public interface CommonControlRepositoryCustom {

    /**
     * Tag picker lookup. Leaf-level entries only, optionally narrowed by a free
     * text fragment (code, title or legacy tag) and/or a domain.
     *
     * This is the query that replaces free-text control_tag entry in the audit
     * library, so it is the most load-bearing endpoint in this package — it is
     * what stops tag drift re-forming after the reconciliation.
     */
    List<CommonControl> searchSelectable(String query, String domainCode, int limit);

    /**
     * Walk up the tree from a leaf: the node itself, then each ancestor.
     * Phase 3 uses this to build matched_tags_snapshot at instantiation.
     */
    List<CommonControl> findAncestryChain(String code);
}