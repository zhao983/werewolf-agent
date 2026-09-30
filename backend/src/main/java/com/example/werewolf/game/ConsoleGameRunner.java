package com.example.werewolf.game;

import java.util.Random;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 经典 7 人独立 Console 演示入口，无需浏览器或 AI API。 */
@Component
@Profile("console")
public class ConsoleGameRunner implements CommandLineRunner {
    @Override
    public void run(String... args) {
        new GameEngine(new Random()).start();
    }
}
