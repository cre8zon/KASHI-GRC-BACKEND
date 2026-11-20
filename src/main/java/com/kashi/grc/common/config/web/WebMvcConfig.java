package com.kashi.grc.common.config.web;

import com.kashi.grc.common.config.multitenancy.TenantInterceptor;
import com.kashi.grc.common.security.ModuleActionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final ModuleActionGuard moduleActionGuard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/v1/**")
                .excludePathPatterns(
                        "/v1/auth/login",
                        "/v1/auth/request-password-reset",
                        "/v1/auth/reset-password"
                );
        // After the tenant interceptor, so the caller's tenant is set. The guard
        // narrows itself to the module paths and mutating methods it protects.
        registry.addInterceptor(moduleActionGuard)
                .addPathPatterns("/v1/**");
    }
}