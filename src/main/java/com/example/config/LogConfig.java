package com.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * 初始化 java.util.logging：从 classpath 加载 logging.properties。
 */
public final class LogConfig {

    private LogConfig() {
    }

    public static void initialiser() {
        // 如果用户已通过 -Djava.util.logging.config.file 指定配置，则尊重用户的选择
        if (System.getProperty("java.util.logging.config.file") != null) {
            return;
        }
        try {
            // FileHandler 要求目录已存在
            Files.createDirectories(Paths.get("logs"));
            try (InputStream is = LogConfig.class.getClassLoader().getResourceAsStream("logging.properties")) {
                if (is != null) {
                    LogManager.getLogManager().readConfiguration(is);
                }
            }
        } catch (IOException e) {
            Logger.getLogger(LogConfig.class.getName())
                    .warning("Impossible de charger logging.properties : " + e.getMessage());
        }
    }
}
