package com.kashi.grc.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Authorize button in Swagger UI.
 *
 * ── WHY SWAGGER WAS THERE BUT NOT USABLE ──────────────────────────────────
 *
 * springdoc is on the classpath, every controller is annotated with @Tag and
 * @Operation, and SecurityConfig already lets /swagger-ui/** and
 * /v3/api-docs/** through unauthenticated. So the page rendered and every
 * endpoint was listed — and every "Try it out" came back 401, because nothing
 * ever told springdoc the API uses a bearer token. With no security scheme
 * declared there is no Authorize button, no way to attach a JWT, and the UI is
 * reduced to a read-only catalogue.
 *
 * This declares the one scheme the app actually uses: the Authorization header,
 * "Bearer <token>", exactly as JwtAuthenticationFilter reads it.
 *
 * ── HOW TO USE IT ─────────────────────────────────────────────────────────
 *
 *   1. POST /v1/auth/login in the UI (it is permitAll, so it works unauthorised)
 *   2. copy the token out of the response
 *   3. Authorize → paste the token ALONE, with no "Bearer " prefix —
 *      scheme = bearer means springdoc adds that itself
 *
 * Every subsequent call carries it until you log out of the page.
 *
 * ── WHY addSecurityItem AND NOT A PER-CONTROLLER ANNOTATION ───────────────
 *
 * Declaring it once at the root applies it to every operation, which matches
 * reality: SecurityConfig authenticates everything except a short permitAll
 * list. The handful of public endpoints will show a padlock they do not need,
 * which is harmless — the alternative is @SecurityRequirement on ~80
 * controllers and a new one silently missing it every time somebody adds a
 * file.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kashiGrcOpenApi() {
        final String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("KashiGRC API")
                        .version("v1")
                        .description("Internal API. Log in via /v1/auth/login, then use Authorize "
                                + "with the returned token (no \"Bearer \" prefix)."))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .name(scheme)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                // Cosmetic — it tells the UI to label the field
                                // JWT. The filter only cares about the header.
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)));
    }
}