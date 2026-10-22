package com.kashi.grc.usermanagement.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A refresh token that may no longer be exchanged, by its jti.
 *
 * JWTs are stateless, so "signing out" used to mean only that the browser
 * forgot the token — a copied refresh token stayed usable for its full 7 days.
 * Logout and every refresh now record the spent token's jti here; the refresh
 * endpoint refuses any jti in this table.
 *
 * Rows are only needed until the token would have expired on its own, so
 * expiresAt drives the nightly purge (RevokedTokenPurgeJob).
 */
@Entity
@Table(name = "revoked_tokens",
        indexes = {
                @Index(name = "idx_revoked_tokens_expires", columnList = "expires_at"),
                @Index(name = "idx_revoked_tokens_user",    columnList = "user_id")
        })
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class RevokedToken {

    @Id
    @Column(name = "jti", length = 64, nullable = false)
    private String jti;

    @Column(name = "user_id")
    private Long userId;

    /** When the token itself expires — after this the row is purgeable. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at", nullable = false)
    private LocalDateTime revokedAt;

    /** LOGOUT | ROTATED */
    @Column(name = "reason", length = 20)
    private String reason;
}
