package com.kashi.grc.billing.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The priced limit, enforced.
 *
 * tenants.max_vendors and max_users have existed since the start and nothing
 * reads them — a tenant on a 50-vendor plan can add 5,000 today. This service
 * is the check; it is NOT yet called from the vendor or user modules (this
 * change leaves those modules untouched). Wiring is one line at each create
 * path:
 *
 *   tenantLimitService.assertCanAddVendor(tenantId);   // VendorService.create
 *   tenantLimitService.assertCanAddUser(tenantId);     // UserService.create (org side)
 *
 * NULL limit = unlimited. Seats are unlimited on the recommended plans, so the
 * user check only bites on a plan that deliberately sells seats.
 */
@Service
@RequiredArgsConstructor
public class TenantLimitService {

    private final TenantRepository tenantRepository;

    @PersistenceContext
    private EntityManager em;

    public void assertCanAddVendor(Long tenantId) {
        Tenant t = tenantRepository.findById(tenantId).orElse(null);
        if (t == null || t.getMaxVendors() == null) return;
        long n = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM vendors WHERE tenant_id = :t AND is_deleted = false")
                .setParameter("t", tenantId).getSingleResult()).longValue();
        if (n >= t.getMaxVendors()) {
            throw new BusinessException("VENDOR_LIMIT_REACHED",
                    "Your plan includes " + t.getMaxVendors() + " vendors under management and all are in use. "
                            + "Contact us to add more.", HttpStatus.PAYMENT_REQUIRED);
        }
    }

    public void assertCanAddUser(Long tenantId) {
        Tenant t = tenantRepository.findById(tenantId).orElse(null);
        if (t == null || t.getMaxUsers() == null) return;
        long n = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM users WHERE tenant_id = :t AND is_deleted = false AND vendor_id IS NULL")
                .setParameter("t", tenantId).getSingleResult()).longValue();
        if (n >= t.getMaxUsers()) {
            throw new BusinessException("SEAT_LIMIT_REACHED",
                    "Your plan includes " + t.getMaxUsers() + " seats and all are in use.", HttpStatus.PAYMENT_REQUIRED);
        }
    }
}
