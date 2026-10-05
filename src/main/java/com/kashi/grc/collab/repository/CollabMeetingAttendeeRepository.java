package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabMeetingAttendee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollabMeetingAttendeeRepository extends JpaRepository<CollabMeetingAttendee, Long> {

    List<CollabMeetingAttendee> findByMeetingId(Long meetingId);

    List<CollabMeetingAttendee> findByMeetingIdIn(Collection<Long> meetingIds);

    List<CollabMeetingAttendee> findByTenantIdAndUserId(Long tenantId, Long userId);

    Optional<CollabMeetingAttendee> findByMeetingIdAndUserId(Long meetingId, Long userId);
}
