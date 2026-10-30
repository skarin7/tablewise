# tablewise-api

Spring Boot 3.3 / Java 21 backend for the Tablewise POS. Spec: `../specs/tablewise-backend-handoff/README.md`.
Migrations in `src/main/resources/db/migration` are the spec's files, unchanged.

## Run locally
```
docker compose up -d                       # Postgres 16
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```
`local` accepts the bearer token `dev-<auth user uuid>` (matches `staff_members.user_id`). Never enable it on Render.

## Test
`mvn verify` (needs Docker; Testcontainers starts Postgres 16). One test class per milestone:
`SkeletonTest` (M1) · `AuthTest` (M2) · `MenuInventoryTest` (M3) · `OrderTest`, `BillCalculatorTest` (M4) · `RealtimeTest` (M5) ·
`PaymentTest` (M6) · `DeviceTest` (M7) · `OnlineOrderTest` (M8) · `ReportTest` (M9) · `AdminTest` (M10).

## Deploy (Render, Docker, Singapore)
`render.yaml` is a blueprint; set the `sync: false` variables from `.env.example`. Health check: `/actuator/health`.
Keep one instance (in-memory STOMP broker and `@Scheduled` jobs, README 12.3).

## Client contracts the spec leaves open
- Staff with several outlets send `X-Outlet-Id`. STOMP CONNECT takes `Authorization: Bearer <jwt>` (staff) or `X-Device-Token` (display).
- A display sends any STOMP SEND (e.g. to `/app/heartbeat`) every ~10 s to stay "online".
- Platform webhooks (`POST /webhooks/{swiggy|zomato|website}`) use a canonical JSON signed with `PLATFORM_WEBHOOK_SECRET` in `X-Signature`;
  see `LoggingPlatformAdapter`. Replace it with real adapters when partner access exists.
