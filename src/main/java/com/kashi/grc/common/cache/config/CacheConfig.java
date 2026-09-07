package com.kashi.grc.common.cache.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.cache.CacheNames;
import com.kashi.grc.common.cache.LoggingCacheErrorHandler;
import com.kashi.grc.common.cache.RedisCircuitBreaker;
import com.kashi.grc.common.cache.ResilientCacheManager;
import com.kashi.grc.common.cache.TenantAwareKeyGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis cache foundation for KashiGRC.
 *
 * Gated behind kashi.redis.enabled so environments without a Redis instance
 * start normally with caching switched off — every {@code @Cacheable}
 * effectively no-ops back to the underlying method (Spring's NoOpCacheManager
 * auto-configures when no CacheManager bean is present and caching isn't
 * otherwise configured). Same "flip a flag, zero blast radius" contract as
 * KafkaConfig.
 *
 * Design decisions:
 *  - JSON serde via the Spring-managed ObjectMapper (JavaTimeModule already
 *    registered) — same rule as Kafka's JsonSerializer, never a bare one.
 *  - Default key generator = TenantAwareKeyGenerator: every @Cacheable in the
 *    app is tenant-scoped by default unless it explicitly opts out (e.g. the
 *    global UCF catalogue), closing off the single most dangerous failure
 *    mode for a shared cache in a multi-tenant app.
 *  - Error handler = LoggingCacheErrorHandler: a Redis outage degrades to
 *    "caching didn't happen", never to a 500. Never let the accelerator
 *    become a single point of failure for the whole app.
 *  - Per-cache TTL map: reference/config data that only changes via admin
 *    action gets a short TTL (5 min) as a safety net, with @CacheEvict at
 *    the write side (see UiAdminController) for immediate correctness —
 *    the TTL is a backstop against a missed eviction, not the primary
 *    invalidation mechanism.
 *  - The bean actually registered as "cacheManager" (what @Cacheable resolves
 *    against) is ResilientCacheManager, not the raw RedisCacheManager built
 *    here — it wraps every cache region with circuit breaking, stampede
 *    protection, and metrics. See ResilientRedisCache for why each of those
 *    exists; nothing about that wrapping requires touching call sites.
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.redis.enabled", havingValue = "true")
public class CacheConfig implements CachingConfigurer {

    private final ObjectMapper objectMapper;
    private final TenantAwareKeyGenerator tenantAwareKeyGenerator;
    private final LoggingCacheErrorHandler loggingCacheErrorHandler;

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    // ── Connection ──────────────────────────────────────────────────

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isBlank()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }

    // ── Cache manager ───────────────────────────────────────────────

    // Named redisCacheManager (not "cacheManager") deliberately — this is the
    // raw, undecorated manager. The bean Spring's @Cacheable machinery
    // actually resolves against is cacheManager() below, which wraps this one.
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        // A dedicated COPY of the shared ObjectMapper, not the shared bean
        // itself. GenericJackson2JsonRedisSerializer needs
        // activateDefaultTyping() to embed @class metadata in the cached
        // JSON — without it, Jackson has no way to know a cached value
        // should deserialize back into (say) UiFormResponse instead of a
        // generic LinkedHashMap, which is exactly the
        // "ClassCastException: LinkedHashMap cannot be cast to
        // UiFormResponse" seen in production on getForm(). Enabling default
        // typing on the SHARED objectMapper bean instead would fix caching
        // but silently embed @class fields in every REST response the app
        // sends to the frontend — a much worse, app-wide side effect for a
        // Redis-only concern. .copy() keeps JavaTimeModule and every other
        // registration already on the shared mapper; only this Redis-only
        // copy gets typing activated.
        ObjectMapper redisObjectMapper = objectMapper.copy();
        com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator ptv =
                com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType(Object.class) // trusted — this cache only ever holds our own DTOs
                        .build();
        redisObjectMapper.activateDefaultTyping(
                ptv,
                com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL,
                // WRAPPER_ARRAY, not PROPERTY.
                //
                // As.PROPERTY writes the type as a "@class" field INSIDE the JSON
                // object — which is impossible for a root-level List, because a
                // JSON array has nowhere to put a property. Every cache whose
                // value is a collection therefore round-tripped asymmetrically:
                // written as [{"@class":...}], read by a reader demanding a type
                // id as the FIRST ARRAY ELEMENT. Hence, on every single read:
                //
                //   expected VALUE_STRING: need String, Number of Boolean value
                //   that contains type id ... line: 1, column: 2
                //
                // Column 2 is the character right after the opening bracket, which
                // is what made this look like a data problem rather than a shape
                // one. It is not the values — it is the root.
                //
                // WRAPPER_ARRAY writes ["java.util.ArrayList",[...]] uniformly for
                // lists, maps and POJOs alike, which is what the reader expects and
                // why it is the conventional choice for a Redis value serializer.
                //
                // Evidence this was the cause: the ONLY keys that survived in Redis
                // were the two caches switched to an untyped serializer. No typed
                // cache had ever stored a readable entry.
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.WRAPPER_ARRAY);

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper);

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(StringRedisSerializer.UTF_8))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer))
                .prefixCacheNameWith("kashigrc:");

        // Per-cache TTL overrides — see CacheNames for what each region holds
        // and why. Anything not listed here falls back to the 5-min default.
        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put(CacheNames.UI_FORM,      defaults.entryTtl(Duration.ofMinutes(5)));
        perCache.put(CacheNames.UI_SCREEN,    defaults.entryTtl(Duration.ofMinutes(5)));
        perCache.put(CacheNames.UI_ACTIONS,   defaults.entryTtl(Duration.ofMinutes(5)));
        perCache.put(CacheNames.UI_DASHBOARD, defaults.entryTtl(Duration.ofMinutes(5)));
        // User display names change rarely (profile edits) and are read on
        // every history/assignment screen — longer TTL is safe.
        perCache.put(CacheNames.USER_DISPLAY_NAME, defaults.entryTtl(Duration.ofMinutes(15)));
        // Global catalogue, edited only from the UCF admin screens.
        perCache.put(CacheNames.UCF_CATALOGUE, defaults.entryTtl(Duration.ofMinutes(5)));
        // Entitlements should feel immediate to an admin who just changed a
        // plan — short TTL + explicit evict on write (see TenantFeatureService).
        perCache.put(CacheNames.TENANT_ENTITLEMENTS, defaults.entryTtl(Duration.ofMinutes(10)));
        // Compound "template structure" snapshot (see AssessmentTemplateStructureCacheService)
        // — read on every instantiation, changes only when an admin edits a
        // template in the library. Longer TTL is safe; explicit evict on write.
        perCache.put(CacheNames.ASSESSMENT_TEMPLATE_STRUCTURE, defaults.entryTtl(Duration.ofMinutes(30)));

        // Templates/workflows change only via rare admin actions (publish,
        // edit blueprint) but this dropdown is re-queried on every "New
        // engagement" modal open — same reasoning as USER_DISPLAY_NAME.
        perCache.put(CacheNames.AUDIT_TEMPLATE_LIST,     defaults.entryTtl(Duration.ofMinutes(15)));
        perCache.put(CacheNames.WORKFLOW_BLUEPRINT_LIST, defaults.entryTtl(Duration.ofMinutes(15)));

        // Library lists change only when someone edits the library, and every
        // mutating endpoint evicts explicitly, so the TTL is a backstop rather
        // than the mechanism. 10 minutes bounds the damage if an eviction path
        // is ever missed.
        // ── These two caches use a serializer WITHOUT default typing ────────
        //
        // Every read of them failed with:
        //   SerializationException: Unexpected token (START_OBJECT), expected
        //   VALUE_STRING ... that contains type id (for subtype of java.lang.Object)
        //
        // The shared serializer runs activateDefaultTyping(NON_FINAL, As.PROPERTY).
        // A JSON array cannot carry a type PROPERTY, so the root List falls back
        // to wrapper-array form and the reader then demands a type-id string as
        // the first element. The stored value did not have one, so it failed at
        // line 1, column 2 — every time, on every read.
        //
        // Typing exists so an Object-typed cache value can be reconstructed as
        // its original class. These two caches do not need that: they hold
        // List<Map<String,Object>> of plain scalars, and plain Jackson
        // reconstructs exactly that — ArrayList of LinkedHashMap — with no type
        // information at all.
        //
        // I tried ISO-formatting the dates first, on the theory that the temporal
        // values were the problem. They were not; the failure is structural and
        // at the root, before any value is read. (The ISO change is still worth
        // keeping — it makes the cached JSON smaller and matches the response.)
        //
        // Old entries written by the previous serializer will fail once more,
        // be swallowed as a miss by ResilientRedisCache, and be replaced. No
        // flush required, though flushing makes the first read faster.
        // objectMapper.copy(), NOT a bare new ObjectMapper(): the same reasoning
        // as redisObjectMapper above — it inherits JavaTimeModule and every other
        // registration Spring has applied. The only difference is that typing is
        // never activated on this one.
        ObjectMapper plainMapper = objectMapper.copy();

        RedisCacheConfiguration untypedListConfig = defaults
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(plainMapper)));

        perCache.put(CacheNames.AUDIT_POLICY_LIST, untypedListConfig);
        perCache.put(CacheNames.AUDIT_TEST_LIST,   untypedListConfig);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    /** Plain string Redis access for the stampede-lock (SETNX/Lua unlock) in ResilientRedisCache. */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @Primary
    public CacheManager cacheManager(RedisCacheManager redisCacheManager,
                                     RedisCircuitBreaker circuitBreaker,
                                     StringRedisTemplate stringRedisTemplate,
                                     MeterRegistry meterRegistry) {
        return new ResilientCacheManager(redisCacheManager, circuitBreaker, stringRedisTemplate, meterRegistry);
    }

    // ── Wiring for @EnableCaching (CachingConfigurer) ────────────────

    @Override
    public KeyGenerator keyGenerator() {
        return tenantAwareKeyGenerator;
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return loggingCacheErrorHandler;
    }
}