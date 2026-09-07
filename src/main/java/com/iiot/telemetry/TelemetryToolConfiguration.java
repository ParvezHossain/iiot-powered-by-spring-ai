package com.iiot.telemetry;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TelemetryToolConfiguration {
    @Bean
    ToolCallbackProvider telemetryToolCallbackProvider(TelemetryTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
