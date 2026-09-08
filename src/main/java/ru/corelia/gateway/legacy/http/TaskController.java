package ru.corelia.gateway.legacy.http;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import ru.corelia.gateway.legacy.task.TaskService;
import ru.corelia.http.ApiRequest;

import tools.jackson.databind.JsonNode;

/** REST-контракт очередей и действий по задачам. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "CORELIA_LEGACY_ENABLED",
        havingValue = "true",
        matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/task")
public class TaskController {
    private final TaskService tasks;
    private final ApiRequest requests;

    public TaskController(TaskService tasks, ApiRequest requests) {
        this.tasks = tasks;
        this.requests = requests;
    }

    @PostMapping("/search")
    public JsonNode search(HttpServletRequest request) {
        return tasks.search(requests.body(request), requests.auth(request));
    }

    @GetMapping("/summary")
    public JsonNode summary(HttpServletRequest request) {
        return tasks.summary(requests.auth(request));
    }

    @PostMapping("/{id}/start")
    public JsonNode start(@PathVariable String id, HttpServletRequest request) {
        return tasks.start(id, requests.auth(request));
    }

    @PostMapping("/{id}/complete")
    public JsonNode complete(@PathVariable String id, HttpServletRequest request) {
        return tasks.complete(id, requests.body(request), requests.auth(request));
    }

    @PostMapping("/{id}/action")
    public JsonNode action(@PathVariable String id, HttpServletRequest request) {
        return tasks.action(id, requests.body(request), requests.auth(request));
    }
}
