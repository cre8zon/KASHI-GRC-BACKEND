package com.kashi.grc.collab.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * A programme of work inside a workspace — "FY27 ISO 27001", "Subsidiary B
 * SOC 2", "Q1 pen test". Each programme has its own plan (one per spreadsheet
 * timeline the parties keep today).
 *
 * Visibility: the client's people see every programme in the workspace; the
 * firm's people see only programmes they are members of
 * (collab_programme_members).
 */
@Entity
@Table(name = "collab_programmes",
        indexes = @Index(name = "idx_collab_prog_ws", columnList = "workspace_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabProgramme extends AuditableEntity {

    public static final String PLANNED   = "PLANNED";
    public static final String ACTIVE    = "ACTIVE";
    public static final String COMPLETED = "COMPLETED";
    public static final String ARCHIVED  = "ARCHIVED";

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "planned_start")
    private LocalDate plannedStart;

    @Column(name = "planned_end")
    private LocalDate plannedEnd;

    /** PLANNED | ACTIVE | COMPLETED | ARCHIVED */
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = PLANNED;
}
