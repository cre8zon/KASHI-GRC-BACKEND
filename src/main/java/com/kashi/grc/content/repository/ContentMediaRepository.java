package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.ContentMedia;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentMediaRepository extends JpaRepository<ContentMedia, Long> {
    Page<ContentMedia> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<ContentMedia> findByIdIn(List<Long> ids);
}
