package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.UserAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserAttributeRepository extends JpaRepository<UserAttribute, Long> {

    @Query("SELECT a FROM UserAttribute a WHERE a.user.id = :userId")
    List<UserAttribute> findByUserId(@Param("userId") Long userId);

    @Query("SELECT a FROM UserAttribute a WHERE a.user.id = :userId AND a.attributeKey = :key")
    Optional<UserAttribute> findByUserIdAndAttributeKey(@Param("userId") Long userId, @Param("key") String key);

    @Modifying
    @Transactional
    @Query("DELETE FROM UserAttribute a WHERE a.user.id = :userId AND a.attributeKey = :key")
    void deleteByUserIdAndAttributeKey(@Param("userId") Long userId, @Param("key") String key);

    @Modifying
    @Transactional
    @Query("DELETE FROM UserAttribute a WHERE a.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
