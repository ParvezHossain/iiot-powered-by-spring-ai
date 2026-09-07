package com.iiot.simulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(SimulatorProperties.class)
@ConditionalOnProperty(name = "simulator.enabled", havingValue = "true", matchIfMissing = true)
class SimulatorConfiguration {
}
