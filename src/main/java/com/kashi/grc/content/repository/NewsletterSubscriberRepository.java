package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.NewsletterSubscriber;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NewsletterSubscriberRepository extends JpaRepository<NewsletterSubscriber, Long> {
    Optional<NewsletterSubscriber> findByEmail(String email);
    Optional<NewsletterSubscriber> findByConfirmToken(String token);
    long countByConfirmedAtIsNotNullAndUnsubscribedAtIsNull();
}
