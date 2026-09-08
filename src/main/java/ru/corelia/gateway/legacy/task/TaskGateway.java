package ru.corelia.gateway.legacy.task;

import static ru.corelia.gateway.legacy.task.TaskPresentation.*;
import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.cache.UserCache;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.gateway.WorkflowClient;
import ru.corelia.http.ApiException;
import ru.corelia.support.ParallelCalls;

import tools.jackson.databind.JsonNode;

import java.util.*;

/** Поиск и чтение задач с совместимыми резервными маршрутами BPMU/BPMX. */
@Component
public class TaskGateway {
    public static final List<String> ACTIVE = List.of("STARTED", "ASSIGNED", "NEW");
    public static final List<String> SCOPES = List.of("EXECUTOR", "MANAGER");
    private final WorkflowClient bpm;
    private final UserCache cache;
    private final CoreliaConfig config;
    private final ParallelCalls parallel;

    public TaskGateway(
            WorkflowClient bpm, UserCache cache, CoreliaConfig config, ParallelCalls parallel) {
        this.bpm = bpm;
        this.cache = cache;
        this.config = config;
        this.parallel = parallel;
    }

    public JsonNode search(JsonNode filters, String scope, AuthContext auth) {
        Map<String, Object> query =
                new LinkedHashMap<>(Map.of("attributes", "*", "limit", 100, "offset", 0));
        if (scope != null) query.put("scope", scope);
        return bpm.taskList("/system/v2/tasks:search", filters, query, auth);
    }

    public List<JsonNode> searchStatuses(
            List<String> statuses, List<String> scopes, JsonNode filters, AuthContext auth) {
        record Search(String status, String scope) {}
        List<Search> searches =
                statuses.stream()
                        .flatMap(status -> scopes.stream().map(scope -> new Search(status, scope)))
                        .toList();
        List<JsonNode> responses =
                parallel.map(
                        searches,
                        item -> {
                            var filter = copy(filters);
                            filter.set("status", object("value", item.status()));
                            return search(filter, item.scope(), auth);
                        });
        return unique(
                responses.stream()
                        .flatMap(response -> list(response.path("items")).stream())
                        .toList());
    }

    public List<JsonNode> broad(JsonNode filters, AuthContext auth) {
        List<JsonNode> responses = parallel.map(SCOPES, scope -> search(filters, scope, auth));
        return unique(
                responses.stream()
                        .flatMap(response -> list(response.path("items")).stream())
                        .toList());
    }

    public List<JsonNode> byDocument(String id, AuthContext auth) {
        return broad(
                        object(
                                "attributes",
                                object("documentId", object("value", id, "exact", true))),
                        auth)
                .stream()
                .filter(
                        task ->
                                ACTIVE.contains(text(task, "status"))
                                        && id.equals(attribute(task, "documentId")))
                .toList();
    }

    public JsonNode find(String id, AuthContext auth) {
        try {
            return bpm.taskList("/system/v1/user-tasks/" + encode(id), null, Map.of(), auth);
        } catch (ApiException error) {
            if (!WorkflowClient.unavailable(error)) throw error;
        }
        return searchStatuses(ACTIVE, SCOPES, object(), auth).stream()
                .filter(task -> id.equals(text(task, "id")))
                .findFirst()
                .orElse(null);
    }

    public JsonNode details(JsonNode task, AuthContext auth) {
        if (text(task, "formType").equals("COMPLETIONS")
                && task.path("completions").path("options").isArray()) return task;
        String id = encode(text(task, "id"));
        try {
            return bpm.system("/system/v6/usertasks/" + id, null, auth);
        } catch (ApiException error) {
            if (!WorkflowClient.unavailable(error)) throw error;
        }
        try {
            return bpm.taskList("/system/v1/user-tasks/" + id, null, Map.of(), auth);
        } catch (ApiException error) {
            if (!WorkflowClient.unavailable(error)) throw error;
        }
        return task;
    }

    public JsonNode forDocument(JsonNode document, AuthContext auth) {
        String id = text(document, "id");
        JsonNode cached = cache.get(auth, "task:" + id);
        if (cached != null && matches(cached, document) && expected(document, cached))
            return cached;
        try {
            JsonNode matched = best(byDocument(id, auth), document);
            if (matched != null) {
                remember(matched, auth);
                return matched;
            }
        } catch (ApiException error) {
            if (error.status() != 400) throw error;
        }
        List<JsonNode> tasks = searchStatuses(ACTIVE, SCOPES, object(), auth);
        if (tasks.isEmpty()) tasks = broad(object(), auth);
        JsonNode matched = best(tasks, document);
        if (matched != null) remember(matched, auth);
        return matched;
    }

    public JsonNode followUp(JsonNode document, String completedId, AuthContext auth) {
        cache.remove(auth, "task:" + text(document, "id"));
        for (int attempt = 0; attempt < 10; attempt++) {
            if (attempt > 0) ru.corelia.gateway.legacy.document.DocumentService.pause(250);
            List<JsonNode> tasks =
                    byDocument(text(document, "id"), auth).stream()
                            .filter(task -> !completedId.equals(text(task, "id")))
                            .toList();
            JsonNode next = best(tasks, document);
            if (next != null) {
                remember(next, auth);
                return next;
            }
        }
        return null;
    }

    public void remember(JsonNode task, AuthContext auth) {
        String id = attribute(task, "documentId");
        if (!id.isEmpty())
            cache.put(
                    auth,
                    "task:" + id,
                    config.number("PLATFORM_V_TASK_BY_DOCUMENT_CACHE_TTL_MS", 10000),
                    task);
    }

    public JsonNode withCachedState(JsonNode task, AuthContext auth) {
        JsonNode cached = cache.get(auth, "task:" + attribute(task, "documentId"));
        if (cached == null || !text(task, "id").equals(text(cached, "id"))) return task;
        var merged = copy(task);
        for (String key :
                List.of(
                        "status",
                        "executorRole",
                        "executorRoles",
                        "managerRole",
                        "managerRoles",
                        "assignee",
                        "assigneeName",
                        "assigneeLogin",
                        "executor",
                        "executorLogin",
                        "executorName",
                        "performer",
                        "performerLogin",
                        "performerName")) {
            if (cached.hasNonNull(key)) merged.set(key, cached.path(key));
        }
        return merged;
    }

    private static List<JsonNode> unique(List<JsonNode> tasks) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        tasks.forEach(task -> result.putIfAbsent(text(task, "id"), task));
        return new ArrayList<>(result.values());
    }

    private static boolean matches(JsonNode task, JsonNode document) {
        if (attribute(task, "documentId").equals(text(document, "id"))) return true;
        if (attribute(task, "contractNumber").equals(text(document, "contractNumber"))
                && attribute(task, "snils").equals(text(document, "snils"))) return true;
        String number = text(document, "contractNumber");
        return !number.isEmpty() && searchText(task).contains(number.toLowerCase(Locale.ROOT));
    }

    private static boolean expected(JsonNode document, JsonNode task) {
        String title = title(task);
        return switch (text(document, "approvalStatus")) {
            case "CREATED", "NEEDS_REVISION" ->
                    title.contains("оператор") && title.contains("взять");
            case "IN_WORK" -> title.contains("оператор") && !title.contains("взять");
            case "ON_APPROVAL" -> title.contains("соглас") || title.contains("approver");
            default -> false;
        };
    }

    private static int rank(JsonNode document, JsonNode task) {
        int status =
                switch (text(task, "status")) {
                    case "NEW" -> 0;
                    case "ASSIGNED" -> 1;
                    case "STARTED" -> 2;
                    default -> 3;
                };
        return (expected(document, task) ? 0 : 50) + status;
    }

    private static JsonNode best(List<JsonNode> tasks, JsonNode document) {
        return tasks.stream()
                .filter(task -> matches(task, document))
                .min(Comparator.comparingInt(task -> rank(document, task)))
                .orElse(null);
    }
}
