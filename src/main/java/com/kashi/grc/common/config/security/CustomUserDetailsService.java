package com.kashi.grc.common.config.security;

import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Loads user by ID (stored as subject in JWT) or email.
 * Bridges Spring Security with our User domain object.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /** Called by JwtAuthenticationFilter using the JWT subject (user ID) */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String userIdOrEmail) throws UsernameNotFoundException {
        User user;
        try {
            Long userId = Long.parseLong(userIdOrEmail);

            // Cached path — this method runs on every authenticated request, so the
            // cheapest version of it is the one that does not touch the DB at all.
            UserDetails cached = AUTH_CACHE.getIfPresent(userId);
            if (cached != null) return cached;

            // findWithRolesAndPermissionsById, not findById: the entity graph pulls
            // roles and permissions in ONE query instead of one lazy select per role.
            user = userRepository.findWithRolesAndPermissionsById(userId)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));
        } catch (NumberFormatException e) {
            user = userRepository.findByEmailAndIsDeletedFalse(userIdOrEmail)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userIdOrEmail));
        }

        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(perm -> new SimpleGrantedAuthority(perm.getCode()))
                .distinct()
                .collect(Collectors.toList());

        // Also add role names as authorities (ROLE_xxx convention)
        user.getRoles().forEach(role ->
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName().toUpperCase().replace(" ", "_")))
        );

        // Add the role SIDE as an authority (SIDE_SYSTEM, SIDE_ORGANIZATION, …).
        // This lets SecurityConfig gate the platform-admin URL space to
        // SIDE_SYSTEM only — a hard boundary a customer's JWT can never satisfy,
        // enforced at the filter layer before any controller runs.
        user.getRoles().stream()
                .map(role -> role.getSide())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(side ->
                        authorities.add(new SimpleGrantedAuthority("SIDE_" + side.name())));

        UserDetails details = org.springframework.security.core.userdetails.User.builder()
                .username(String.valueOf(user.getId()))
                .password(user.getPasswordHash())
                .authorities(authorities)
                .accountLocked(user.isLocked())
                .disabled(!user.isActive() && !user.isLocked())
                .build();

        AUTH_CACHE.put(user.getId(), details);
        return details;
    }

    /**
     * Short-lived cache of the built UserDetails, keyed by user id.
     *
     * WHY: authorities change only when roles or permissions are edited, which is
     * rare, while this method runs on every single authenticated request. 60s is
     * short enough that a permission change takes effect almost immediately and
     * long enough to remove the DB from the hot path entirely.
     *
     * Call invalidate(userId) — or invalidateAll() — from role/permission admin
     * code if you need a change to apply instantly rather than within the minute.
     * Lock and enabled state are also cached, so a user disabled mid-session keeps
     * access for up to 60s; drop expireAfterWrite if that matters more than the
     * latency.
     */
    private static final com.github.benmanes.caffeine.cache.Cache<Long, UserDetails> AUTH_CACHE =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(java.time.Duration.ofSeconds(60))
                    .maximumSize(10_000)
                    .build();

    /** Drop one user's cached authorities — call after a role or permission change. */
    public static void invalidate(Long userId) {
        if (userId != null) AUTH_CACHE.invalidate(userId);
    }

    /** Drop every cached entry — call after a bulk role/permission migration. */
    public static void invalidateAll() {
        AUTH_CACHE.invalidateAll();
    }
}