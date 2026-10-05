# Документация gateway

Публичные document, attachment, task и workflow-admin маршруты сгруппированы
под `/api/core/v1`; `GET /api/core/v1/auth/me` возвращает представление
аутентифицированного пользователя. Gateway не открывает `/internal/v1` и не
должен превращать transport mapping в дублирование domain logic.

JWT проверяется до обращения к upstream. Внутренний caller identity задаёт
mTLS certificate, а пользовательский контекст передаётся внутреннему сервису
для его собственной авторизации. См. [API](../../docs/api.md) и
[operations](../../docs/operations.md).
