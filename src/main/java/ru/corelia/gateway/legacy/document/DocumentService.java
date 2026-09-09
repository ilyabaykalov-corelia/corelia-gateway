package ru.corelia.gateway.legacy.document;

import static ru.corelia.support.Json.*;

import org.springframework.stereotype.Service;

import ru.corelia.auth.AuthContext;
import ru.corelia.cache.TaskCache;
import ru.corelia.http.ApiException;
import ru.corelia.transport.ServiceClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Адаптер прежнего плоского API React к универсальной карточке Corelia. */
@Service
public class DocumentService {
    private final ServiceClient services;
    private final AttachmentService attachments;
    private final TaskCache cache;

    public DocumentService(ServiceClient services, AttachmentService attachments, TaskCache cache) {
        this.services = services;
        this.attachments = attachments;
        this.cache = cache;
    }

    private String path() {
        return "/internal/v1/documents/" + "PDS_CONTRACT";
    }

    public JsonNode raw(String id, AuthContext auth) {
        try {
            return services.call("document", path() + "/" + encode(id), "GET", null, auth);
        } catch (ApiException error) {
            if (error.status() == 404) throw new ApiException(404, "Договор ПДС не найден");
            throw error;
        }
    }

    public ObjectNode get(String id, AuthContext auth) {
        ObjectNode result = legacy(raw(id, auth));
        result.set("attachments", array(attachments.current(id, auth)));
        return result;
    }

    public JsonNode versions(String id, AuthContext auth) {
        JsonNode response =
                services.call(
                        "document",
                        path() + "/" + encode(id) + "/versions",
                        "GET",
                        null,
                        auth);
        List<JsonNode> currentAttachments = attachments.current(id, auth);
        return array(
                list(response).stream()
                        .map(document -> {
                            ObjectNode result = legacy(document);
                            result.set("attachments", array(currentAttachments));
                            return result;
                        })
                        .toList());
    }

    public ObjectNode version(String id, int version, AuthContext auth) {
        return list(versions(id, auth)).stream()
                .filter(item -> item.path("version").asInt() == version)
                .map(item -> (ObjectNode) item)
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "Версия документа не найдена"));
    }

    public JsonNode types(AuthContext auth) {
        return services.call(
                "document", "/internal/v1/document-types/available", "GET", null, auth);
    }

    public JsonNode search(JsonNode body, AuthContext auth) {
        String type = text(body, "documentTypeId");
        if (!type.isEmpty() && !"PDS_CONTRACT".equals(type))
            return object("items", List.of(), "total", 0);
        JsonNode response = services.call("document", path() + "/search", "POST", body, auth);
        return object(
                "items",
                list(response.path("items")).stream().map(this::legacy).toList(),
                "total",
                response.path("total"));
    }

    public Map<String, JsonNode> byId(AuthContext auth) {
        JsonNode response =
                services.call("document", path() + "/search", "POST", object("limit", 10000), auth);
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode doc : list(response.path("items"))) result.put(text(doc, "id"), legacy(doc));
        return result;
    }

    public ObjectNode create(JsonNode body, AuthContext auth) {
        ObjectNode result = legacy(services.call("document", path(), "POST", payload(body), auth));
        cache.invalidate();
        return result;
    }

    public ObjectNode update(String id, JsonNode body, AuthContext auth) {
        services.call("document", path() + "/" + encode(id), "PATCH", payload(body), auth);
        cache.invalidate();
        return get(id, auth);
    }

    private JsonNode payload(JsonNode body) {
        parsePayload(body);
        String type = fallback(text(body, "documentTypeId"), "PDS_CONTRACT");
        if (!"PDS_CONTRACT".equals(type))
            throw new ApiException(
                    400, "Этот тип документа доступен через универсальный API Corelia");
        ObjectNode attributes = object();
        attributes.put("contractDate", first(body, "contractDate", "date", "outgoingDocumentDate"));
        attributes.put("contractNumber", first(body, "contractNumber", "number", "outgoingNumber"));
        attributes.put("snils", text(body, "snils"));
        return object("attributes", attributes);
    }

    private ObjectNode legacy(JsonNode document) {
        ObjectNode result =
                object(
                        "id",
                        text(document, "id"),
                        "documentTypeId",
                        text(document, "typeCode"),
                        "documentType",
                        text(document, "typeName"),
                        "approvalStatus",
                        text(document, "status"),
                        "documentStatus",
                        text(document, "statusLabel"),
                        "attachments",
                        List.of());
        document.path("attributes").properties().forEach(e -> result.set(e.getKey(), e.getValue()));
        for (String field : List.of("createdBy", "createdAt", "processInstanceId"))
            if (document.has(field)) result.set(field, document.path(field));
        return result;
    }

    public static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "Ожидание платформы прервано");
        }
    }

    public static ObjectNode parsePayload(JsonNode payload) {
        String type = fallback(text(payload, "documentTypeId"), "PDS_CONTRACT");
        String date = first(payload, "contractDate", "date", "outgoingDocumentDate");
        String number = first(payload, "contractNumber", "number", "outgoingNumber");
        String snils = text(payload, "snils");
        if (date.isEmpty() || number.isEmpty() || snils.isEmpty())
            throw new ApiException(400, "Заполнены не все обязательные атрибуты договора ПДС");
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new ApiException(400, "Дата договора должна передаваться в формате YYYY-MM-DD");
        if (number.length() > 64)
            throw new ApiException(400, "Номер договора не должен превышать 64 символа");
        if (!snils.matches("\\d{3}-\\d{3}-\\d{3} \\d{2}"))
            throw new ApiException(400, "СНИЛС должен быть в формате 000-000-000 00");
        return object(
                "documentTypeId",
                type,
                "contractDate",
                date,
                "contractNumber",
                number,
                "snils",
                snils);
    }
}
