package com.kashi.grc.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Put the timezone back on every timestamp this API sends.
 *
 * ── THE BUG THIS FIXES ────────────────────────────────────────────────────
 *
 * Every timestamp in the product is a LocalDateTime — created_at, message
 * times, meeting times, 281 files' worth. LocalDateTime has no zone, so Jackson
 * writes it as a bare string:
 *
 *     "2026-10-08T06:22:12.860474"
 *
 * The browser then does new Date(...) on it, and the ECMAScript rule for a
 * date-time with no offset is to read it as LOCAL. So a message written at
 * 06:22 UTC is displayed as 06:22 IST — 5 hours 30 minutes early, everywhere in
 * the app at once. That is the reported "actual time does not show".
 *
 * With the offset attached:
 *
 *     "2026-10-08T06:22:12.860474Z"
 *
 * new Date() resolves a real instant and every existing toLocaleTimeString()
 * call in the frontend starts printing the viewer's own local time. No frontend
 * change is needed for any of it, and no per-user timezone setting either — the
 * browser already knows where it is. A setting would have sat on top of this
 * and still been wrong.
 *
 * ── WHY THIS IS NOT "ASSUME UTC" ──────────────────────────────────────────
 *
 * The zone is read from the JVM rather than hardcoded, which makes it correct
 * by construction instead of by luck: these values were WRITTEN by
 * LocalDateTime.now(), which uses the JVM's default zone, so stamping that same
 * zone's offset on the way out can only reproduce the instant that was meant.
 * It stays right if this is deployed somewhere that is not UTC.
 *
 * The one thing that would break it is the JVM zone CHANGING between writing a
 * row and reading it — a redeploy to another region would silently reinterpret
 * every historical timestamp. Pin it so that cannot happen, with either:
 *
 *     app.time-zone=UTC                       (this property)
 *     -Duser.timezone=UTC                     (JVM flag)
 *     TZ=UTC                                  (container env)
 *
 * The resolved zone is logged at startup — check it says what you expect.
 *
 * ── WHY READING HAD TO CHANGE TOO ─────────────────────────────────────────
 *
 * Writing an offset without being able to read one back would have broken the
 * Redis cache. CacheConfig builds its serializer from objectMapper.copy(), so
 * the cached copy of a DTO would go in as "...Z" and come back out through
 * Jackson's stock LocalDateTimeDeserializer, which REFUSES a trailing offset —
 * "unparsed text found at index 19". Every cached response carrying a timestamp
 * would have started failing on read.
 *
 * So the deserialiser is the mirror of the serialiser: an offset-bearing string
 * is converted into this server's wall clock, and a bare one is parsed exactly
 * as before. That also means the API now accepts a real instant from a client,
 * which is what the meeting form sends.
 *
 * LocalDate is untouched: a date has no instant, and "2026-10-08" is the same
 * day everywhere.
 */
@Slf4j
@Configuration
public class JacksonTimeConfig {

    /** Blank = follow the JVM. Set it to pin the zone explicitly, e.g. UTC. */
    @Value("${app.time-zone:}")
    private String configuredZone;

    private ZoneId zone() {
        if (configuredZone == null || configuredZone.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(configuredZone.trim());
        } catch (RuntimeException e) {
            log.warn("[TIME] app.time-zone '{}' is not a valid zone id — falling back to the JVM default",
                    configuredZone);
            return ZoneId.systemDefault();
        }
    }

    @PostConstruct
    void announce() {
        ZoneId z = zone();
        log.info("[TIME] Timestamps are serialised with the offset of {} (now {}). "
                        + "If that is not the zone this server stores times in, set app.time-zone.",
                z, LocalDateTime.now().atZone(z).getOffset());
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCarriesItsOffset() {
        final ZoneId zone = zone();
        return builder -> builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                if (value == null) {
                    gen.writeNull();
                    return;
                }
                // atZone, not atOffset: a fixed offset would be wrong on either
                // side of a daylight-saving change for any zone that has one.
                gen.writeString(value.atZone(zone).toOffsetDateTime().toString());
            }
        });
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeAcceptsAnOffset() {
        final ZoneId zone = zone();
        return builder -> builder.deserializerByType(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String s = p.getValueAsString();
                if (s == null || s.isBlank()) return null;
                s = s.trim();
                // Carries an offset: a real moment. Convert it to this server's
                // wall clock, which is what the column stores.
                if (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:\\d{2}$")) {
                    return OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime();
                }
                // No offset: exactly the old behaviour, so anything still
                // posting a bare wall clock is unaffected.
                if (s.length() == 16) s = s + ":00";
                return LocalDateTime.parse(s);
            }
        });
    }
}