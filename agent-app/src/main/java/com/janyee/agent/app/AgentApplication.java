package com.janyee.agent.app;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.janyee.agent")
@EntityScan(basePackages = "com.janyee.agent")
@EnableJpaRepositories(basePackages = "com.janyee.agent")
// Required for ScheduledRunReconcileSweeper (@Scheduled) to actually tick. Without this
// annotation Spring ignores @Scheduled methods entirely.
@EnableScheduling
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
