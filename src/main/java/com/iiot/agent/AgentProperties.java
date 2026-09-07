package com.iiot.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("agent")
public record AgentProperties(
        @DefaultValue("qwen2.5:1.5b") @NotBlank String model,
        @DefaultValue("4") @Min(1) @Max(8) int maxRounds,
        @DefaultValue("8") @Min(1) @Max(16) int maxToolCalls) {}
