package ru.corelia.gateway;

import jakarta.servlet.http.HttpServletRequest;

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

    @GetMapping("/api/core/v1/auth/me")
    public JsonNode me(HttpServletRequest r) {
        return services.call("auth", "/internal/v1/auth/me", "GET", null, requests.auth(r));
    }
}
