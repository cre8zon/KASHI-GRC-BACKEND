package com.kashi.grc.evidence.event;

/**
 * A piece of linked evidence now stands as evidence for its target: the
 * reviewer ACCEPTED the link, or an integration created it already
 * AUTOMATION_VERIFIED. Modules that keep their own "evidence is in" state
 * (audit controls) listen for it; the evidence module itself knows nothing
 * about them.
 *
 * @param acceptedBy the reviewer; null when an integration verified it
 */
public record EvidenceLinkAcceptedEvent(String targetEntityType, Long targetEntityId,
                                        Long acceptedBy, Long tenantId) {}