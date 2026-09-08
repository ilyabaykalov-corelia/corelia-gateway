package ru.corelia.gateway.legacy.document;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Service;

import ru.corelia.auth.AuthContext;
import ru.corelia.transport.ServiceClient;

import tools.jackson.databind.JsonNode;

import java.util.*;

/** Совместимый фасад вложений; данные и содержимое обслуживает отдельный сервис. */
@Service
public class AttachmentService {
    public record Download(byte[] body, String contentType, String fileName) {}

    private final ServiceClient services;

    public AttachmentService(ServiceClient services) {
        this.services = services;
    }

    public List<JsonNode> current(String id, AuthContext auth) {
        return list(
                services.call(
                        "attachment",
                        "/internal/v1/documents/" + encode(id) + "/attachments",
                        "GET",
                        null,
                        auth));
    }

    public List<JsonNode> upload(String id, JsonNode body, AuthContext auth) {
        return list(
                services.call(
                        "attachment",
                        "/internal/v1/documents/" + encode(id) + "/attachments",
                        "POST",
                        body,
                        auth));
    }

    public JsonNode find(String id, AuthContext auth) {
        return services.call(
                "attachment",
                "/internal/v1/attachments/" + encode(id) + "/metadata",
                "GET",
                null,
                auth);
    }

    public JsonNode replace(JsonNode current, JsonNode body, AuthContext auth) {
        return services.call(
                "attachment",
                "/internal/v1/attachments/" + encode(first(current, "attachmentId", "id")),
                "PUT",
                body,
                auth);
    }

    public JsonNode delete(String id, AuthContext auth) {
        return services.call(
                "attachment", "/internal/v1/attachments/" + encode(id), "DELETE", null, auth);
    }

    public List<JsonNode> previous(String id, AuthContext auth) {
        return list(
                services.call(
                        "attachment",
                        "/internal/v1/attachments/" + encode(id) + "/versions",
                        "GET",
                        null,
                        auth));
    }

    public Download download(String id, AuthContext auth) {
        JsonNode metadata = find(id, auth);
        var response =
                services.raw(
                        "attachment",
                        "/internal/v1/attachments/" + encode(id),
                        "GET",
                        new byte[0],
                        auth);
        return new Download(
                response.body(),
                response.headers().firstValue("content-type").orElse("application/octet-stream"),
                fallback(text(metadata, "fileName"), id));
    }
}
