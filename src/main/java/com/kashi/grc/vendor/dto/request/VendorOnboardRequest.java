package com.kashi.grc.vendor.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VendorOnboardRequest {
    @NotBlank public String name;
    public String legalName;
    public String registrationNumber;
    public String country;
    public String industry;
    public String riskClassification;
    public String criticality;
    public String dataAccessLevel;
    public String servicesProvided;
    public String website;
    @Data
    public static class PrimaryContact {
        public String firstName;
        public String lastName;
        public String email;
        public String jobTitle;
    }
    public PrimaryContact primaryContact;
    public String primaryContactEmail;
    @NotNull public Long workflowId;
}
