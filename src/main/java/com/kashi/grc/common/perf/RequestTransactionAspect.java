package com.kashi.grc.common.perf;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;

/**
 * ONE database transaction per GET request, for every @RestController.
 *
 * ── WHY ───────────────────────────────────────────────────────────────────────
 *
 * spring.jpa.open-in-view is off and GET handlers are not transactional, so
 * every repository call a GET made opened and committed its OWN transaction:
 * set autocommit off, run the query, commit, set autocommit back — about four
 * network round trips per query. Against a remote database (~150 ms per trip)
 * an 8-query endpoint cost ~4.8 s. Inside one transaction the same endpoint
 * costs roughly one trip per query plus a single commit.
 *
 * ── WHY A NORMAL (READ-WRITE) TRANSACTION, NOT readOnly ───────────────────────
 *
 * A few GET handlers write on purpose (scanned: assessment review, newsletter
 * confirm, trust-centre download log). Under a Hibernate read-only transaction
 * the common "load, modify, save" write is silently dropped — the entity was
 * loaded read-only, so the change is never flushed. A normal transaction keeps
 * every intended write working. A GET that writes nothing costs the same: the
 * commit is skipped by the driver (useLocalTransactionState) or is one trip.
 * Any GET that DOES write is logged at WARN, so accidental writes are visible.
 *
 * ── NOT WRAPPED ───────────────────────────────────────────────────────────────
 *
 *   • handlers or controllers already marked @Transactional (theirs applies)
 *   • @NoRequestTransaction
 *   • streaming / async / file-bytes responses (SseEmitter, StreamingResponseBody,
 *     DeferredResult, CompletableFuture, byte[], Resource, a raw
 *     HttpServletResponse / OutputStream parameter) — they would hold a pooled
 *     connection for the whole download
 *   • a call made while a transaction is already active
 *
 * ── FAILURES BEHAVE AS BEFORE ─────────────────────────────────────────────────
 *
 * An exception thrown by the handler rolls back and propagates unchanged. If an
 * inner @Transactional call failed and the handler CAUGHT it (which marks the
 * shared transaction rollback-only), the request is rolled back quietly
 * instead of committing — so a GET that used to recover from such a failure
 * does not now turn into an UnexpectedRollbackException 500.
 *
 * Switch off with kashi.perf.request-transaction.enabled=false.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.perf.request-transaction.enabled", havingValue = "true", matchIfMissing = true)
public class RequestTransactionAspect {

    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory       entityManagerFactory;

    @Around("@within(org.springframework.web.bind.annotation.RestController) "
            + "&& @annotation(org.springframework.web.bind.annotation.GetMapping)")
    public Object aroundGet(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Class<?> type = pjp.getTarget() != null ? pjp.getTarget().getClass() : method.getDeclaringClass();

        if (TransactionSynchronizationManager.isActualTransactionActive() || skip(method, type)) {
            return pjp.proceed();
        }

        String handler = type.getSimpleName() + "." + method.getName();
        DefaultTransactionDefinition def = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED);
        def.setName("GET " + handler);
        TransactionStatus status = transactionManager.getTransaction(def);

        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            rollbackQuietly(status, handler);
            throw t;
        }

        if (status.isRollbackOnly()) {
            // An inner call failed and the handler recovered — keep the old
            // behaviour (the handler's response stands), roll back, no 500.
            log.debug("[REQ-TX] {} marked rollback-only by an inner call — rolling back", handler);
            transactionManager.rollback(status);
            return result;
        }

        warnIfWriting(handler);
        transactionManager.commit(status);
        return result;
    }

    private void rollbackQuietly(TransactionStatus status, String handler) {
        try {
            if (!status.isCompleted()) transactionManager.rollback(status);
        } catch (RuntimeException ex) {
            log.warn("[REQ-TX] Rollback after failure in {} failed: {}", handler, ex.getMessage());
        }
    }

    /** Visibility for GETs that write — intended ones are fine, accidental ones get noticed. */
    private void warnIfWriting(String handler) {
        try {
            EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            if (em != null && em.unwrap(org.hibernate.Session.class).isDirty()) {
                log.warn("[REQ-TX] GET {} is writing to the database inside its request transaction "
                        + "(committed). Expected only for GETs that write on purpose.", handler);
            }
        } catch (RuntimeException ignored) {
            // diagnostic only — never affects the request
        }
    }

    private static boolean skip(Method method, Class<?> type) {
        if (AnnotatedElementUtils.hasAnnotation(method, NoRequestTransaction.class)
                || AnnotatedElementUtils.hasAnnotation(type, NoRequestTransaction.class)) return true;
        if (AnnotatedElementUtils.hasAnnotation(method, org.springframework.transaction.annotation.Transactional.class)
                || AnnotatedElementUtils.hasAnnotation(type, org.springframework.transaction.annotation.Transactional.class)
                || AnnotatedElementUtils.hasAnnotation(method, jakarta.transaction.Transactional.class)
                || AnnotatedElementUtils.hasAnnotation(type, jakarta.transaction.Transactional.class)) return true;

        String ret = method.getGenericReturnType().getTypeName();
        if (ret.contains("byte[]") || ret.contains("SseEmitter") || ret.contains("ResponseBodyEmitter")
                || ret.contains("StreamingResponseBody") || ret.contains("DeferredResult")
                || ret.contains("CompletableFuture") || ret.contains("CompletionStage")
                || ret.contains("java.util.concurrent.Callable") || ret.contains("reactor.core")
                || ret.endsWith("Resource") || ret.contains("Resource>")) return true;

        for (Class<?> p : method.getParameterTypes()) {
            String n = p.getName();
            if (n.equals("jakarta.servlet.http.HttpServletResponse") || n.equals("java.io.OutputStream")) return true;
        }
        return false;
    }
}