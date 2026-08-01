package com.kashi.grc.uiconfig.security;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.uiconfig.service.TenantFeatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Enforces @RequiresFeature. Method-level annotation wins over class-level.
 * On a missing feature, throws 403 (FORBIDDEN) — the real server-side gate that
 * a frontend route guard alone cannot provide.
 *
 * This is the API layer of the three-layer entitlement model:
 *   1. nav link      — ui_navigation.required_feature  (hides the link)
 *   2. route guard   — frontend, redirects              (stops URL typing)
 *   3. API (here)    — @RequiresFeature                 (stops scripts/curl)
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RequiresFeatureAspect {

    private final TenantFeatureService tenantFeatureService;

    @Around("@within(com.kashi.grc.uiconfig.security.RequiresFeature) "
            + "|| @annotation(com.kashi.grc.uiconfig.security.RequiresFeature)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();

        // Method-level annotation takes precedence over the controller-level one.
        RequiresFeature ann = AnnotationUtils.findAnnotation(method, RequiresFeature.class);
        if (ann == null) {
            ann = AnnotationUtils.findAnnotation(method.getDeclaringClass(), RequiresFeature.class);
        }

        if (ann != null && !tenantFeatureService.hasFeature(ann.value())) {
            log.warn("[ENTITLEMENT] Blocked {}.{} — tenant lacks feature '{}'",
                    method.getDeclaringClass().getSimpleName(), method.getName(), ann.value());
            throw new BusinessException("FEATURE_NOT_LICENSED",
                    "This feature is not enabled for your organization.",
                    HttpStatus.FORBIDDEN);
        }
        return pjp.proceed();
    }
}