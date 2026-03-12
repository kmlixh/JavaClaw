package com.janyee.agent.infra.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentPlatformProperties.class)
public class InfraConfiguration {
}
