package com.kashi.grc.common.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts a column at rest with AES-256-GCM.
 *
 * ── WHAT THIS IS FOR ──────────────────────────────────────────────────────────
 *
 * integration_configs.auth_config holds the credential a customer hands us for
 * their Okta, AWS, GitHub, Azure or Google Workspace tenant. The entity's own
 * javadoc has said "AES-256 encrypted at the application layer before
 * persisting" since it was written, and IntegrationController carries
 * `// TODO: encrypt` twice. It was stored in clear.
 *
 * ── WHAT IT PROTECTS, AND WHAT IT DOES NOT ────────────────────────────────────
 *
 * The key lives in the environment, the ciphertext in the database. So this
 * protects a database dump, a backup file, a stolen disk, a read-only replica
 * and anyone who gets a SELECT but not a shell — which is most of the ways a
 * credential table actually leaks.
 *
 * It does NOT protect against someone who already has the running server's
 * environment: they have the key. A managed secrets store with per-request
 * fetch is the answer to that, and this is deliberately not pretending to be
 * one. Worth saying plainly rather than letting the word "encrypted" imply
 * more than it buys.
 *
 * ── AES-GCM, NOT CBC ──────────────────────────────────────────────────────────
 *
 * GCM authenticates as well as encrypts: a tampered ciphertext fails to
 * decrypt rather than returning plausible rubbish. A fresh 12-byte IV per
 * write, prepended to the ciphertext — reusing an IV under one key is the one
 * mistake that breaks GCM outright, so it is generated per call from
 * SecureRandom and never derived from the row.
 *
 * ── MIGRATING WITHOUT A FLAG DAY ──────────────────────────────────────────────
 *
 * Existing rows are plaintext. Rather than a migration script that would need
 * the key in two places, the read path detects:
 *
 *   starts with "enc:v1:"  → decrypt
 *   anything else          → hand back as-is, it predates this
 *
 * The write path always encrypts, so a row upgrades the next time anything
 * saves it, and a customer never re-enters a credential. The prefix carries a
 * version so a future key rotation or algorithm change can tell its own
 * ciphertext from this one.
 *
 * ── NO KEY CONFIGURED ─────────────────────────────────────────────────────────
 *
 * Passes through in both directions and says so, loudly, once at startup and
 * once per write. Failing to boot would be the safer default for production
 * and the wrong one for a developer who has just pulled the branch — and a
 * silent pass-through would be worse than either, which is why it is noisy.
 *
 * Set it in the environment beside the database password:
 *
 *   kashi.crypto.secret-key=<44-character base64 of 32 random bytes>
 *
 * Generate one with:
 *
 *   openssl rand -base64 32
 *
 * Losing the key loses the credentials — they cannot be recovered, only
 * re-entered. Keep it wherever the database password already lives.
 *
 * ── APPLIED EXPLICITLY ────────────────────────────────────────────────────────
 *
 * autoApply = false, deliberately. An auto-applied String converter would
 * encrypt every String column in the application, which is both enormous and
 * wrong. Columns opt in with @Convert, one at a time, and today exactly one
 * does: IntegrationConfig.authConfig.
 */
@Slf4j
@Component
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    /** Marks our own ciphertext, and says which scheme wrote it. */
    private static final String PREFIX  = "enc:v1:";
    private static final String ALGO    = "AES";
    private static final String CIPHER  = "AES/GCM/NoPadding";
    private static final int    IV_LEN  = 12;   // 96 bits — the size GCM is specified for
    private static final int    TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public EncryptedStringConverter(
            @Value("${kashi.crypto.secret-key:}") String configuredKey) {
        SecretKeySpec k = null;
        if (configuredKey != null && !configuredKey.isBlank()) {
            try {
                byte[] raw = Base64.getDecoder().decode(configuredKey.trim());
                if (raw.length != 32) {
                    log.error("[CRYPTO] kashi.crypto.secret-key decodes to {} bytes, not 32. "
                            + "Generate one with: openssl rand -base64 32. "
                            + "Credentials will be stored IN CLEAR until this is fixed.", raw.length);
                } else {
                    k = new SecretKeySpec(raw, ALGO);
                    log.info("[CRYPTO] AES-256-GCM active for encrypted columns");
                }
            } catch (IllegalArgumentException e) {
                log.error("[CRYPTO] kashi.crypto.secret-key is not valid base64. "
                        + "Credentials will be stored IN CLEAR until this is fixed.");
            }
        } else {
            log.warn("[CRYPTO] kashi.crypto.secret-key is not set — integration credentials will be "
                    + "stored and read IN CLEAR. Fine for local work; set it before production. "
                    + "Generate one with: openssl rand -base64 32");
        }
        this.key = k;
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        if (key == null) {
            log.warn("[CRYPTO] Storing a value in clear — no kashi.crypto.secret-key configured");
            return plain;
        }
        // Already ours: re-encrypting would double-wrap. Can happen when an
        // entity is read and written back without the field being touched.
        if (plain.startsWith(PREFIX)) return plain;

        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);

            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv,   0, out, 0,         iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);

            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Never store the plaintext as a fallback — that is the failure this
            // class exists to prevent, and it would be invisible.
            log.error("[CRYPTO] Could not encrypt a value — refusing to store it in clear | {}", e.toString());
            throw new IllegalStateException("Could not encrypt a value for storage", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || stored.isEmpty()) return stored;

        // Written before this converter existed. Hand it back as it is; the
        // next save re-writes it encrypted.
        if (!stored.startsWith(PREFIX)) return stored;

        if (key == null) {
            log.error("[CRYPTO] Found an encrypted value but no kashi.crypto.secret-key is set — "
                    + "cannot read it. The key that wrote this row has to be restored.");
            return null;
        }

        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv  = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);

            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = c.doFinal(all, IV_LEN, all.length - IV_LEN);

            return new String(body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Wrong key, or a tampered row — GCM's authentication tag catches
            // both. Null rather than a throw: one unreadable credential should
            // make that one integration fail to run, not break the page that
            // lists every integration.
            log.error("[CRYPTO] Could not decrypt a stored value — wrong key, or the row was altered | {}",
                    e.toString());
            return null;
        }
    }
}