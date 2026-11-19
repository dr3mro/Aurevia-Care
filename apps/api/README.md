# api

Spring Boot staff API. Entry point: `POST/GET /api/v1/facilities`.

Run `bash check.sh` from this directory for unit tests, real-PostgreSQL integration tests and the enforced 100% JaCoCo gate. The integration test creates the synthetic database `smartclinic_it` and applies `../../db/migrations` with the migration role; the application connects as the non-owner runtime role.

Protected requests require `X-Synthetic-Subject` and `X-Active-Organization` while the `synthetic-auth` profile is active. `X-Correlation-Id` is optional and echoed when provided. See `../../contracts/openapi.yaml` and `../../docs/decisions/0001-g0-tenant-isolation.md`.
