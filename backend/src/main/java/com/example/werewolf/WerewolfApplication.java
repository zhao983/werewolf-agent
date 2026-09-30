package com.example.werewolf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 启动入口；核心规则位于独立的 game 包。 */
@SpringBootApplication
public class WerewolfApplication {
    public static void main(String[] args) {
        SpringApplication.run(WerewolfApplication.class, args);
    }
}
