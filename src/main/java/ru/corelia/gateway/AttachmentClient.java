package ru.corelia.gateway;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Service;

import ru.corelia.auth.AuthContext;
import ru.corelia.transport.ServiceClient;

import tools.jackson.databind.JsonNode;

import java.util.*;

/** Клиент сервиса вложений для универсального API Corelia. */
@Service
public class AttachmentClient {
    public record Download(byte[] body, String contentType, String fileName) {}

    private final ServiceClient services;

    public AttachmentClient(ServiceClient services) {
        this.services = services;
    }

    public List<JsonNode> current(String type, String id, AuthContext auth) {
        return list(services.call("attachment", "/internal/v1/documents/" + encode(type) + "/" + encode(id) + "/attachments", "GET", null, auth));
    }

    public List<JsonNode> atHead(String type, String id, String head, AuthContext auth) {
        if (head == null || head.isBlank()) return List.of();
        return list(services.call("attachment", "/internal/v1/documents/" + encode(type) + "/" + encode(id) + "/attachments?head=" + encode(head), "GET", null, auth));
    }

    public List<JsonNode> upload(String type, String id, JsonNode body, AuthContext auth) {
        return list(services.call("attachment", "/internal/v1/documents/" + encode(type) + "/" + encode(id) + "/attachments", "POST", body, auth));
    }

    public JsonNode find(String id, AuthContext auth) {
        return services.call("attachment", "/internal/v1/attachments/" + encode(id) + "/metadata", "GET", null, auth);
    }

    public JsonNode replace(String id, JsonNode body, AuthContext auth) {
        return services.call("attachment", "/internal/v1/attachments/" + encode(id), "PUT", body, auth);
    }

    public JsonNode delete(String id, AuthContext auth) {
        return services.call("attachment", "/internal/v1/attachments/" + encode(id), "DELETE", null, auth);
    }

    public List<JsonNode> previous(String id, AuthContext auth) {
        return list(services.call("attachment", "/internal/v1/attachments/" + encode(id) + "/versions", "GET", null, auth));
    }

    public Download download(String id, AuthContext auth) {
        JsonNode metadata = find(id, auth);
        var response = services.raw("attachment", "/internal/v1/attachments/" + encode(id), "GET", new byte[0], auth);
        return new Download(response.body(), response.headers().firstValue("content-type").orElse("application/octet-stream"), fallback(text(metadata, "fileName"), id));
    }
}
