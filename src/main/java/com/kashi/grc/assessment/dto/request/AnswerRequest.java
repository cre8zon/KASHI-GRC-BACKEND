package com.kashi.grc.assessment.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

@Data
public class AnswerRequest {
    @NotNull public Long questionInstanceId;
    public Long selectedOptionInstanceId;
    public List<Long> selectedOptionInstanceIds; // for MULTI_CHOICE
    public String responseText;
    public List<Long> documentIds;
}