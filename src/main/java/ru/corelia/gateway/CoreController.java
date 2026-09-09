package ru.corelia.gateway;

import static ru.corelia.support.Json.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiRequest;
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

    @GetMapping("/documents/{type}/{id}")
    public JsonNode get(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        var doc = copy(services.call("document", path(type) + "/" + encode(id), "GET", null, auth));
        doc.set("attachments", array(attachments.current(type, id, auth)));
        doc.set("workflow", terminal(doc) ? emptyWorkflow() : workflow(type, id, auth));
        return doc;
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

    @GetMapping("/documents/{type}/{id}/versions")
    public JsonNode documentVersions(@PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        return services.call("document", path(type) + "/" + encode(id) + "/versions", "GET", null, requests.auth(r));
    }

    @GetMapping("/documents/{type}/{id}/versions/{version}")
    public JsonNode documentVersion(@PathVariable String type, @PathVariable String id, @PathVariable int version, HttpServletRequest r) {
        var auth = requests.auth(r);
        var doc = copy(services.call("document", path(type) + "/" + encode(id) + "/versions/" + version, "GET", null, auth));
        doc.set("attachments", array(attachments.atHead(type, id, text(doc, "attachmentsHead"), auth)));
        return doc;
    }

    @PostMapping("/documents/{type}/{id}/attachments")
    public ResponseEntity<JsonNode> upload(
            @PathVariable String type, @PathVariable String id, HttpServletRequest r) {
        var auth = requests.auth(r);
        services.call("document", path(type) + "/" + encode(id), "GET", null, auth);
        return ResponseEntity.status(201)
                .body(array(attachments.upload(type, id, requests.body(r), auth)));
    }

    @RequestMapping(
            value = "/attachments/{id}",
            method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> file(@PathVariable String id, HttpServletRequest r) {
        var response =
                services.raw(
                        "attachment",
                        "/internal/v1/attachments/" + encode(id),
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
        return services.call(
                "workflow",
                "/internal/v1/tasks/" + encode(id) + "/complete",
                "POST",
                requests.body(r),
                requests.auth(r));
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
        return java.util.Set.of("APPROVED", "REJECTED")
                .contains(text(document, "status"));
    }

    private static JsonNode emptyWorkflow() {
        return object("task", null, "availableActions", java.util.List.of(), "executor", null);
    }

    private static String path(String type) {
        return "/internal/v1/documents/" + encode(type);
    }
}
