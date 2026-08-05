package com.kashi.grc.common.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Optional;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final CustomUserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/",
                                "/v1/auth/login",
                                "/v1/auth/request-password-reset",
                                "/v1/auth/reset-password",
                                "/v1/users/password",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/actuator/health",
                                "/v1/health",
                                "/ws/**",
                                "/v1/issues/ingest"
                        ).permitAll()
                        // ── PLATFORM-ADMIN BOUNDARY ────────────────────────────
                        // These URL spaces configure the platform itself and must
                        // NEVER be reachable by a customer/organization user, even
                        // though they are authenticated. Gated to SIDE_SYSTEM (a
                        // role with RoleSide.SYSTEM), enforced here at the filter
                        // layer — before any controller — so a new admin endpoint
                        // is covered by the URL pattern automatically and cannot be
                        // forgotten. This is the real gate; the frontend guard is
                        // only UX.
                        // Tenant-READ exception — the ONLY blueprint endpoint open to
                        // non-System users. The Universal Module Page renders an org
                        // user's screen by reading its blueprint via by-type (result is
                        // tenant-scoped: the caller's blueprint or the global default).
                        // Everything else on module-blueprints — the list, by-id, and
                        // all writes — stays System-only via the /v1/admin/** lock
                        // below. Configuring blueprints remains System-only; only
                        // rendering is opened. Placed BEFORE the lock (first match wins).
                        .requestMatchers(HttpMethod.GET,
                                "/v1/admin/module-blueprints/by-type/**"
                        ).authenticated()
                        // ── ROLE SELF-SERVICE EXCEPTION (matched before the boundary below) ──
                        // These RoleController endpoints are nested under
                        // /v1/tenants/{tenantId}/... for REST-friendliness, but that
                        // prefix is also used by genuinely platform-admin-only tenant
                        // management (TenantController) locked below. Without this
                        // exception, EVERY org/vendor/auditee/auditor user gets a
                        // silent 403 just reading the assignable-roles list for their
                        // own tenant — the role data and the Criteria query were both
                        // correct all along; the request never reached the controller.
                        // Cross-tenant writes are still enforced at the service layer
                        // (RoleServiceImpl.createRoleForTenant / deleteRole).
                        .requestMatchers(HttpMethod.GET,    "/v1/tenants/*/roles/hierarchy").authenticated()
                        .requestMatchers(HttpMethod.POST,   "/v1/tenants/*/roles").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/v1/tenants/*/roles/*").authenticated()
                        // ── PLATFORM-ADMIN BOUNDARY (writes + all other admin) ──────
                        .requestMatchers(
                                "/v1/admin/**",
                                "/v1/tenants/**"
                        ).hasAuthority("SIDE_SYSTEM")
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("https://app.digiosec.com", "http://localhost:3000", "http://localhost:58343"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuditorAware<Long> auditorProvider() {
        return () -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return Optional.empty();
            Object principal = auth.getPrincipal();
            if (principal instanceof org.springframework.security.core.userdetails.UserDetails ud) {
                try { return Optional.of(Long.parseLong(ud.getUsername())); }
                catch (NumberFormatException ignored) {}
            }
            return Optional.empty();
        };
    }
}