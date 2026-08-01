package com.kashi.grc.ucf.dto;

import com.kashi.grc.ucf.domain.CommonControl;
import com.kashi.grc.ucf.domain.CommonControlMapping;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTOs for the common control catalogue.
 * Grouped in one file — they are small and only meaningful together.
 */
public class CommonControlDtos {

    /** Single node, optionally with children. Drives the catalogue tree. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NodeResponse {
        private Long    id;
        private String  code;
        private String  parentCode;
        private String  nodeLevel;
        private String  domainCode;
        private String  title;
        private String  description;
        private String  legacyTag;
        private Integer sortOrder;
        private Boolean active;
        private String  source;

        /** Populated on tree responses only. */
        private List<NodeResponse> children;

        /** How many library controls / tests point at this node. */
        private Long libraryControls;
        private Long libraryTests;

        /** Distinct frameworks reachable through this node. */
        private Integer frameworkCount;
    }

    /**
     * Flat option for the tag picker in the audit library control form.
     * Deliberately lean — this endpoint is called on every keystroke.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PickerOption {
        private String code;
        private String title;
        private String domainCode;
        /** 'IAM-02 Authentication' — shown as secondary text in the dropdown. */
        private String familyLabel;
        /** Old tag this replaces, so people searching 'MFA_ADMIN' still find it. */
        private String legacyTag;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MappingResponse {
        private Long    id;
        private String  commonControlCode;
        private String  frameworkRef;
        private String  citation;
        private String  citationTitle;
        private String  relationship;
        private Boolean fullySatisfies;
        private String  notes;
        private String  source;
    }

    /** Full detail for the catalogue admin panel. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DetailResponse {
        private NodeResponse           node;
        private List<NodeResponse>     ancestry;   // leaf first, domain last
        private List<MappingResponse>  mappings;
        private List<LibraryUsage>     usedBy;
    }

    /** A library control or test pointing at this common control. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LibraryUsage {
        private String entityType;   // AUDIT_CONTROL | AUDIT_TEST
        private Long   entityId;
        private String frameworkRef;
        private String controlCode;
        private String name;
    }

    // ── Requests ────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NodeRequest {
        @NotBlank private String  code;
        private String  parentCode;
        @NotNull  private CommonControl.NodeLevel nodeLevel;
        @NotBlank private String  domainCode;
        @NotBlank private String  title;
        private String  description;
        private String  legacyTag;
        private Integer sortOrder;
        private Boolean active;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MappingRequest {
        @NotBlank private String commonControlCode;
        @NotBlank private String frameworkRef;
        @NotBlank private String citation;
        private String citationTitle;
        @NotNull  private CommonControlMapping.Relationship relationship;
        private String notes;
    }
}