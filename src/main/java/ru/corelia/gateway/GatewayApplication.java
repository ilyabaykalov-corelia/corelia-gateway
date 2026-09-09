package ru.corelia.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import ru.corelia.config.LocalEnvironment;

/** Запускает приложение corelia-gateway. */
@SpringBootApplication(
        scanBasePackages = {
            "ru.corelia.config",
            "ru.corelia.support",
            "ru.corelia.auth",
            "ru.corelia.http",
            "ru.corelia.cache",
            "ru.corelia.integration",
            "ru.corelia.transport",
            "ru.corelia.gateway"
        })
public class GatewayApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(GatewayApplication.class);
        app.setDefaultProperties(LocalEnvironment.load());
        app.run(args);
    }
}
