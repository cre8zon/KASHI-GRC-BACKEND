package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabMeeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollabMeetingRepository extends JpaRepository<CollabMeeting, Long> {

    Optional<CollabMeeting> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    List<CollabMeeting> findByWorkspaceIdAndIsDeletedFalseOrderByStartsAtDesc(Long workspaceId);

    List<CollabMeeting> findByTenantIdAndIdInAndIsDeletedFalse(Long tenantId, Collection<Long> ids);

    List<CollabMeeting> findByTenantIdAndOrganizerIdAndIsDeletedFalse(Long tenantId, Long organizerId);

    List<CollabMeeting> findByRoomIdAndIsDeletedFalseOrderByStartsAtDesc(Long roomId);

    List<CollabMeeting> findByTenantIdAndSeriesRefAndIsDeletedFalse(Long tenantId, String seriesRef);
}
