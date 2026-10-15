package com.kashi.grc.billing.controller;

import com.kashi.grc.billing.service.PublicPricingService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Unauthenticated price list for www.kashiguard.com/pricing.
 *
 * SecurityConfig opens exactly GET /v1/billing/public/plans and closes the rest
 * of /v1/billing/public/** — a public endpoint added here later stays shut
 * until someone opens it on purpose.
 */
@RestController
@RequestMapping("/v1/billing/public")
@Tag(name = "Billing — public price list")
@RequiredArgsConstructor
public class PublicPricingController {

    private final PublicPricingService pricingService;

    @GetMapping("/plans")
    @Operation(summary = "Public plans, prices and the comparison table (no auth)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> plans(@RequestParam(required = false) String currency) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(ApiResponse.success(pricingService.pricing(currency)));
    }
}
