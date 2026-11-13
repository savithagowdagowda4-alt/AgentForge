package com.agentforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the AgentForge decision-intelligence platform. */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class AgentForgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentForgeApplication.class, args);
    }
}
