package ru.corelia.gateway;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Component;

import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.profile.ProductProfile;
import ru.corelia.transport.ServiceClient;

import tools.jackson.databind.JsonNode;

import java.util.*;

/** Переводит операции совместимости в конечный набор команд сервиса процессов. */
@Component
public class WorkflowClient {
    private final ServiceClient services;
    private final ProductProfile profile;

    public WorkflowClient(ServiceClient services, ProductProfile profile) {
        this.services = services;
        this.profile = profile;
    }

    public JsonNode taskList(String path, JsonNode body, Map<String, ?> query, AuthContext auth) {
        if (path.equals("/system/v2/tasks:search"))
            return services.call(
                    "workflow",
                    "/internal/v1/tasks/search",
                    "POST",
                    object("filters", body, "scope", query.get("scope")),
                    auth);
        String prefix = "/system/v1/user-tasks/";
        if (path.startsWith(prefix))
            return services.call(
                    "workflow",
                    "/internal/v1/tasks/" + path.substring(prefix.length()),
                    "GET",
                    null,
                    auth);
        throw new IllegalArgumentException("Неизвестная операция задач");
    }

    public JsonNode system(String path, JsonNode body, AuthContext auth) {
        String prefix = "/system/v6/usertasks/";
        if (path.startsWith(prefix))
            return services.call(
                    "workflow",
                    "/internal/v1/tasks/" + path.substring(prefix.length()) + "/details",
                    "GET",
                    null,
                    auth);
        String action =
                switch (path) {
                    case "/system/v6/usertasks:start" -> "start";
                    case "/system/v6/usertasks:complete" -> "complete";
                    default -> throw new IllegalArgumentException("Неизвестная команда задачи");
                };
        List<JsonNode> ids = list(body.path("userTaskIds"));
        if (ids.size() != 1) throw new ApiException(400, "Команда должна содержать одну задачу");
        return services.call(
                "workflow",
                "/internal/v1/tasks/" + encode(text(ids.getFirst())) + "/" + action,
                "POST",
                body,
                auth);
    }

    public JsonNode roleLabels(AuthContext auth) {
        var labels = object();
        for (String role : auth.roles()) {
            JsonNode response =
                    services.call(
                            "workflow", "/internal/v1/roles/" + encode(role), "GET", null, auth);
            labels.set(role, response.path("label"));
        }
        return labels;
    }

    public static boolean unavailable(ApiException error) {
        return Set.of(400, 404, 405).contains(error.status());
    }
}
