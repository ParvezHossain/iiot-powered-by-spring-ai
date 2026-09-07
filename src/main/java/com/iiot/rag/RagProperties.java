package com.iiot.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag")
public record RagProperties(
        @DefaultValue("classpath:equipment/*.md") @NotBlank String documents,
        @DefaultValue("16") @Min(1) @Max(64) int batchSize,
        @DefaultValue("true") boolean ingestOnStartup) {
    public static final String MODEL = "nomic-embed-text:v1.5";
    public static final int DIMENSIONS = 768;
    public static final String CORPUS = "equipment-v1";
}
