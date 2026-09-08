package ru.corelia.gateway.legacy.http;

import static ru.corelia.support.Json.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ru.corelia.gateway.legacy.document.*;
import ru.corelia.gateway.legacy.task.TaskService;
import ru.corelia.http.ApiException;
import ru.corelia.http.ApiRequest;

import tools.jackson.databind.JsonNode;

/** Совместимые REST-маршруты карточек и вложений без собственной бизнес-модели хранения. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "CORELIA_LEGACY_ENABLED",
        havingValue = "true",
        matchIfMissing = true)
@RestController
@RequestMapping("/api/v1")
public class DocumentController {
    private final DocumentService documents;
    private final AttachmentService attachments;
    private final TaskService tasks;
    private final ApiRequest requests;

    public DocumentController(
            DocumentService documents,
            AttachmentService attachments,
            TaskService tasks,
            ApiRequest requests) {
        this.documents = documents;
        this.attachments = attachments;
        this.tasks = tasks;
        this.requests = requests;
    }

    @GetMapping("/document-types")
    public JsonNode types(HttpServletRequest request) {
        return documents.types(requests.auth(request));
    }

    @PostMapping("/document/search")
    public JsonNode search(HttpServletRequest request) {
        return documents.search(requests.body(request), requests.auth(request));
    }

    @PostMapping("/document")
    public ResponseEntity<JsonNode> create(HttpServletRequest request) {
        return ResponseEntity.status(201)
                .body(documents.create(requests.body(request), requests.auth(request)));
    }

    @GetMapping("/document/{id}")
    public JsonNode get(@PathVariable String id, HttpServletRequest request) {
        var auth = requests.auth(request);
        return tasks.withWorkflow(documents.get(id, auth), auth);
    }

    @PatchMapping("/document/{id}")
    public JsonNode update(@PathVariable String id, HttpServletRequest request) {
        return documents.update(id, requests.body(request), requests.auth(request));
    }

    @PostMapping("/document/{id}/approval")
    public JsonNode approve(@PathVariable String id, HttpServletRequest request) {
        return tasks.approval(id, requests.body(request), requests.auth(request));
    }

    @GetMapping("/document/{id}/attachments")
    public JsonNode list(@PathVariable String id, HttpServletRequest request) {
        var auth = requests.auth(request);
        documents.get(id, auth);
        return array(attachments.current(id, auth));
    }

    @PostMapping("/document/{id}/attachment")
    public ResponseEntity<JsonNode> upload(@PathVariable String id, HttpServletRequest request) {
        var auth = requests.auth(request);
        documents.raw(id, auth);
        return ResponseEntity.status(201)
                .body(array(attachments.upload(id, requests.body(request), auth)));
    }

    @PutMapping("/attachment/{id}")
    public JsonNode replace(@PathVariable String id, HttpServletRequest request) {
        var auth = requests.auth(request);
        JsonNode current = attachments.find(id, auth);
        String documentId = text(current, "documentId");
        if (documentId.isEmpty())
            throw new ApiException(502, "DataSpace вернул вложение без documentId");
        documents.raw(documentId, auth);
        return attachments.replace(current, requests.body(request), auth);
    }

    @DeleteMapping("/attachment/{id}")
    public JsonNode delete(@PathVariable String id, HttpServletRequest request) {
        return attachments.delete(id, requests.auth(request));
    }

    @GetMapping("/attachment/{id}/versions")
    public JsonNode versions(@PathVariable String id, HttpServletRequest request) {
        return array(attachments.previous(id, requests.auth(request)));
    }

    @GetMapping("/attachment/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id, HttpServletRequest request) {
        var file = attachments.download(id, requests.auth(request));
        return ResponseEntity.ok()
                .header("Content-Type", file.contentType())
                .contentLength(file.body().length)
                .header(
                        "Content-Disposition",
                        "attachment; filename*=UTF-8''" + encode(file.fileName()))
                .body(file.body());
    }
}
