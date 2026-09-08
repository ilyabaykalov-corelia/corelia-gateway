package ru.corelia.gateway.legacy.document;

import java.util.Locale;

/** Статусы и подписи действующего клиентского контракта; переходы определяет BPMN. */
public enum ApprovalStatus {
    CREATED("Создан"),
    IN_WORK("В работе"),
    ON_APPROVAL("На согласовании"),
    NEEDS_REVISION("Отправлено на доработку"),
    APPROVED("Согласован"),
    REJECTED("Отклонен");

    private final String label;

    ApprovalStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean terminal() {
        return this == APPROVED || this == REJECTED;
    }

    public String tone() {
        return this == REJECTED ? "error" : this == NEEDS_REVISION ? "warning" : "success";
    }

    public static ApprovalStatus parse(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("на доработке")) return NEEDS_REVISION;
        if (value.equals("отклонён")) return REJECTED;
        for (var status : values()) {
            if (status.name().equalsIgnoreCase(value)
                    || status.label.toLowerCase(Locale.ROOT).equals(value)) return status;
        }
        return null;
    }

    public static ApprovalStatus normalize(String value) {
        var status = parse(value);
        return status == null ? CREATED : status;
    }
}
