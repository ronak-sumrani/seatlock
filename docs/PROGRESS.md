# Progress

## Phase 0

- [x] Step 0.1 Maven multi-module monorepo (2026-10-05): Maven wrapper (3.9.16, only-script), Spring Boot
      3.5.16 parent, Java 21. Modules: `libs/common`, `services/user-catalog-service` (:8081),
      `services/inventory-service` (:8082), `services/booking-service` (:8083). Each service has
      actuator health (+ liveness/readiness probes), virtual threads on, and one smoke test that
      checks health is UP and that Boot's Tomcat virtual-thread customizer is active.
      Spotless (Palantir Java Format) `check` runs in `verify`; enforcer requires JDK 21 / Maven 3.9;
      failsafe runs `*IT` tests so `-DskipITs` works.
      Acceptance verified: `./mvnw -q verify` and `./mvnw spotless:check` pass; distinct ports; after
      `./mvnw install -DskipTests`, `./mvnw -pl services/inventory-service spring-boot:run` serves
      `/actuator/health` = UP (all three service jars also checked UP on 8081/8082/8083).
- [x] Step 0.2 Local infra + schema-per-service (2026-10-05): `deploy/docker-compose.yml` with
      Postgres 16 and Redis 7 (healthchecks, named volumes `pg-data`/`redis-data`). Host ports are
      **15432** and **16379**, because 5432/5433 were taken on this machine (native Postgres and
      other containers). Init script `deploy/postgres/init/01-schemas-and-roles.sh` creates schemas
      auth, catalog, inventory, booking, payment; roles `user_catalog_user` (owns auth + catalog),
      `inventory_user`, `booking_user`, `payment_user`, and `checker_ro` (SELECT everywhere via
      default privileges, read-only sessions). Credentials come from the repo-root `.env`
      (`cp .env.example .env`). Compose reads it via `env_file`, and the services via
      `spring.config.import`. Each service connects as its own role and runs Flyway
      (`V1__baseline.sql`, empty) in its own schema. ADR-011 written.
      Tests: `SchemaIsolationIT` (28 cases, in libs/common) and the three service smoke tests, now
      `*ApplicationIT` because they need Postgres. All use a shared Testcontainers fixture
      (`SeatLockPostgres`, common test-jar) that runs the real init script. Note: `-DskipITs` now
      runs no service tests.
      Acceptance verified: `./mvnw verify` green (34 ITs); a mutation check (drop default privileges)
      makes the checker test fail; all three services UP against Compose and Flyway applied v1 as
      the service role; manual psql check shows `permission denied for schema booking` for
      inventory_user. Changing the init script needs `docker compose -f deploy/docker-compose.yml down -v`.

## v0.1

- [ ] 

## v0.2

- [ ] 

## v0.3

- [ ] 

## v1.0

- [ ] 
