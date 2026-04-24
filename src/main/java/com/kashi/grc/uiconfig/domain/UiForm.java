package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Defines a dynamic form (Create Vendor, Create User, Assessment Response, etc.).
 * Each form maps to a submit endpoint. Fields defined in UiFormField.
 * Adding a field to a form = insert a row. Zero code deploy.
 */
@Entity
@Table(name = "ui_forms")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class UiForm extends BaseEntity {

    @Column(name = "form_key", unique = true, nullable = false, length = 100)
    private String formKey;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** API endpoint this form submits to. e.g. '/v1/vendors/onboard' */
    @Column(name = "submit_url", nullable = false, length = 255)
    private String submitUrl;

    /** HTTP method: POST, PUT, PATCH */
    @Column(name = "http_method", length = 10)
    @Builder.Default
    private String httpMethod = "POST";

    @Column(name = "tenant_id")
    private Long tenantId;

    /** JSON: wizard steps config for multi-step forms */
    @Column(name = "steps_json", columnDefinition = "JSON")
    private String stepsJson;
}
