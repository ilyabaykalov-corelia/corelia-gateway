package ru.corelia.gateway.legacy.task;

import static ru.corelia.gateway.legacy.task.TaskPresentation.*;
import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Service;

import ru.corelia.auth.AuthContext;
import ru.corelia.cache.TaskCache;
import ru.corelia.gateway.WorkflowClient;
import ru.corelia.gateway.legacy.document.*;
import ru.corelia.http.ApiException;
import ru.corelia.support.ParallelCalls;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Оркестрирует задачи платформы, используя варианты завершения из формы COMPLETIONS. */
@Service
public class TaskService {
    private static final Set<String> STATUSES =
            Set.of("NEW", "ASSIGNED", "STARTED", "COMPLETED", "ABORTED");
    private final TaskGateway gateway;
    private final TaskPresentation presentation;
    private final DocumentService documents;
    private final WorkflowClient bpm;
    private final TaskCache cache;
    private final ParallelCalls parallel;

    public TaskService(
            TaskGateway gateway,
            TaskPresentation presentation,
            DocumentService documents,
            WorkflowClient bpm,
            TaskCache cache,
            ParallelCalls parallel) {
        this.gateway = gateway;
        this.presentation = presentation;
        this.documents = documents;
        this.bpm = bpm;
        this.cache = cache;
        this.parallel = parallel;
    }

    public JsonNode search(JsonNode payload, AuthContext auth) {
        return cache.get(auth, "search", payload, () -> searchUncached(payload, auth));
    }

    private JsonNode searchUncached(JsonNode payload, AuthContext auth) {
        String query = text(payload, "query"),
                status = text(payload, "status").toUpperCase(Locale.ROOT),
                queue = text(payload, "queue").toUpperCase(Locale.ROOT);
        ObjectNode filters = object();
        if (!query.isEmpty()) filters.put("query", query);
        if (STATUSES.contains(status)) filters.set("status", object("value", status));
        if (queue.equals("MY") || queue.equals("AVAILABLE")) {
            filters.remove("query");
            List<String> statuses =
                    STATUSES.contains(status)
                            ? List.of(status)
                            : queue.equals("AVAILABLE")
                                    ? List.of("NEW", "ASSIGNED")
                                    : List.of("STARTED");
            List<JsonNode> tasks =
                    gateway.searchStatuses(statuses, List.of("EXECUTOR"), filters, auth).stream()
                            .map(task -> gateway.withCachedState(task, auth))
                            .filter(
                                    task ->
                                            queue.equals("MY")
                                                    ? login(task).equals(auth.login())
                                                    : login(task).isEmpty())
                            .toList();
            List<JsonNode> visible =
                    visible(tasks, auth).stream()
                            .filter(
                                    task ->
                                            searchText(task)
                                                    .contains(query.toLowerCase(Locale.ROOT)))
                            .toList();
            List<JsonNode> items = parallel.map(visible, task -> withTaskActions(task, auth));
            return object("items", items, "total", items.size());
        }
        JsonNode result = gateway.search(filters, null, auth);
        List<JsonNode> items =
                parallel.map(list(result.path("items")), task -> withTaskActions(task, auth));
        return object("items", items, "total", number(result, "count", items.size()));
    }

    public JsonNode summary(AuthContext auth) {
        return cache.get(
                auth,
                "summary",
                object(),
                () -> {
                    List<JsonNode> tasks =
                            new ArrayList<>(
                                    gateway.searchStatuses(
                                            List.of("STARTED"),
                                            List.of("EXECUTOR"),
                                            object(),
                                            auth));
                    tasks.addAll(
                            gateway.searchStatuses(
                                    List.of("NEW", "ASSIGNED"),
                                    List.of("EXECUTOR"),
                                    object(),
                                    auth));
                    List<JsonNode> visible =
                            visible(
                                    tasks.stream()
                                            .map(task -> gateway.withCachedState(task, auth))
                                            .toList(),
                                    auth);
                    return object(
                            "my",
                            visible.stream()
                                    .filter(task -> login(task).equals(auth.login()))
                                    .count(),
                            "available",
                            visible.stream().filter(task -> login(task).isEmpty()).count());
                });
    }

    private List<JsonNode> visible(List<JsonNode> tasks, AuthContext auth) {
        Map<String, JsonNode> docs = documents.byId(auth);
        return tasks.stream()
                .filter(
                        task -> {
                            JsonNode doc = docs.get(attribute(task, "documentId"));
                            return doc != null
                                    && !ApprovalStatus.normalize(text(doc, "approvalStatus"))
                                            .terminal();
                        })
                .toList();
    }

    public ObjectNode withWorkflow(JsonNode document, AuthContext auth) {
        if (terminal(document)) return withDocumentTask(document, null, auth);
        JsonNode task = gateway.forDocument(document, auth);
        return task == null && queuedStatus(text(document, "approvalStatus"))
                ? queued(document, auth)
                : withDocumentTask(document, task, auth);
    }

    private ObjectNode withDocumentTask(JsonNode document, JsonNode task, AuthContext auth) {
        ObjectNode result = copy(document);
        result.set(
                "availableActions",
                array(terminal(document) || task == null ? List.of() : actions(task, auth)));
        result.set(
                "executor",
                MAPPER.valueToTree(terminal(document) ? null : presentation.executor(task, auth)));
        return result;
    }

    private ObjectNode queued(JsonNode document, AuthContext auth) {
        String status = text(document, "approvalStatus");
        if (!queuedStatus(status) || terminal(document))
            return withDocumentTask(document, null, auth);
        boolean approval = status.equals("ON_APPROVAL");
        String role = approval ? "approver" : "document_operator";
        ObjectNode result = copy(document);
        result.set("availableActions", array(List.of()));
        result.set(
                "executor",
                object(
                        "login",
                        null,
                        "name",
                        null,
                        "role",
                        role,
                        "roleLabel",
                        presentation.roleLabel(role, auth),
                        "taskStatus",
                        "NEW",
                        "taskTitle",
                        "Взять договор ПДС в работу " + (approval ? "согласующим" : "оператором")));
        return result;
    }

    private static boolean terminal(JsonNode document) {
        return ApprovalStatus.normalize(text(document, "approvalStatus")).terminal();
    }

    private static boolean queuedStatus(String status) {
        return Set.of("CREATED", "NEEDS_REVISION", "ON_APPROVAL").contains(status);
    }

    private static boolean waitQueued(String status) {
        return Set.of("NEEDS_REVISION", "ON_APPROVAL").contains(status);
    }

    private JsonNode documentForTask(JsonNode task, AuthContext auth) {
        String id = attribute(task, "documentId");
        if (id.isEmpty()) return null;
        try {
            return documents.get(id, auth);
        } catch (ApiException error) {
            if (error.status() == 404) return null;
            throw error;
        }
    }

    private ObjectNode withTaskActions(JsonNode task, AuthContext auth) {
        ObjectNode result = copy(task);
        result.set(
                "availableActions",
                array(documentForTask(task, auth) == null ? List.of() : actions(task, auth)));
        return result;
    }

    public List<JsonNode> actions(JsonNode task, AuthContext auth) {
        JsonNode detail = gateway.details(task, auth);
        if (!text(detail, "formType").equals("COMPLETIONS")) return List.of();
        List<JsonNode> actions = new ArrayList<>();
        int index = 0;
        for (JsonNode option : list(detail.path("completions").path("options"))) {
            index++;
            ObjectNode parameters = copy(option.path("result"));
            if (parameters.isEmpty()) continue;
            ApprovalStatus status = ApprovalStatus.parse(text(parameters, "approvalStatus"));
            if (status == ApprovalStatus.CREATED) status = null;
            String code =
                    status == null
                            ? fallback(text(option, "label"), "completion_" + index)
                            : status.name();
            ObjectNode action =
                    object(
                            "code",
                            code,
                            "label",
                            fallback(text(option, "label"), code),
                            "tone",
                            status == null ? "success" : status.tone(),
                            "result",
                            parameters);
            if (status != null) action.put("status", status.name());
            actions.add(action);
        }
        return actions;
    }

    public JsonNode start(String id, AuthContext auth) {
        JsonNode task = requireTask(id, auth);
        JsonNode started = startForUser(task, auth);
        cache.invalidate();
        gateway.remember(started, auth);
        return object("successIds", List.of(id));
    }

    private JsonNode startForUser(JsonNode task, AuthContext auth) {
        String id = text(task, "id");
        JsonNode result = bpm.system("/system/v6/usertasks:start", clientFields(id, auth), auth);
        assertSuccess(result, id, "запуск");
        ObjectNode started = copy(task).put("status", "STARTED");
        JsonNode detail = gateway.details(started, auth);
        gateway.remember(detail, auth);
        return detail;
    }

    public JsonNode complete(String id, JsonNode payload, AuthContext auth) {
        ApprovalStatus status = ApprovalStatus.parse(first(payload, "approvalStatus", "decision"));
        JsonNode task = gateway.find(id, auth);
        if (status == null)
            throw new ApiException(
                    400, "Для завершения задачи передайте корректный approvalStatus");
        if (task == null) throw new ApiException(404, "Активная задача не найдена");
        if (!text(task, "status").equals("STARTED")) startForUser(task, auth);
        ObjectNode params = object("approvalStatus", status.name());
        if (title(task).contains("взять")) params.put("assignee", auth.login());
        ObjectNode body = clientFields(id, auth);
        body.set("parameters", params);
        JsonNode result = bpm.system("/system/v6/usertasks:complete", body, auth);
        cache.invalidate();
        return result;
    }

    public JsonNode action(String id, JsonNode payload, AuthContext auth) {
        JsonNode task = requireTask(id, auth);
        requireAction(payload, false);
        JsonNode document = documentForTask(task, auth);
        if (document == null) throw new ApiException(404, "Документ для задачи не найден");
        return execute(task, document, payload, auth, true);
    }

    public JsonNode approval(String id, JsonNode payload, AuthContext auth) {
        requireAction(payload, true);
        JsonNode document = documents.get(id, auth);
        JsonNode task = gateway.forDocument(document, auth);
        if (task == null)
            throw new ApiException(404, "Активная задача согласования для документа не найдена");
        return execute(task, document, payload, auth, false);
    }

    private JsonNode execute(
            JsonNode task,
            JsonNode document,
            JsonNode payload,
            AuthContext auth,
            boolean taskResponse) {
        String code = actionCode(payload);
        JsonNode parameters = payload.path("parameters");
        JsonNode action =
                actions(task, auth).stream()
                        .filter(
                                item ->
                                        !code.isEmpty() && code.equals(text(item, "code"))
                                                || parameters.isObject()
                                                        && parameters.equals(item.path("result")))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                400,
                                                "Действие \""
                                                        + code
                                                        + "\" недоступно для текущей задачи"));
        ApprovalStatus status = ApprovalStatus.parse(text(action.path("result"), "approvalStatus"));
        if (status == null)
            throw new ApiException(
                    400, "Для завершения задачи передайте корректный approvalStatus");
        String taskId = text(task, "id"), documentId = text(document, "id");
        if (!text(task, "status").equals("STARTED")) startForUser(task, auth);
        ObjectNode completion = copy(action.path("result"));
        boolean takeInWork = takeInWork(action);
        if (takeInWork) completion.put("assignee", auth.login());
        ObjectNode body = clientFields(taskId, auth);
        body.set("parameters", completion);
        assertSuccess(
                bpm.system("/system/v6/usertasks:complete", body, auth), taskId, "завершение");
        cache.invalidate();
        JsonNode updated = documents.get(documentId, auth);
        for (int attempt = 0;
                attempt < 4 && !status.name().equals(text(updated, "approvalStatus"));
                attempt++) {
            DocumentService.pause(250);
            updated = documents.get(documentId, auth);
        }
        JsonNode next =
                takeInWork || waitQueued(status.name())
                        ? gateway.followUp(updated, taskId, auth)
                        : null;
        if (next != null && takeInWork) next = withTaskActions(startForUser(next, auth), auth);
        ObjectNode decorated =
                next != null
                        ? withDocumentTask(updated, next, auth)
                        : waitQueued(status.name())
                                ? queued(updated, auth)
                                : withWorkflow(updated, auth);
        if (!taskResponse) return decorated;
        ObjectNode completed = copy(task).put("status", "COMPLETED");
        completed.set("availableActions", array(List.of()));
        return object("task", next == null ? completed : next, "document", decorated);
    }

    private JsonNode requireTask(String id, AuthContext auth) {
        JsonNode task = gateway.find(id, auth);
        if (task == null) throw new ApiException(404, "Активная задача не найдена");
        return task;
    }

    private static ObjectNode clientFields(String id, AuthContext auth) {
        return object(
                "clientLogin",
                auth.login(),
                "clientName",
                fallback(auth.fullName(), auth.login()),
                "userTaskIds",
                List.of(id));
    }

    private static boolean takeInWork(JsonNode action) {
        return Set.of("взять в работу", "take_on")
                        .contains(text(action, "label").toLowerCase(Locale.ROOT))
                || text(action, "code").equalsIgnoreCase("take_on");
    }

    private static String actionCode(JsonNode payload) {
        String raw = first(payload, "actionCode", "approvalStatus", "decision");
        String upper = raw.toUpperCase(Locale.ROOT);
        return Set.of("IN_WORK", "ON_APPROVAL", "NEEDS_REVISION", "APPROVED", "REJECTED")
                        .contains(upper)
                ? upper
                : raw;
    }

    private static void requireAction(JsonNode payload, boolean document) {
        if (actionCode(payload).isEmpty() && !payload.path("parameters").isObject()) {
            throw new ApiException(
                    400,
                    document
                            ? "Для решения по документу передайте actionCode или parameters"
                            : "Для действия по задаче передайте actionCode или parameters");
        }
    }

    public static void assertSuccess(JsonNode result, String taskId, String action) {
        JsonNode operation =
                list(result.path("operationResults")).stream()
                        .filter(item -> taskId.equals(text(item, "userTaskId")))
                        .findFirst()
                        .orElse(object());
        String response = text(operation, "responseType");
        if (response.equals("SUCCESS")) return;
        if (!response.isEmpty())
            throw new ApiException(
                    502,
                    "BPMX: "
                            + action
                            + " не выполнено для задачи "
                            + taskId
                            + ": "
                            + response
                            + (text(operation, "message").isEmpty()
                                    ? ""
                                    : ": " + text(operation, "message")));
        if (list(result.path("successIds")).stream().anyMatch(id -> taskId.equals(text(id))))
            return;
        if (!result.path("successIds").isArray() && !result.path("failedIds").isArray()) return;
        throw new ApiException(
                502,
                "Task List: "
                        + action
                        + " не выполнено для задачи "
                        + taskId
                        + (text(result, "message").isEmpty()
                                ? ""
                                : ": " + text(result, "message")));
    }
}
