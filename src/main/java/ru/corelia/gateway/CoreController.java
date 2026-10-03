package ru.corelia.gateway;

import static ru.corelia.support.Json.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiRequest;
import ru.corelia.http.ApiException;
import ru.corelia.transport.ServiceClient;

import tools.jackson.databind.JsonNode;

/** Универсальный внешний API для документов, вложений и задач. */
@RestController
@RequestMapping("/api/core/v1")
public class CoreController {
    private final ServiceClient services;
    private final ApiRequest requests;
    private final AttachmentClient attachments;

    public CoreController(
            ServiceClient services, ApiRequest requests, AttachmentClient attachments) {
        this.services = services;
        this.requests = requests;
        this.attachments = attachments;
    }

    @GetMapping("/document-types")
    public JsonNode types(HttpServletRequest r) {
        return services.call(
                "document", "/internal/v1/document-types", "GET", null, requests.auth(r));
    }

    @PostMapping("/admin/migrations/legacy-blobs")
    public JsonNode migrateLegacyBlobs(HttpServletRequest r) {
        return services.call(
                "attachment", "/internal/v1/migrations/legacy-blobs", "POST", object(), requests.auth(r));
    }

    @GetMapping("/admin/workflows")
    public JsonNode workflows(HttpServletRequest r) {
        return services.call(
                "workflow", "/internal/v1/admin/workflows", "GET", null, requests.auth(r));
    }

    @PostMapping("/admin/workflows")
    public JsonNode createWorkflow(HttpServletRequest r) {
        return services.call("workflow", "/internal/v1/admin/workflows", "POST", requests.body(r), requests.auth(r));
    }

    @GetMapping("/admin/workflows/{key}")
    public JsonNode workflowDraft(@PathVariable String key, HttpServletRequest r) {
        return services.call("workflow", "/internal/v1/admin/workflows/" + encode(key), "GET", null, requests.auth(r));
    }

    @PutMapping("/admin/workflows/{key}/draft")
    public JsonNode saveWorkflowDraft(@PathVariable String key, HttpServletRequest r) {
        return services.call("workflow", "/internal/v1/admin/workflows/" + encode(key) + "/draft", "PUT", requests.body(r), requests.auth(r));
    }

    @PostMapping("/admin/workflows/{key}/validate")
    public JsonNode validateWorkflowDraft(@PathVariable String key, HttpServletRequest r) {
        return services.call("workflow", "/internal/v1/admin/workflows/" + encode(key) + "/validate", "POST", null, requests.auth(r));
    }

    @PostMapping("/admin/workflows/{key}/publish")
    public JsonNode publishWorkflowDraft(@PathVariable String key, HttpServletRequest r) {
        return services.call("workflow", "/internal/v1/admin/workflows/" + encode(key) + "/publish", "POST", null, requests.auth(r));
    }

    @PostMapping("/admin/workflows/{key}/retire")
    public JsonNode retireWorkflowDraft(@PathVariable String key, HttpServletRequest r) {
        return services.call("workflow", "/internal/v1/admin/workflows/" + encode(key) + "/retire", "POST", null, requests.auth(r));
    }

    @PostMapping("/admin/workflows/{key}/import")
    public JsonNode importWorkflowDraft(@PathVariable String key, HttpServletRequest r) {
        return services.call("workflow", "/internal/v1/admin/workflows/" + encode(key) + "/import", "POST", requests.body(r), requests.auth(r));
    }

    @GetMapping("/admin/workflows/{key}/export")
    public JsonNode exportWorkflowDraft(@PathVariable String key, HttpServletRequest r) {
        return services.call("workflow", "/internal/v1/admin/workflows/" + encode(key) + "/export", "GET", null, requests.auth(r));
    }

    @GetMapping("/admin/workflows/{key}/audit")
    public JsonNode workflowAudit(@PathVariable String key, HttpServletRequest r) {
        return services.call("workflow", "/internal/v1/admin/workflows/" + encode(key) + "/audit", "GET", null, requests.auth(r));
    }

    @GetMapping("/document-types/{type}")
    public JsonNode definition(@PathVariable String type, HttpServletRequest r) {
        return services.call("document", "/internal/v1/document-types/" + encode(type), "GET", null, requests.auth(r));
    }

    @PostMapping("/documents/search")
    public JsonNode searchAll(HttpServletRequest r) {
        return services.call("document", "/internal/v1/documents/search", "POST", requests.body(r), requests.auth(r));
    }

    @GetMapping("/documents/by-id/{id}")
    public JsonNode getById(@PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        JsonNode doc = services.call("document", "/internal/v1/documents/by-id/" + encode(id), "GET", null, auth);
        return document(text(doc, "typeCode"), id, auth);
    }

    @PostMapping("/documents/{type}/search")
    public JsonNode search(@PathVariable String type, HttpServletRequest r) {
        return services.call(
                "document", path(type) + "/search", "POST", requests.body(r), requests.auth(r));
    }

    @PostMapping("/documents/{type}")
    public ResponseEntity<JsonNode> create(@PathVariable String type, HttpServletRequest r) {
        return ResponseEntity.status(201)
                .body(
                        services.call(
                                "document",
                                path(type),
                                "POST",
                                requests.body(r),
                                requests.auth(r)));
    }

    @PostMapping(value = "/documents/{type}/stream", consumes = "multipart/form-data")
    public ResponseEntity<JsonNode> createStream(
            @PathVariable String type,
            @RequestParam String requestId,
            @RequestParam String attributes,
            @RequestParam MultipartFile file,
            HttpServletRequest request)
            throws java.io.IOException {
        try (var content = file.getInputStream()) {
            return ResponseEntity.status(201)
                    .body(
                            services.callMultipart(
                                    "document",
                                    "/internal/v1/documents/" + encode(type) + "/stream",
                                    "POST",
                                    java.util.Map.of("requestId", requestId, "attributes", attributes),
                                    fallback(file.getOriginalFilename(), "attachment.bin"),
                                    fallback(file.getContentType(), "application/octet-stream"),
                                    content,
                                    requests.auth(request)));
        }
    }

    @GetMapping("/documents/{type}/{id}")
    public JsonNode get(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return document(type, id, requests.auth(r));
    }

    private JsonNode document(String type, String id, AuthContext auth) {
        var doc = copy(services.call("document", path(type) + "/" + encode(id), "GET", null, auth));
        // Attachments belong to the same version snapshot returned by document-service.
        JsonNode context = terminal(doc) ? emptyWorkflow() : workflow(type, id, auth);
        doc.set("workflow", context);
        doc.set("availableActions", context.path("availableActions"));
        doc.set("executor", context.path("executor"));
        return doc;
    }

    @GetMapping("/documents/{type}/{id}/actions")
    public JsonNode documentActions(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        JsonNode card = document(type, id, auth);
        JsonNode capabilities = services.call("document", path(type) + "/" + encode(id) + "/capabilities", "GET", null, auth);
        return object("actions", card.path("availableActions"), "capabilities", capabilities.path("capabilities"));
    }

    @PostMapping("/documents/{type}/{id}/actions/{action}")
    public JsonNode documentAction(@PathVariable String type, @PathVariable String id, @PathVariable String action, HttpServletRequest r) {
        var auth = requests.auth(r);
        JsonNode card = document(type, id, auth);
        String taskId = text(card.path("workflow").path("task"), "id");
        if (taskId.isEmpty()) throw new ApiException(404, "Активная задача документа не найдена");
        services.call("workflow", "/internal/v1/tasks/" + encode(taskId) + "/complete", "POST", object("actionCode", action), auth);
        return document(type, id, auth);
    }

    @GetMapping("/documents/{type}/{id}/capabilities")
    public JsonNode capabilities(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return services.call("document", path(type) + "/" + encode(id) + "/capabilities", "GET", null, requests.auth(r));
    }

    @GetMapping("/documents/{type}/{id}/versions")
    public JsonNode documentVersions(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return services.call("document", path(type) + "/" + encode(id) + "/versions", "GET", null, requests.auth(r));
    }
    @GetMapping("/documents/{type}/{id}/versions/{version}")
    public JsonNode documentVersion(@PathVariable String type, @PathVariable String id, @PathVariable int version, HttpServletRequest r) {
        var auth = requests.auth(r);
        var doc = copy(services.call("document", path(type) + "/" + encode(id) + "/versions/" + version, "GET", null, auth));
        doc.set("workflow", terminal(doc) ? emptyWorkflow() : workflow(type, id, auth));
        return doc;
    }

    @GetMapping("/documents/{type}/{id}/history")
    public JsonNode documentHistory(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return services.call("document", path(type) + "/" + encode(id) + "/history", "GET", null, requests.auth(r));
    }

    @PatchMapping("/documents/{type}/{id}")
    public JsonNode update(
            @PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return services.call(
                "document",
                path(type) + "/" + encode(id),
                "PATCH",
                requests.body(r),
                requests.auth(r));
    }

    @GetMapping("/documents/{type}/{id}/attachments")
    public JsonNode files(
            @PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        services.call("document", path(type) + "/" + encode(id), "GET", null, auth);
        return array(attachments.current(type, id, auth));
    }

    @PostMapping("/documents/{type}/{id}/attachments")
    public ResponseEntity<JsonNode> upload(
            @PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        services.call("document", path(type) + "/" + encode(id), "GET", null, auth);
        return ResponseEntity.status(201)
                .body(array(attachments.upload(type, id, requests.body(r), auth)));
    }

    @PostMapping(value = "/documents/{type}/{id}/attachments/stream", consumes = "multipart/form-data")
    public ResponseEntity<JsonNode> uploadStream(
            @PathVariable String type,
            @PathVariable String id,
            @RequestParam String requestId,
            @RequestParam MultipartFile file,
            HttpServletRequest r)
            throws java.io.IOException {
        var auth = requests.auth(r);
        services.call("document", path(type) + "/" + encode(id), "GET", null, auth);
        try (var body = file.getInputStream()) {
            return ResponseEntity.status(201)
                    .body(
                            services.callMultipart(
                                    "attachment",
                                    "/internal/v1/documents/"
                                            + encode(type)
                                            + "/"
                                            + encode(id)
                                    + "/attachments/stream",
                                    "POST",
                                    requestId,
                                    fallback(file.getOriginalFilename(), "attachment.bin"),
                                    fallback(file.getContentType(), "application/octet-stream"),
                                    body,
                                    auth));
        }
    }

    @RequestMapping(
            value = "/attachments/{id}",
            method = {RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> file(@PathVariable String id, HttpServletRequest r) {
        var response =
                services.raw(
                        "attachment",
                        "/internal/v1/attachments/" + encode(id) + (r.getMethod().equals("DELETE") ? "?requestId=" + encode(fallback(r.getParameter("requestId") == null ? "" : r.getParameter("requestId"), "")) : ""),
                        r.getMethod(),
                        r.getMethod().equals("PUT")
                                ? write(requests.body(r))
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                : new byte[0],
                        requests.auth(r));
        var result =
                ResponseEntity.status(response.statusCode())
                        .header(
                                "Content-Type",
                                response.headers()
                                        .firstValue("content-type")
                                        .orElse("application/json"));
        response.headers()
                .firstValue("content-disposition")
                .ifPresent(v -> result.header("Content-Disposition", v));
        return result.body(response.body());
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> download(
            @PathVariable String id, HttpServletRequest r) {
        var response = services.rawStream("attachment", "/internal/v1/attachments/" + encode(id), requests.auth(r));
        var result = ResponseEntity.ok().header("Content-Type", response.headers().firstValue("content-type").orElse("application/octet-stream"));
        response.headers().firstValue("content-disposition").ifPresent(value -> result.header("Content-Disposition", value));
        return result.body(output -> { try (var input = response.body()) { input.transferTo(output); } });
    }

    @PutMapping(value = "/attachments/{id}/stream", consumes = "multipart/form-data")
    public JsonNode replaceStreamMultipart(
            @PathVariable String id,
            @RequestParam String requestId,
            @RequestParam MultipartFile file,
            HttpServletRequest r)
            throws java.io.IOException {
        try (var body = file.getInputStream()) {
            return services.callMultipart(
                    "attachment",
                    "/internal/v1/attachments/" + encode(id) + "/stream",
                    "PUT",
                    requestId,
                    fallback(file.getOriginalFilename(), "attachment.bin"),
                    fallback(file.getContentType(), "application/octet-stream"),
                    body,
                    requests.auth(r));
        }
    }

    @GetMapping("/attachments/{id}/versions")
    public JsonNode versions(@PathVariable String id, HttpServletRequest r) {
        return array(attachments.previous(id, requests.auth(r)));
    }

    @PostMapping("/tasks/search")
    public JsonNode tasks(HttpServletRequest r) {
        return services.call(
                "workflow",
                "/internal/v1/tasks/search",
                "POST",
                requests.body(r),
                requests.auth(r));
    }

    @GetMapping("/tasks/{id}/actions")
    public JsonNode actions(@PathVariable String id, HttpServletRequest r) {
        return services.call(
                "workflow",
                "/internal/v1/tasks/" + encode(id) + "/actions",
                "GET",
                null,
                requests.auth(r));
    }

    @PostMapping("/tasks/{id}/start")
    public JsonNode start(@PathVariable String id, HttpServletRequest r) {
        return services.call(
                "workflow",
                "/internal/v1/tasks/" + encode(id) + "/start",
                "POST",
                object(),
                requests.auth(r));
    }

    @PostMapping("/tasks/{id}/action")
    public JsonNode action(@PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        JsonNode body = requests.body(r);
        // Клиент dev передаёт ID задачи из очереди и ID документа из карточки.
        try {
            services.call("workflow", "/internal/v1/tasks/" + encode(id), "GET", null, auth);
        } catch (ApiException error) {
            if (error.status() != 404) throw error;
            try {
                return services.call("workflow", "/internal/v1/tasks/" + encode(id) + "/complete", "POST", body, auth);
            } catch (ApiException completionError) {
                if (completionError.status() != 404) throw completionError;
            }
            JsonNode identity = services.call("document", "/internal/v1/documents/by-id/" + encode(id), "GET", null, auth);
            String type = text(identity, "typeCode");
            JsonNode card = document(type, id, auth);
            String taskId = text(card.path("workflow").path("task"), "id");
            if (taskId.isEmpty()) throw new ApiException(404, "Активная задача документа не найдена");
            services.call("workflow", "/internal/v1/tasks/" + encode(taskId) + "/complete", "POST", body, auth);
            JsonNode updated = document(type, id, auth);
            var response = copy(updated.path("attributes"));
            response.put("id", id);
            response.put("documentTypeId", text(updated, "typeCode"));
            response.put("documentType", text(updated, "typeName"));
            response.put("status", text(updated, "status"));
            response.put("documentStatus", text(updated, "statusLabel"));
            for (String field : java.util.List.of("createdBy", "createdAt", "attachments", "availableActions", "executor", "workflow", "version", "currentVersion", "changeToken", "versionCreatedBy", "versionCreatedAt"))
                if (updated.has(field)) response.set(field, updated.path(field));
            return response;
        }
        return services.call("workflow", "/internal/v1/tasks/" + encode(id) + "/complete", "POST", body, auth);
    }

    @GetMapping("/documents/{type}/{id}/workflow")
    public JsonNode documentWorkflow(
            @PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        var document = services.call("document", path(type) + "/" + encode(id), "GET", null, auth);
        return terminal(document) ? emptyWorkflow() : workflow(type, id, auth);
    }

    @GetMapping("/tasks/summary")
    public JsonNode summary(HttpServletRequest r) {
        return services.call(
                "workflow", "/internal/v1/tasks/summary", "GET", null, requests.auth(r));
    }

    private JsonNode workflow(String type, String id, AuthContext auth) {
        return services.call(
                "workflow",
                "/internal/v1/documents/" + encode(type) + "/" + encode(id) + "/workflow",
                "GET",
                null,
                auth);
    }

    private static boolean terminal(JsonNode document) {
        return document.path("workflowCompleted").asBoolean();
    }

    private static JsonNode emptyWorkflow() {
        return object("task", null, "availableActions", java.util.List.of(), "executor", null);
    }

    private static String path(String type) {
        return "/internal/v1/documents/" + encode(type);
    }
}
