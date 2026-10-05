# corelia-gateway

Единственная публичная прикладная точка Corelia: `/api/core/v1`. Gateway
проверяет JWT, принимает запросы browser/API clients и вызывает внутренние
document-, workflow- и attachment-service через mTLS. Он не является
владельцем document state, business permissions или BPMN transition.

В Compose gateway слушает host port `7170`; внутренние сервисы не публикуют
business ports на host. Для запуска необходимы JWT issuer/JWKS/audiences,
CORS origin и сертификаты для внутренних вызовов. `MAX_BODY_SIZE_MB` задаёт
JSON body limit, `CORELIA_UPSTREAM_MAX_RESPONSE_SIZE_MB` — максимум upstream
ответа.

```bash
mvn -pl corelia-gateway -am test
./scripts/up.sh
```

Полный внешний контракт: [API](../docs/api.md); маршруты web-клиента:
[corelia-web](../corelia-web/README.md).
