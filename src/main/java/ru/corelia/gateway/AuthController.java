package ru.corelia.gateway;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import ru.corelia.http.ApiRequest;

import tools.jackson.databind.JsonNode;

/** Возвращает профиль пользователя из проверенного JWT. */
@RestController
public class AuthController {
    private final ApiRequest requests;

    public AuthController(ApiRequest requests) {
        this.requests = requests;
    }

    @GetMapping("/api/core/v1/auth/me")
    public JsonNode me(HttpServletRequest r) {
        return requests.auth(r).user();
    }
}
