# api

Spring Boot staff API. Entry point: `POST/GET /api/v1/facilities`.

Unauthenticated health routes outside the versioned prefix: `GET /health/live` (process up) and `GET /health/ready` (database reachable).

Run `bash check.sh` from this directory for unit tests, real-PostgreSQL integration tests and the enforced 100% JaCoCo gate. The integration test creates the synthetic database `orevia_it` and applies `../../db/migrations` with the migration role; the application connects as the non-owner runtime role.

Protected requests require `X-Synthetic-Subject` and `X-Active-Organization` while the `synthetic-auth` profile is active. `X-Correlation-Id` is optional and echoed when provided. See `../../contracts/openapi.yaml` and `../../docs/decisions/0001-g0-tenant-isolation.md`.

## Production configuration (WEB-01, WEB-05)

Start with `SPRING_PROFILES_ACTIVE=production` to load `application-production.yml`. `ProductionSafetyCheck` fails startup closed if `synthetic-auth` is also active, if the database password is blank, or if the datasource URL does not contain `sslmode=require` or `verify-full`. Failures never include the password. All values are read from the environment; nothing below is stored in the repository. The profile trusts `X-Forwarded-*` headers from the TLS-terminating edge; the deployment must ensure callers cannot inject those headers directly.

| Name | Meaning | Default | Required in production |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Active Spring profiles; must include `production` and must not include `synthetic-auth` | none | Yes |
| `OREVIA_DB_URL` | JDBC URL; must contain `sslmode=require` or `verify-full` | `jdbc:postgresql://127.0.0.1:5432/orevia_it` (test only) | Yes |
| `OREVIA_DB_USER` | Database role used by the application | `orevia_app` | Yes |
| `OREVIA_DB_PASSWORD` | Database password; must be non-blank | empty | Yes |
| `OREVIA_DB_POOL` | Hikari maximum pool size (production profile only) | `20` | No |
| `OREVIA_SERVER_PORT` | HTTP listen port | `8081` | No |
| `OREVIA_RATE_LIMIT_PER_MINUTE` | Instance-local request limit (the edge WAF supplies the global limit) | `600` | No |
| `OREVIA_OIDC_ISSUER_URI` | OIDC issuer for staff web and bearer tokens | empty | Yes, outside `synthetic-auth` |
| `OREVIA_OIDC_AUDIENCE` | Expected OIDC token audience | empty | Recommended (fail-closed audience enforcement is a separate story) |
| `OREVIA_OIDC_CLIENT_ID` | Staff web OIDC client id | empty | Yes, outside `synthetic-auth` |
| `OREVIA_OIDC_CLIENT_SECRET` | Staff web OIDC client secret | empty | Yes, outside `synthetic-auth` |
