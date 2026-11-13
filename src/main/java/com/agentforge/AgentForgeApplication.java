package com.agentforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

/** Entry point for the AgentForge decision-intelligence platform. */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class AgentForgeApplication {
    public static void main(String[] args) {
        // H2 rejects implicitly-relative file paths, so resolve the data directory to an absolute path up front.
        if (System.getProperty("agentforge.data-dir") == null) {
            String dir = System.getenv().getOrDefault("AGENTFORGE_DATA_DIR", "./data");
            if (dir.isBlank()) dir = "./data";
            System.setProperty("agentforge.data-dir", Path.of(dir).toAbsolutePath().normalize().toString().replace('\\', '/'));
        }
        SpringApplication.run(AgentForgeApplication.class, args);
    }
}
