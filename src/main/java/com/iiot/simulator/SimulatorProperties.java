package com.iiot.simulator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("simulator")
@Validated
public record SimulatorProperties(
        @DefaultValue("5") @Min(1) @Max(1000) int machineCount,
        @DefaultValue("5000") @Min(100) long intervalMs,
        @DefaultValue("12") @Min(2) int anomalyEveryTicks,
        @DefaultValue("42") long seed) {
}
