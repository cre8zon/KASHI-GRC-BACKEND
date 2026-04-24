package com.kashi.grc.usermanagement.seed;

import com.kashi.grc.usermanagement.domain.*;
import com.kashi.grc.usermanagement.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository       userRepository;
    private final RoleRepository       roleRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder      passwordEncoder;
    private final JdbcTemplate         jdbcTemplate;

    private static final Long SYSTEM_TENANT_ID = 1L;
    private static final Long SYSTEM_MODULE_ID = 1L;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("DataInitializer running — ensuring base data exists...");

        // ── 1. System tenant ──────────────────────────────────────────
        jdbcTemplate.execute("""
            INSERT INTO tenants (id, name, code, description, status)
            VALUES (1, 'Kashi System Tenant', 'KASHI_SYS', 'Root system tenant', 'ACTIVE')
            ON DUPLICATE KEY UPDATE name = name
        """);

        // ── 2. System module ──────────────────────────────────────────
        jdbcTemplate.execute("""
            INSERT INTO modules (id, code, name)
            VALUES (1, 'SYS', 'System Management')
            ON DUPLICATE KEY UPDATE name = name
        """);

        // ── 3. Permissions — idempotent ───────────────────────────────
        Permission readAll       = createPermission("system:read",          "Read All");
        Permission writeAll      = createPermission("system:write",         "Write All");
        Permission manageTenants = createPermission("system:manageTenants", "Manage Tenants");

        // ── 4. Platform Admin role — idempotent ───────────────────────
        Role adminRole = roleRepository.findByNameAndTenantId("PLATFORM_ADMIN", SYSTEM_TENANT_ID)
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setName("PLATFORM_ADMIN");
                    r.setSide(RoleSide.SYSTEM);
                    r.setLevel(RoleLevel.L1);
                    r.setTenantId(SYSTEM_TENANT_ID);
                    r.setPermissions(new HashSet<>());
                    roleRepository.save(r);
                    roleRepository.flush();
                    log.info("Created PLATFORM_ADMIN role.");
                    return r;
                });

        // ── 5. Role → Permission links — idempotent ───────────────────
        for (Permission p : Set.of(readAll, writeAll, manageTenants)) {
            jdbcTemplate.update(
                    "INSERT IGNORE INTO role_permissions (role_id, permission_id, tenant_id) VALUES (?, ?, ?)",
                    adminRole.getId(), p.getId(), SYSTEM_TENANT_ID
            );
        }

        // ── 6. Admin user — idempotent ────────────────────────────────
        if (!userRepository.existsByEmail("admin@kashigrc.com")) {
            User admin = new User();
            admin.setEmail("admin@kashigrc.com");
            admin.setFirstName("Kashi");
            admin.setLastName("Admin");
            admin.setPasswordHash(passwordEncoder.encode("Admin@123"));
            admin.setStatus(UserStatus.ACTIVE);
            admin.setTenantId(SYSTEM_TENANT_ID);   // tenantId = 1; isSystemUser() detects via RoleSide
            admin.setRoles(new HashSet<>(Set.of(adminRole)));
            admin.setPasswordResetRequired(false);
            admin.setLastLogin(LocalDateTime.now());
            userRepository.save(admin);
            log.info("Seed complete — admin@kashigrc.com created with PLATFORM_ADMIN role.");
        } else {
            // User already exists — make sure the PLATFORM_ADMIN role is attached.
            // This handles the case where the app was run before the role was seeded.
            userRepository.findByEmailAndIsDeletedFalse("admin@kashigrc.com").ifPresent(admin -> {
                boolean hasRole = admin.getRoles().stream()
                        .anyMatch(r -> "PLATFORM_ADMIN".equals(r.getName()));
                if (!hasRole) {
                    admin.getRoles().add(adminRole);
                    userRepository.save(admin);
                    log.info("Attached PLATFORM_ADMIN role to existing admin user.");
                } else {
                    log.info("Admin user already exists with correct role — nothing to do.");
                }
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private Permission createPermission(String code, String name) {
        return permissionRepository.findByCode(code).orElseGet(() -> {
            Permission p = new Permission();
            p.setModuleId(SYSTEM_MODULE_ID);
            p.setCode(code);
            p.setName(name);
            return permissionRepository.save(p);
        });
    }
}