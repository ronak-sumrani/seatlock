# SeatLock
Ticketing backend for a 100k-users / 5k-seats on-sale. Portfolio project: correctness,
provable claims and honest trade-offs matter more than features.

## Source of truth
- Spec: docs/spec/SeatLock_Project_Specification_v2.md. Read the relevant section before
  designing or changing anything. If the code and the spec disagree, stop and ask me.
- Plain-language walkthrough: docs/spec/SeatLock_Architecture_Walkthrough.md
- Decisions made while building: docs/adr/
- Progress log: docs/PROGRESS.md (update it at the end of every step)

## Invariants (never break these; every change must keep them provable)
- I1: a seat belongs to at most one confirmed booking, ever.
- I2: every successful charge ends in tickets or a refund.
- R1: one active booking per user per event. R2: max 6 seats per user per event.
  R3: no booking stays non-terminal forever.

## Stack rules
- Java 21, Spring Boot 3.x, Maven multi-module monorepo (layout in spec §17).
- Postgres: one schema and one DB user per service. Flyway; never edit an applied
  migration, add a new one.
- Hot paths (hold, confirm, sweeper, outbox) use JdbcTemplate/native SQL, not JPA.
- Redis via Spring Data Redis (Lettuce) with hand-written Lua. No Redisson.
- Money is integer minor units (long). Never double or float.
- All timings are config properties (seatlock.hold.ttl etc.); tests use seconds.
- libs/common holds infrastructure helpers only, never domain entities.
- Errors are RFC 7807 problem+json with a stable "code" field (spec §18).

## Commands
- Install modules to ~/.m2 (needed before `-pl` without `-am`, e.g. spring-boot:run):
  ./mvnw install -DskipTests
- Run one service: ./mvnw -pl services/<name> spring-boot:run
- Build + unit tests: ./mvnw -q verify -DskipITs
- One module: ./mvnw -q -pl services/<name> -am verify
- Everything incl. Testcontainers (needs Docker): ./mvnw verify
- Local stack: docker compose -f deploy/docker-compose.yml up -d
- Format: ./mvnw spotless:apply

## Workflow
- Every behaviour change comes with a test. Concurrency claims need a concurrency test.
- Run the relevant tests before saying something is done and show me the output.
- Never skip, disable, or weaken a test to make it pass. Fix the cause.
- Small commits with conventional messages (feat:, fix:, test:, docs:, chore:).
- I am learning: for non-obvious code (SQL locking, Lua, saga transitions, version
  handling) add a short comment explaining WHY.
- I'm on native Windows: use Git Bash-compatible commands, and keep shell scripts LF.
