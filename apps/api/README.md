# api

Spring Boot staff API. Entry point: `POST/GET /api/v1/facilities`.

Unauthenticated health routes outside the versioned prefix: `GET /health/live` (process up) and `GET /health/ready` (database reachable).

Run `bash check.sh` from this directory for unit tests, real-PostgreSQL integration tests and the enforced 100% JaCoCo gate. The integration test creates the synthetic database `aurevia_it` and applies `../../db/migrations` with the migration role; the application connects as the non-owner runtime role.

Protected requests require `X-Synthetic-Subject` and `X-Active-Organization` while the `synthetic-auth` profile is active. `X-Correlation-Id` is optional and echoed when provided. See `../../contracts/openapi.yaml` and `../../docs/decisions/0001-g0-tenant-isolation.md`.

## Production configuration (WEB-01, WEB-05)

Start with `SPRING_PROFILES_ACTIVE=production` to load `application-production.yml`. `ProductionSafetyCheck` fails startup closed if `synthetic-auth` is also active, if the database password is blank, or if the datasource URL does not contain `sslmode=require` or `verify-full`. Failures never include the password. All values are read from the environment; nothing below is stored in the repository. The profile trusts `X-Forwarded-*` headers from the TLS-terminating edge; the deployment must ensure callers cannot inject those headers directly.

| Name | Meaning | Default | Required in production |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Active Spring profiles; must include `production` and must not include `synthetic-auth` | none | Yes |
| `AUREVIA_DB_URL` | JDBC URL; must contain `sslmode=require` or `verify-full` | `jdbc:postgresql://127.0.0.1:5432/aurevia_it` (test only) | Yes |
| `AUREVIA_DB_USER` | Database role used by the application | `aurevia_app` | Yes |
| `AUREVIA_DB_PASSWORD` | Database password; must be non-blank | empty | Yes |
| `AUREVIA_DB_POOL` | Hikari maximum pool size (production profile only) | `20` | No |
| `AUREVIA_SERVER_PORT` | HTTP listen port | `8081` | No |
| `AUREVIA_RATE_LIMIT_PER_MINUTE` | Instance-local request limit (the edge WAF supplies the global limit) | `600` | No |
| `AUREVIA_OIDC_ISSUER_URI` | OIDC issuer for staff web and bearer tokens | empty | Yes, outside `synthetic-auth` |
| `AUREVIA_OIDC_AUDIENCE` | Expected OIDC token audience | empty | Recommended (fail-closed audience enforcement is a separate story) |
| `AUREVIA_OIDC_CLIENT_ID` | Staff web OIDC client id | empty | Yes, outside `synthetic-auth` |
| `AUREVIA_OIDC_CLIENT_SECRET` | Staff web OIDC client secret | empty | Yes, outside `synthetic-auth` |
