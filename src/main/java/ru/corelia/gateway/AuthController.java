package ru.corelia.gateway;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ru.corelia.http.ApiRequest;
import ru.corelia.transport.ServiceClient;

import tools.jackson.databind.JsonNode;

/** Единая внешняя точка входа в авторизацию; провайдер скрыт за corelia-auth. */
@RestController
public class AuthController {
    private final ServiceClient services;
    private final ApiRequest requests;

    public AuthController(ServiceClient services, ApiRequest requests) {
        this.services = services;
        this.requests = requests;
    }

    @PostMapping({"/api/v1/auth/login", "/api/core/v1/auth/login"})
    public JsonNode login(HttpServletRequest r) {
        return services.call("auth", "/internal/v1/auth/login", "POST", requests.body(r), null);
    }

    @PostMapping({"/api/v1/auth/refresh", "/api/core/v1/auth/refresh"})
    public JsonNode refresh(HttpServletRequest r) {
        return services.call("auth", "/internal/v1/auth/refresh", "POST", requests.body(r), null);
    }

    @PostMapping({"/api/v1/auth/logout", "/api/core/v1/auth/logout"})
    public ResponseEntity<Void> logout(HttpServletRequest r) {
        services.call("auth", "/internal/v1/auth/logout", "POST", requests.body(r), null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping({"/api/v1/user", "/api/v1/auth/me", "/api/core/v1/auth/me"})
    public JsonNode me(HttpServletRequest r) {
        return services.call("auth", "/internal/v1/auth/me", "GET", null, requests.auth(r));
    }
}
