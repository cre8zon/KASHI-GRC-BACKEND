package com.kashi.grc.tenant.service;

/**
 * Published by TenantProvisioningService inside the provisioning transaction,
 * after the tenant, its owner and its first subscription exist.
 *
 * Listeners that use a plain @EventListener run in the same transaction, so
 * their writes commit or roll back with the tenant — the enquiry module uses
 * this to mark the source enquiry converted without the tenant module knowing
 * enquiries exist.
 *
 * @param enquiryId the website enquiry this tenant came from; null when created directly
 */
public record TenantProvisionedEvent(Long tenantId, String tenantName, String planCode,
                                     Long enquiryId, Long actorId) {}
