# Folder structure

What is in the repo today (after step 0.2). The target layout is in spec §17;
folders listed there but missing here (api-gateway, payment-service, realtime-service,
tools/, frontend/, load-tests/, chaos/, .github/workflows/) come in later steps.

```
SeatLock/
├─ CLAUDE.md                        # working rules and invariants for Claude Code
├─ pom.xml                          # Maven parent: modules, Java 21, Spring Boot, Spotless
├─ mvnw, mvnw.cmd, .mvn/wrapper/    # Maven wrapper
├─ .env.example                     # DB credentials template; copy to .env (gitignored)
├─ .gitattributes                   # line endings: LF everywhere except mvnw.cmd
├─ .gitignore
├─ .claude/
│  ├─ settings.json                 # Claude Code project settings
│  ├─ agents/invariant-reviewer.md  # reviews changes for races and invariant violations
│  └─ skills/
│     ├─ adr/                       # skill for writing ADRs
│     └─ ship-check/                # skill for pre-commit checks
├─ docs/
│  ├─ PROGRESS.md                   # progress log, updated every step
│  ├─ adr/                          # Architecture Decision Records (ADR-011 so far)
│  ├─ spec/                         # specification, architecture walkthrough, spec review
│  └─ structure/                    # this file
├─ deploy/
│  ├─ docker-compose.yml            # local Postgres (15432) and Redis (16379), bound to 127.0.0.1
│  └─ postgres/init/
│     └─ 01-schemas-and-roles.sh    # one schema and one DB role per service, plus checker_ro
├─ libs/
│  └─ common/                       # infrastructure helpers only, never domain entities
│     ├─ src/main/java/com/seatlock/common/
│     └─ src/test/java/com/seatlock/common/testing/
│        ├─ SeatLockPostgres.java   # shared Testcontainers fixture (exported as test-jar)
│        └─ SchemaIsolationIT.java  # proves each role can only touch its own schema
└─ services/
   ├─ user-catalog-service/         # users, auth, events, venues (schemas auth + catalog)
   ├─ inventory-service/            # seats and holds (schema inventory)
   └─ booking-service/              # bookings and the purchase saga (schema booking)
      (each service has the same shape:)
      ├─ pom.xml
      ├─ src/main/java/com/seatlock/<service>/   # Spring Boot application
      ├─ src/main/resources/
      │  ├─ application.yml
      │  └─ db/migration/V1__baseline.sql        # Flyway; never edit, add a new version
      └─ src/test/java/com/seatlock/<service>/   # *ApplicationIT smoke test (needs Docker)
```
