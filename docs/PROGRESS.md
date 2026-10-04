# Progress

## Phase 0

- [x] Maven multi-module monorepo (2026-10-05): Maven wrapper (3.9.16, only-script), Spring Boot
      3.5.16 parent, Java 21. Modules: `libs/common`, `services/user-catalog-service` (:8081),
      `services/inventory-service` (:8082), `services/booking-service` (:8083). Each service has
      actuator health (+ liveness/readiness probes), virtual threads on, and one smoke test that
      checks health is UP and that Boot's Tomcat virtual-thread customizer is active.
      Spotless (Palantir Java Format) `check` runs in `verify`; enforcer requires JDK 21 / Maven 3.9;
      failsafe runs `*IT` tests so `-DskipITs` works.

## v0.1

- [ ] 

## v0.2

- [ ] 

## v0.3

- [ ] 

## v1.0

- [ ] 
