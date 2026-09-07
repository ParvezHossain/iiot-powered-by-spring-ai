package com.iiot.rag;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.answer")
public record RagAnswerProperties(
        @DefaultValue("qwen2.5:1.5b") @NotBlank String model,
        @DefaultValue("6") @Min(1) @Max(8) int topK,
        @DefaultValue("0.45") @DecimalMin("0.0") @DecimalMax("1.0") double threshold) {}
