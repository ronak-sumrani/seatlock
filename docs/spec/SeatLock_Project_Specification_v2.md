# SeatLock: Complete Project Specification (v2)

> **How to use this document:** Paste it into another AI as context. It describes one project, SeatLock, in full: purpose, architecture, data model, flows, failure handling, testing, and build order. Decisions are stated as decisions, with the reasoning, so the AI can build on them, challenge them, or generate code, schemas, tests or docs that stay consistent with them.
>
> **Builder profile:** a fresher/student targeting Backend Engineer, SDE-1 and Full-stack roles. Stack: Java + Spring Boot backend, React frontend. The project exists to be a resume-grade, interview-defensible portfolio piece, so correctness, provable claims and honest trade-offs matter more than feature count.
>
> **About numbers:** every performance figure in this document is an *illustrative target*, not a measured result. Real numbers must come from the builder's own load tests.
>
> **What changed from v1:** see Appendix A. In short: the late-payment policy no longer allows two confirmed bookings per user; the recovery job fences in-flight calls instead of guessing; hold IDs are deterministic; Redis gate keys are provisional until the DB commits; WebSocket deltas carry version ranges; prices live in Inventory; sales open only after Inventory is seeded; admission tokens are tied to the admitted slot; and a sold-out flow, per-user seat cap, money invariant, configurable timings, retention jobs, a rush simulator and explicit ship points were added.

---

## 1. Project summary

**SeatLock** is a ticketing backend, with a React seat-map frontend, for selling a fixed inventory of seats to a very large crowd that arrives at the same moment (a concert or match on-sale). The reference scenario is 100,000 users trying to buy 5,000 seats within seconds.

It is not a generic "book a ticket" CRUD app. It is a system designed around **correctness under contention**:

- thousands of users race for overlapping seats at the same instant,
- payments are slow, fail, time out, or succeed after the user's hold has expired,
- users abandon carts, double-click and open many tabs,
- servers, Redis and Kafka can crash mid-flow,
- requests can be slow enough to race with the system's own recovery jobs,
- and through all of it, **no seat may ever be sold twice and no one may lose money**.

### The two core invariants (everything protects these)

> **I1 (seats): A seat belongs to at most one confirmed booking, ever.**
>
> **I2 (money): Every successful charge ends in exactly one of two outcomes: the user holds tickets for it, or the charge is refunded.**

Every design decision serves one of three goals: protect the invariants, stay fast under load, or recover from failure without breaking the invariants.

### Secondary rules (business invariants, also checked automatically)

- **R1:** A user has at most one *active* booking per event.
- **R2:** A user owns at most **6 seats per event** in total, across all their bookings.
- **R3:** No booking stays in a non-terminal state forever.

### Headline features

1. Atomic multi-seat holds (all-or-nothing) with automatic expiry.
2. Virtual waiting room that meters traffic into the buying flow, with a sold-out flow.
3. Orchestrated Saga for the purchase (hold → pay → confirm) with compensation and a fencing recovery job.
4. Transactional Outbox + Kafka for reliable event publishing.
5. Idempotent APIs and idempotent consumers (safe retries everywhere).
6. Live seat map and queue position over WebSockets, with version-ranged deltas and resync.
7. Mock payment provider that injects latency, declines, timeouts and duplicate events.
8. Automated concurrency tests, chaos scripts, an invariant checker and k6 load tests that prove I1 and I2.
9. A rush simulator that reproduces an on-sale on a laptop for demos.

### Deliberately NOT in the project

- **No AI feature.** Dynamic pricing or an assistant would be a gimmick here, and the project's depth comes from concurrency and reliability.
- No real payments, no real email (emails are logged), no Kubernetes initially, no promo codes, no resale, no cancellation of confirmed bookings in v1 (stretch).

---

## 2. Goals and non-goals

### Goals
- Zero oversold seats (I1) and zero unresolved charges (I2) under any tested concurrency level and any tested failure.
- No booking stuck in a non-terminal state beyond its maximum age (R3).
- No lost events (outbox) and no double-applied events (idempotent consumers).
- Fast rejection of losers under contention (Redis gate) so the database is not the bottleneck.
- A clear experience for the 95% of users who will not get a seat (sold-out flow).
- Observable system: metrics, traces and logs that show what happened to any booking.
- One-command local run (`docker compose up`), CI on every push, reproducible load tests.

### Non-goals
- Real money movement, PCI compliance, multi-region deployment, global active-active, bot-proof fairness, anti-scalper identity verification.

### Illustrative targets (to be measured, not assumed)
| Metric | Target |
|---|---|
| Hold attempt p95 latency (Gateway → Inventory) | < 100 to 150 ms at ~3,000 attempts/sec on a dev machine |
| Oversold seats / unresolved charges | exactly 0 across 1M simulated attempts |
| Seat-map snapshot p95 | < 30 ms (cached per version) |
| Delta propagation (hold → client sees seat grey) p95 | < 500 ms |
| Stuck bookings after chaos run | 0 |
| Concurrent WebSocket clients tested | 5,000 to 10,000 on one machine (100k is extrapolated, see §16.9) |

---

## 3. Technology stack

| Layer | Choice | Reason |
|---|---|---|
| Language / runtime | Java 21, Spring Boot 3.x | Matches target roles; virtual threads for blocking I/O services |
| Build | Maven or Gradle, **monorepo**, one module per service | Easy to run, review and CI together |
| Primary DB | **PostgreSQL**, one instance, one **schema per service**, one DB user per service | Transactions, unique constraints, conditional updates, `SKIP LOCKED`, advisory locks |
| Migrations | Flyway | Versioned schema per service |
| Data access | Spring Data JPA for CRUD; `JdbcTemplate`/native SQL on hot paths (hold, confirm, sweeper, outbox) | Control over the exact SQL where correctness matters |
| Cache / atomic ops | Redis 7 via Spring Data Redis (Lettuce); hand-written Lua | Atomic holds, sorted sets, TTLs, pub/sub, rate limiting. No Redisson: writing the Lua is the point |
| Messaging | Kafka (KRaft mode) + Spring Kafka. Redpanda allowed as a drop-in for low-RAM machines | Event log, partition ordering, replay, DLT |
| Gateway | Spring Cloud Gateway + Spring Security resource server | JWT validation, rate limiting, routing |
| Sync calls | `RestClient` + Resilience4j | Timeouts, retry, circuit breaker, bulkhead |
| Realtime | Spring `WebSocketHandler` (raw WebSocket, JSON, no STOMP) | Small custom protocol; resync logic is easier to control and show |
| Frontend | React + Vite + TypeScript, TanStack Query, native `WebSocket`, canvas seat map | Enough for a seat map; no Redux needed |
| Testing | JUnit 5, Testcontainers, Awaitility, jqwik, k6, Toxiproxy (optional), Playwright (one smoke test) | Real infra in tests; load and fault injection |
| Observability | Micrometer + Prometheus + Grafana; OpenTelemetry tracing to Jaeger; structured JSON logs | Mostly free with Spring Boot |
| API docs | springdoc OpenAPI | Per-service Swagger UI |
| Delivery | Docker, Docker Compose, GitHub Actions | One-command run; CI build/test/image |

### Stack decisions and reasons

- **No MongoDB / Express.** The core needs relational constraints and ACID transactions.
- **REST between services, not gRPC.** gRPC adds tooling without improving the concurrency story.
- **Raw WebSocket, not STOMP.** The protocol is tiny (`subscribe`, `snapshot`, `delta`, `queue_position`).
- **WebSocket vs SSE.** Most traffic is server→client, so SSE would also work. WebSockets are used because the client sends `subscribe/unsubscribe` messages and the protocol can grow. A conscious, defensible choice.
- **Outbox relay is a hand-written poller, one active relay per service** (Postgres advisory lock). Debezium (CDC) is the documented upgrade path.
- **No Eureka / Spring Cloud Config.** Compose DNS is enough at this scale.
- **Plain JSON events with a versioned envelope.** Avro + Schema Registry is a documented future improvement.
- **Redis is a single node**, but all keys use hash tags so Cluster behaviour can be explained.
- **Spring Cloud Gateway is reactive (WebFlux).** No blocking calls inside it. Other services use blocking Spring MVC with virtual threads enabled (a measurable talking point).
- **One Postgres instance with schema-per-service.** Each service has its own DB user that can only see its own schema, so "each service owns its data" is enforced by grants, not just convention. The invariant checker uses a separate read-only user that can see all schemas.

---

## 4. System architecture

```
                         ┌────────────────────────────┐
                         │      React Frontend        │
                         │ seat map · queue · checkout│
                         └─────┬───────────────┬──────┘
                          REST │               │ WebSocket
                               ▼               ▼
                     ┌───────────────────────────────────┐
                     │     API Gateway (Spring Cloud)    │
                     │ JWT · rate limit (Redis) · routing│
                     │ correlation IDs · traceparent     │
                     └──┬─────────┬─────────┬─────────┬──┘
                        │         │         │         │
              ┌─────────▼──┐ ┌────▼─────┐ ┌─▼───────┐ ┌▼─────────────┐
              │ User &     │ │ Inventory│ │ Booking │ │  Realtime    │
              │ Catalog    │ │ seats,   │ │ saga    │ │ WebSocket,   │
              │            │ │ holds,   │ │ orchestr│ │ waiting room,│
              │            │ │ prices   │ │ ator    │ │ read model,  │
              │            │ │ (public  │ │         │ │ notifications│
              │            │ │ read: no)│ │         │ │              │
              └────┬───────┘ └──┬───┬───┘ └──┬───┬──┘ └──┬────────┬──┘
                   │            │   │        │   │       │        │
                   ▼            ▼   │        ▼   │       │        │
            ┌──────────────────────────────────────────┐ │        │
            │ PostgreSQL (one instance)                │ │        │
            │ schemas: auth, catalog, inventory,       │ │        │
            │          booking, payment                │ │        │
            └──────────────────────────────────────────┘ │        │
                                │            │           │        │
                           ┌────▼────────────▼───────────▼────┐   │
                           │              Redis                │◄─┘
                           │ hold gate · sold set · queue ·    │
                           │ admitted · read model · rate      │
                           │ limits · cache · pub/sub          │
                           └───────────────────────────────────┘

   Sync (REST, internal network only, service credential):
     Booking  ──► Inventory   hold / extend / confirm / release / abort / lookup
     Booking  ──► Payment     create / get / void / refund
     Inventory──► Catalog     GET /internal/events/{id}/seats   (seeding only)
     Realtime ──► Inventory   GET /internal/events/{id}/seat-state (rebuild only)

   Async (Kafka via transactional outbox):
     catalog.events          Catalog   → Inventory, Realtime
     inventory.events        Inventory → Catalog (InventoryReady)
     inventory.seat-events   Inventory → Realtime (read model)
     inventory.hold-events   Inventory → Booking
     booking.events          Booking   → Realtime (notifications, admitted-slot release)
     payment.events          Payment   → Booking
     *.DLT                   poison messages → alerts / manual replay
```

### Deployable units

| # | Unit | Notes |
|---|---|---|
| 0 | `api-gateway` | Entry point (added at ship point v1.0; earlier, services validate JWTs themselves) |
| 1 | `user-catalog-service` | Auth and catalog as two modules with separate schemas (can be split later) |
| 2 | `inventory-service` | The most critical service: seat state, holds, prices, tickets |
| 3 | `booking-service` | Saga orchestrator |
| 4 | `payment-service` | Mock payment provider |
| 5 | `realtime-service` | WebSockets, waiting room, seat-map read model, notifications |
| 6 | `rush-simulator` | CLI tool (not deployed in demo profile by default) that reproduces an on-sale |

Infrastructure: PostgreSQL, Redis, Kafka (or Redpanda), Prometheus, Grafana, Jaeger.

### Architectural rules

1. **Each service owns its data.** No shared tables, no cross-service SQL. Enforced by per-service DB users.
2. **Source of truth vs derived state.** Postgres is the truth. Redis and the realtime read model are derived and rebuildable.
3. **Synchronous where the user needs an immediate answer** (can I hold these seats?), **asynchronous where they do not** (notifications, read models, analytics).
4. **Every cross-service write is idempotent, and every "unknown outcome" is resolved by fencing, never by guessing** (§6.4 recovery).
5. **Internal endpoints (`/internal/**`) are never routed through the gateway** and require a service credential.
6. **The hot path has the fewest possible hops:** Gateway → Booking → Inventory (Redis + Postgres). Catalog is not on the hot path.

---

## 5. Domain model and lifecycles

### Core entities
- **User**: account, roles `USER` / `ADMIN`.
- **Event**: name, venue, start time, `onSaleAt`, `closesAt`, status (see lifecycle below).
- **Venue / Seat map**: sections, rows, seats, geometry for rendering. Immutable once the event is published.
- **Price tier**: per section, price in **minor units** (integer cents/paise) plus currency. Never floating point. Frozen at publish.
- **Seat state** (Inventory): `AVAILABLE`, `HELD`, `SOLD`, plus the seat's frozen price.
- **Hold**: temporary reservation of up to 6 seats for one booking, with expiry.
- **Booking**: a user's purchase attempt, driven by the saga.
- **Payment / Refund** (Payment service).
- **Ticket**: immutable record that a seat was sold to a booking and user.

### Canonical seat identifier
`seatId` is a string `"<sectionCode>-<rowLabel>-<number>"`, for example `"B2-K-14"`. It is generated by Catalog when the venue is created, is unique within a venue, never changes, and is the only seat identifier used by Catalog, Inventory, Realtime, events and the frontend.

### Event lifecycle (owned by Catalog)

```
DRAFT ──publish──▶ PUBLISHED ──InventoryReady──▶ READY ──onSaleAt reached──▶ ON_SALE ──closesAt / admin──▶ CLOSED
```

- `PUBLISHED`: seat map and prices are frozen; `EventPublished` emitted; Inventory starts seeding.
- `READY`: Inventory confirmed seeding (`InventoryReady {eventId, seatCount}`). Only a `READY` event can open.
- `ON_SALE`: a Catalog scheduler (single instance via advisory lock) flips `READY → ON_SALE` at `onSaleAt` and emits `SaleOpened`. If `onSaleAt` passes while the event is still `PUBLISHED`, the sale does not open and an alert fires (`sale_open_blocked`).
- `CLOSED`: emits `EventClosed`. No new holds or admissions. Existing holds may still be paid and confirmed until they expire.
- "Sold out" is **not** a Catalog status. It is a derived, reversible condition computed by Realtime from live availability (§10.6).

### Seat lifecycle (owned by Inventory)

```
AVAILABLE ──hold──▶ HELD ──confirm (after payment)──▶ SOLD
    ▲                 │
    └─ release / expiry┘
```

`SOLD` is terminal in v1. Cancellation/refund-after-sale is a stretch feature.

### Business rules
- Max **6 seats** per booking and **6 seats per user per event in total** (R2). Checked at booking creation (Booking, from its own confirmed bookings) and enforced again at confirm (Inventory, from `tickets`) as a backstop.
- Max **one active booking per user per event** (R1), enforced with a partial unique index (§6.4).
- Hold TTL: **8 minutes** (configurable). Paying extends the hold to `max(expires_at, now() + payment_window)` with `payment_window` = 2 minutes (configurable).
- **Prices come from Inventory's frozen per-seat price**, set at seeding from Catalog's price tiers. The client never sends a price. The booking stores a price snapshot (line items) returned by the hold.
- Holds are only accepted when Inventory's sale state for the event is `OPEN` and the user presents a valid admission token whose slot is still active.
- All expiry decisions on Postgres data use **database time** (`now()`). Expiry decisions on Redis data use **Redis time** (`redis.call('TIME')` inside Lua). JWT `exp` checks allow 30 s leeway for clock skew between hosts.
- **All timings are configuration** (`seatlock.hold.ttl`, `seatlock.hold.payment-window`, `seatlock.sweeper.interval`, `seatlock.admission.slot-ttl`, `seatlock.recovery.*`). Tests run with timings in seconds.

---

## 6. Services in detail

### 6.1 API Gateway

**Responsibilities**
- Validate JWT access tokens (public keys from the auth module's JWKS endpoint, cached).
- Rate limiting with a Redis token bucket (Lua). Keys per user and per IP; separate limits for `/bookings` (strict), browsing (loose), queue joins, and snapshot (moderate).
- Routing:

| Path | Target |
|---|---|
| `/api/v1/auth/**` | user-catalog |
| `/api/v1/events/**`, `/api/v1/admin/events/**`, `/api/v1/admin/venues/**` | user-catalog |
| `/api/v1/bookings/**` | booking |
| `/api/v1/queue/**`, `/api/v1/realtime/**`, `/ws` | realtime |
| `/api/v1/admin/mock-psp/**` | payment |
| `/internal/**` | **never routed** (404 at the gateway) |

- CORS, request-size limits, correlation ID and W3C `traceparent` propagation.
- Forward the validated JWT downstream; services re-validate it (defense in depth).

**Constraint:** non-blocking only (WebFlux). Routing, JWT validation and rate limiting, nothing heavier.

### 6.2 User & Catalog service

**Auth module**
- Register, login, refresh, logout. Passwords hashed with BCrypt (or Argon2).
- Access token: JWT, signed asymmetrically (RS256 or EdDSA), ~15 min. Claims: `sub` (userId), `roles`, `iat`, `exp`, `jti`.
- Refresh token: opaque random string, stored **hashed**, rotated on use, revocable. (Added at ship point v1.0; earlier versions use access tokens only.)
- Exposes JWKS at `/.well-known/jwks.json`.
- **Test auth profile** (`seatlock.test-auth.enabled=true`, only in the `loadtest` Spring profile): an endpoint that mints access tokens for synthetic users signed with a *separate* test key. Services trust the test key only when the same profile is active. Startup fails if the profile is active together with `prod`. This exists because BCrypt-logging-in 100k users would make the login endpoint the load test.

**Catalog module**
- Admin APIs: create venue/seat map, create event, define price tiers, publish event, set `onSaleAt` / `closesAt`, close event.
- Public APIs: list/search events, event details (including status), seat-map geometry (static layout, no availability).
- On publish: freezes seat map and prices, sets `PUBLISHED`, emits `EventPublished {eventId, venueId, seatCount, onSaleAt, closesAt}` via outbox.
- Consumes `InventoryReady` → `READY`.
- Sale scheduler: `READY → ON_SALE` at `onSaleAt` (emits `SaleOpened`), `ON_SALE → CLOSED` at `closesAt` (emits `EventClosed`). Single active scheduler via advisory lock.
- Internal: `GET /internal/events/{id}/seats` returns every seat with `seatId`, section, tier and `priceMinor`, `currency`. Paginated (seat maps can be 50k seats).
- **Caching:** cache-aside in Redis for event details and seat-map geometry. Geometry is immutable after publish, so long TTL plus `ETag`/`Cache-Control: immutable`. Stampede protection with TTL jitter and a single-flight lock on cache miss.

**Data (schemas `auth`, `catalog`)**: `users`, `refresh_tokens`, `venues`, `sections`, `seats`, `events`, `price_tiers`, `outbox`, `processed_events`.

### 6.3 Inventory service (most critical)

**Responsibilities**
- Own the authoritative state and frozen price of every seat for every event.
- Own the per-event sale state (`PENDING_SEED`, `READY`, `OPEN`, `CLOSED`).
- Execute holds, extensions, confirmations, releases, aborts and expiry.
- Maintain the Redis fast gate and keep it consistent with Postgres.
- Issue tickets and enforce the per-user seat cap at confirm.
- Publish seat, hold and readiness events through the outbox.

**Data (schema `inventory`)**

```sql
CREATE TABLE event_sales (
  event_id     BIGINT PRIMARY KEY,
  state        TEXT NOT NULL CHECK (state IN ('PENDING_SEED','READY','OPEN','CLOSED')),
  seat_count   INT  NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per seat per event. Created idempotently when EventPublished is consumed.
CREATE TABLE seat_state (
  event_id        BIGINT NOT NULL,
  seat_id         TEXT   NOT NULL,
  section_code    TEXT   NOT NULL,
  price_minor     BIGINT NOT NULL CHECK (price_minor >= 0),
  currency        CHAR(3) NOT NULL,
  status          TEXT   NOT NULL CHECK (status IN ('AVAILABLE','HELD','SOLD')),
  hold_id         UUID,
  hold_expires_at TIMESTAMPTZ,
  version         BIGINT NOT NULL DEFAULT 0,      -- per-seat version, carried in seat events
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, seat_id),
  CHECK ((status = 'AVAILABLE') = (hold_id IS NULL) OR status = 'SOLD')
);

CREATE TABLE seat_holds (
  hold_id     UUID PRIMARY KEY,                   -- = UUIDv5(SEATLOCK_NS, bookingId), deterministic
  booking_id  UUID NOT NULL UNIQUE,               -- one hold (or tombstone) per booking, ever
  event_id    BIGINT NOT NULL,
  user_id     UUID NOT NULL,
  seat_ids    TEXT[] NOT NULL,
  status      TEXT NOT NULL CHECK (status IN ('HELD','CONFIRMED','RELEASED','EXPIRED','ABORTED','REJECTED')),
  reject_reason TEXT,                             -- for REJECTED: SEAT_CONFLICT, SALE_NOT_OPEN, ...
  total_minor BIGINT,
  currency    CHAR(3),
  expires_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX seat_holds_expiry ON seat_holds (expires_at) WHERE status = 'HELD';

-- Final immutable backstop against double-selling (I1).
CREATE TABLE tickets (
  ticket_id   UUID PRIMARY KEY,
  event_id    BIGINT NOT NULL,
  seat_id     TEXT   NOT NULL,
  booking_id  UUID   NOT NULL,
  user_id     UUID   NOT NULL,
  price_minor BIGINT NOT NULL,
  sold_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (event_id, seat_id)
);
CREATE INDEX tickets_user_event ON tickets (event_id, user_id);

CREATE TABLE outbox (...);            -- see §9
CREATE TABLE processed_events (...);  -- see §9
```

Note that `seat_holds` records the outcome of *every* hold request for a booking, including rejections and aborts. That makes `POST /internal/holds` and `GET /internal/holds?bookingId=` exact: the first request for a booking decides its outcome forever.

**Seeding (consumer of `EventPublished`)**
1. Insert `event_sales(event_id, 'PENDING_SEED', seatCount)` (`ON CONFLICT DO NOTHING`).
2. Page through `GET /internal/events/{id}/seats`, inserting `seat_state` rows with `ON CONFLICT DO NOTHING` (idempotent, resumable).
3. When the row count equals `seatCount`, set `READY` and write `InventoryReady` to the outbox in the same transaction.
4. Initialise Redis: delete any stale `hold:`/`sold:` keys for the event.

Consumers of `SaleOpened` set `OPEN`; `EventClosed` sets `CLOSED`.

**Operations**

| Operation | Behaviour |
|---|---|
| `POST /internal/holds {bookingId, eventId, userId, seatIds}` | Idempotent on `bookingId`. Returns the existing outcome if a `seat_holds` row exists. Otherwise: sale-state check, Redis gate, Postgres arbiter. Returns `{holdId, status, expiresAt, lineItems[], totalMinor, currency}` or `{status:'REJECTED', reason, conflicts[]}` |
| `POST /internal/holds/{holdId}/extend {until}` | Only if `HELD` and not yet expired (DB time). Extends DB expiry and Redis key TTLs. Returns `EXTENDED` or `HOLD_EXPIRED` |
| `POST /internal/holds/{holdId}/confirm` | Marks seats SOLD, inserts tickets, enforces R2. Idempotent. Returns `CONFIRMED`, `SEAT_CONFLICT` or `QUOTA_EXCEEDED` |
| `POST /internal/holds/{holdId}/release` | Releases seats still owned by this hold. Idempotent |
| `POST /internal/holds/abort {bookingId}` | **Fence.** If no row exists, inserts an `ABORTED` tombstone so any later hold for this booking is refused. If a `HELD` row exists, releases it. Returns the final status |
| `GET /internal/holds?bookingId=` | Exact outcome lookup |
| `GET /internal/events/{id}/seat-state` | Full dump (paged) used to rebuild Redis and the realtime read model |
| Sweeper (scheduled) | Expires holds, releases their seats, emits events |
| Redis rebuild (startup / on demand) | Rebuilds `sold:` sets and `hold:` keys from Postgres |

**Hold algorithm (two stages, defense in depth)**

0. **Fast idempotency check.** `SELECT ... FROM seat_holds WHERE booking_id = :b`. If found, return its outcome (including `ABORTED`/`REJECTED`). Compute `holdId = UUIDv5(SEATLOCK_NS, bookingId)`, so every retry uses the same `holdId`.
1. **Sale-state check** (`event_sales.state = 'OPEN'`, cached in memory for 1 s and refreshed from DB). If not open: reject with `SALE_NOT_OPEN` (not a seat conflict).
2. **Redis gate (fast, cheap rejection).** Run the atomic hold Lua script (§7) with a **provisional TTL** of 30 s. If any seat is sold or held by a different hold, return the conflicting seats. Most losers stop here and never touch Postgres.
3. **Postgres arbiter (authoritative).** In one transaction:
   ```sql
   -- (a) claim the booking first: a concurrent abort or duplicate hold hits the unique key
   INSERT INTO seat_holds (hold_id, booking_id, event_id, user_id, seat_ids, status, expires_at)
   VALUES (:hold, :booking, :evt, :user, :seats, 'HELD', now() + :ttl);

   -- (b) lock seats in a consistent order to avoid deadlocks between multi-seat holds
   SELECT seat_id FROM seat_state
   WHERE event_id = :evt AND seat_id = ANY(:seats)
   ORDER BY seat_id FOR UPDATE;

   -- (c) conditional update
   UPDATE seat_state
   SET status='HELD', hold_id=:hold, hold_expires_at=now() + :ttl, version=version+1, updated_at=now()
   WHERE event_id=:evt AND seat_id = ANY(:seats)
     AND (status='AVAILABLE' OR (status='HELD' AND hold_expires_at < now()))
   RETURNING seat_id, price_minor, currency, version;
   -- affected rows must equal the number of seats; otherwise ROLLBACK and report conflicts
   ```
   Then set `total_minor`/`currency` on the hold and write outbox rows (`SeatsHeld` on `inventory.seat-events`) in the same transaction.
   - If (a) fails with a unique violation, another request for this booking (or an abort) won. Roll back, compensate Redis, and return that row's outcome.
   - If (c) affects fewer rows than requested, roll back, then record the rejection in its own small transaction (`INSERT ... status='REJECTED', reject_reason='SEAT_CONFLICT' ON CONFLICT (booking_id) DO NOTHING`), compensate Redis, and return the conflicts. Recording rejections keeps retries and recovery lookups exact.
   - A seat that was `HELD` with an expired, not-yet-swept hold is taken over in (c). The old hold's row stays `HELD` until the sweeper processes it; the sweeper only releases seats that still carry its `hold_id` (see below), so it cannot release the new owner's seats.
4. **Promote the Redis keys.** After commit, run the promote script (§7) to extend the keys from the 30 s provisional TTL to the real hold TTL (ownership-checked). If Inventory crashes between stage 2 and stage 4, the provisional keys vanish within 30 s instead of blocking free seats for 8 minutes.
5. **Compensation.** If stage 3 fails for any reason, run the release script for the keys acquired in stage 2.
6. **Degraded mode.** If Redis is unavailable (circuit open), skip stages 2, 4 and 5 and rely on the DB alone. Slower but still correct.

**Extend algorithm** (one transaction): `UPDATE seat_holds SET expires_at = GREATEST(expires_at, :until) WHERE hold_id=:hold AND status='HELD' AND expires_at > now()`, and the same on the hold's `seat_state` rows. 0 rows → `HOLD_EXPIRED`. After commit, extend the Redis key TTLs with an ownership-checked script.

**Confirm algorithm** (the real decision point), in one transaction:
1. Lock the hold row (`SELECT ... FOR UPDATE`). If `CONFIRMED`, return `CONFIRMED` (idempotent). If `RELEASED`, `ABORTED` or `REJECTED`, return `SEAT_CONFLICT` (seats were given back on purpose; nothing to confirm). If `HELD` or `EXPIRED`, continue (`EXPIRED` is the late-payment case).
2. **Quota (R2):** `SELECT count(*) FROM tickets WHERE event_id=:evt AND user_id=:user` plus this hold's seat count must be ≤ 6; otherwise roll back and return `QUOTA_EXCEEDED`. (Under R1 this should never fire; it is a backstop.)
3. Lock the hold's seats in `seat_id` order, then:
   ```sql
   UPDATE seat_state
   SET status='SOLD', hold_id=:hold, hold_expires_at=NULL, version=version+1, updated_at=now()
   WHERE event_id=:evt AND seat_id = ANY(:seats)
     AND ( (status='HELD' AND hold_id=:hold)                               -- normal path
        OR  status='AVAILABLE'                                             -- late payment, seat still free
        OR (status='HELD' AND hold_id<>:hold AND hold_expires_at < now()) ) -- late payment, other hold also lapsed
   RETURNING seat_id, version;
   ```
   If fewer rows than seats → someone else owns a seat → roll back, return `SEAT_CONFLICT`. Booking then refunds.
4. Insert one `tickets` row per seat. `UNIQUE (event_id, seat_id)` is the last line of defense even if every other mechanism has a bug.
5. Mark the hold `CONFIRMED`; write outbox `SeatsSold`.
6. After commit: add seats to Redis `sold:` set and delete the hold keys (ownership-checked).

The late-payment clauses are safe because of the booking rules in §6.4: a booking whose hold lapsed **during payment** stays active (it is still `PAYMENT_PENDING`), so the user cannot have started a second booking for the same event.

**Release algorithm**: `UPDATE seat_state SET status='AVAILABLE', hold_id=NULL, hold_expires_at=NULL, version=version+1 WHERE event_id=:evt AND seat_id=ANY(:seats) AND status='HELD' AND hold_id=:hold`; mark hold `RELEASED`; outbox `SeatsReleased` (only for rows actually changed) and `HoldReleased`; after commit, ownership-checked Redis release.

**Expiry sweeper**
- Runs every 2 s (configurable). Claims a bounded batch: `SELECT ... FROM seat_holds WHERE status='HELD' AND expires_at < now() ORDER BY expires_at LIMIT 200 FOR UPDATE SKIP LOCKED`.
- For each hold: release seats **only where `hold_id` still equals this hold** (so a seat already taken over by a newer hold or sold to a late payer is untouched), bump `version`, mark the hold `EXPIRED`, write `SeatsReleased` (changed seats only) and `HoldExpired {bookingId, holdId}`.
- After commit: ownership-checked Redis release.
- **Why a sweeper and not Redis key-expiry events:** keyspace notifications are fire-and-forget and can be missed. Redis TTL is a fast-path convenience only. Postgres plus the sweeper is the guarantee.

### 6.4 Booking service (Saga orchestrator)

Owns the purchase workflow and its persistent state.

**Data (schema `booking`)**
```sql
CREATE TABLE bookings (
  id                   UUID PRIMARY KEY,
  user_id              UUID NOT NULL,
  event_id             BIGINT NOT NULL,
  seat_ids             TEXT[] NOT NULL CHECK (cardinality(seat_ids) BETWEEN 1 AND 6),
  status               TEXT NOT NULL,
  line_items           JSONB,                    -- [{seatId, priceMinor}] snapshot from the hold
  total_minor          BIGINT,                   -- null until SEATS_HELD
  currency             CHAR(3),
  hold_id              UUID,
  hold_expires_at      TIMESTAMPTZ,
  hold_lapsed_at       TIMESTAMPTZ,              -- hold expired while PAYMENT_PENDING (UI hint only)
  payment_id           UUID,
  payment_requested_at TIMESTAMPTZ,              -- set just before calling Payment create
  idempotency_key      TEXT NOT NULL,
  request_hash         TEXT NOT NULL,            -- detects same key + different payload
  failure_reason       TEXT,
  version              BIGINT NOT NULL DEFAULT 0,   -- optimistic locking
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, idempotency_key)
);

-- R1: one active booking per user per event. "Active" = may hold seats.
CREATE UNIQUE INDEX one_active_booking ON bookings (user_id, event_id)
  WHERE status IN ('PENDING','SEATS_HELD','PAYMENT_PENDING','PAYMENT_SUCCEEDED');

CREATE TABLE booking_transitions (
  id BIGSERIAL PRIMARY KEY, booking_id UUID NOT NULL, from_status TEXT, to_status TEXT NOT NULL,
  reason TEXT, at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- I2 safety net: a charge that arrived for a booking that can no longer use it.
CREATE TABLE orphan_payments (
  payment_id  UUID PRIMARY KEY,
  booking_id  UUID NOT NULL,
  amount_minor BIGINT NOT NULL,
  status      TEXT NOT NULL CHECK (status IN ('REFUND_PENDING','REFUNDED')),
  detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (...);
CREATE TABLE processed_events (...);
```

`REFUND_PENDING` is deliberately **not** in `one_active_booking`: it holds no seats, so a user whose confirm lost a race can immediately try again while the refund completes.

**Why orchestration (not choreography):** the flow is linear and has explicit compensations. A central orchestrator with persisted state is easier to reason about, test and recover than services reacting to each other's events. The trade-off is that Booking knows about the steps. That is acceptable here.

**Saga state machine**

```
                    hold ok                 pay (extend ok)            PaymentSucceeded             confirm ok
  PENDING ───────────────────▶ SEATS_HELD ─────────────────▶ PAYMENT_PENDING ─────────────▶ PAYMENT_SUCCEEDED ─────────▶ CONFIRMED
     │                          │   │   │                        │      │                          │
     │ hold rejected / fenced   │   │   │ extend says expired    │      │ PaymentFailed /          │ SEAT_CONFLICT /
     ▼                          │   │   ▼                        │      │ payment voided           │ QUOTA_EXCEEDED
  HOLD_FAILED                   │   │ EXPIRED                    │      ▼                          ▼
                                │   │                            │  PAYMENT_FAILED           REFUND_PENDING ──RefundCompleted──▶ REFUNDED
                     cancel     │   │ hold expires                │  (hold released)
                                ▼   ▼                            │
                         CANCELLED  EXPIRED                      │ HoldExpired while paying:
                       (hold released)                            │ stay PAYMENT_PENDING, set hold_lapsed_at
                                                                  └─ (outcome decided by payment, then confirm)
```

Allowed transitions (the transition table in code is exactly this list):

| From | To | Trigger |
|---|---|---|
| `PENDING` | `SEATS_HELD` | Hold returned `HELD` |
| `PENDING` | `HOLD_FAILED` | Hold returned `REJECTED`, or recovery fenced with abort |
| `SEATS_HELD` | `PAYMENT_PENDING` | `pay` after a successful extend |
| `SEATS_HELD` | `CANCELLED` | User cancel |
| `SEATS_HELD` | `EXPIRED` | `HoldExpired` event, recovery, or extend returned `HOLD_EXPIRED` |
| `PAYMENT_PENDING` | `PAYMENT_SUCCEEDED` | `PaymentSucceeded` (event or recovery poll) |
| `PAYMENT_PENDING` | `PAYMENT_FAILED` | `PaymentFailed`, or recovery voided a never-started payment |
| `PAYMENT_SUCCEEDED` | `CONFIRMED` | Confirm returned `CONFIRMED` |
| `PAYMENT_SUCCEEDED` | `REFUND_PENDING` | Confirm returned `SEAT_CONFLICT` or `QUOTA_EXCEEDED` |
| `REFUND_PENDING` | `REFUNDED` | `RefundCompleted` (event or recovery poll) |

- **Terminal:** `CONFIRMED`, `HOLD_FAILED`, `CANCELLED`, `EXPIRED`, `PAYMENT_FAILED`, `REFUNDED`. Terminal really means terminal: no transition leaves them.
- **Can a terminal booking ever be charged?** No. A charge is only requested from `PAYMENT_PENDING`, and a booking can only leave `PAYMENT_PENDING` through the payment's real outcome or through a successful void (which guarantees no charge). The `orphan_payments` table exists only as a defense-in-depth path for I2: if a `PaymentSucceeded` ever arrives for a booking that is not `PAYMENT_PENDING`/`PAYMENT_SUCCEEDED`/`CONFIRMED`, Booking records it and refunds it, without touching the booking's status, and raises an alert. The invariant checker requires this table to drain to `REFUNDED`.
- Each transition is validated against the table and applied with optimistic locking (`UPDATE ... WHERE id=? AND version=? AND status=?`). Illegal or concurrent transitions are rejected. This resolves races such as cancel vs pay: whichever transition commits first wins, the loser gets `409 ILLEGAL_STATE`.
- Every transition is appended to `booking_transitions` and emitted as an outbox event in the same transaction.
- `HoldExpired` arriving while `PAYMENT_PENDING` or later is **not** a transition: Booking only sets `hold_lapsed_at` (so the UI can say "your hold lapsed; we'll still try to complete your purchase").

**Recovery job (guarantees R3: nothing is stuck forever).** Runs every 5 s, claims rows with `FOR UPDATE SKIP LOCKED`, and **never infers an outcome from silence. It fences first, then reads the truth.**

| Stuck state | Action |
|---|---|
| `PENDING` older than 30 s | Call `POST /internal/holds/abort {bookingId}`. The result is final: `HELD` hold existed → it is now released → `HOLD_FAILED` (reason `RECOVERY_ABORT`); tombstone written → `HOLD_FAILED`; `REJECTED` → `HOLD_FAILED`. A slow original hold request that arrives later hits the tombstone and is refused |
| `SEATS_HELD` past `hold_expires_at` + 10 s grace | → `EXPIRED`, and call `release` (idempotent) |
| `PAYMENT_PENDING` with `payment_id` null and `payment_requested_at` older than 30 s (crashed before or during create) | Call `POST /internal/payments/void {bookingId}`. If Payment reports an existing payment → store its id and follow its status. If voided → `PAYMENT_FAILED` (reason `PAYMENT_NOT_STARTED`) and release the hold |
| `PAYMENT_PENDING` older than the PSP max processing time (configurable, e.g. 5 min) | `GET` the payment. Final status → apply it. Still `PROCESSING` → keep waiting and alert if beyond a hard limit |
| `PAYMENT_SUCCEEDED` | Retry confirm (idempotent) |
| `REFUND_PENDING` / `orphan_payments.REFUND_PENDING` | Retry refund (idempotent by `bookingId`/`paymentId`) |

**Resilience:** Resilience4j timeouts (hold 2 s, confirm 2 s, payment create 3 s), bounded retries with exponential backoff and jitter (idempotent calls only), circuit breakers and bulkheads on calls to Inventory and Payment. Every downstream call carries `bookingId` as its idempotency key.

### 6.5 Payment service (mock PSP)

Simulates a real payment provider so the saga is exercised against realistic failure.

**Contract (asynchronous, like a real PSP):**
- `POST /internal/payments {bookingId, amountMinor, currency, token}`: idempotent on `bookingId`. Persists a `PROCESSING` row and returns `202 {paymentId, status:'PROCESSING'}`. Processing happens in a background worker; the outcome is published as an event.
- `GET /internal/payments?bookingId=` and `GET /internal/payments/{id}`: current status.
- `POST /internal/payments/void {bookingId}`: **fence.** If no payment exists, inserts a `VOIDED` tombstone so a later create for this booking is refused (returns `VOIDED`, no charge). If a payment exists, returns it unchanged.
- `POST /internal/payments/{id}/refund`: idempotent; result as `RefundCompleted` event and via `GET`.

**Magic test tokens (like Stripe test cards):**
- `tok_success`: succeeds after random latency (configurable distribution).
- `tok_decline`: declined.
- `tok_slow`: success after a long delay (longer than the payment window, to exercise late payment).
- `tok_timeout`: commits the `PROCESSING` row, then **delays the HTTP response past the caller's timeout**. The caller sees an unknown outcome even though the payment exists. This is the classic "timeout but charged" case.
- `tok_flaky`: random processing failures at a configurable rate.

**Behaviour:**
- Publishes `PaymentSucceeded` / `PaymentFailed` / `RefundCompleted` (outbox → `payment.events`), and on purpose publishes a configurable fraction of them **twice** to test idempotent consumers.
- Admin: `PUT /api/v1/admin/mock-psp/config` for latency distribution and failure rates (`ADMIN` role).

**Data (schema `payment`)**: `payments(id, booking_id UNIQUE, amount_minor, currency, token, status CHECK IN ('PROCESSING','SUCCEEDED','FAILED','VOIDED'), created_at, updated_at)`, `refunds(id, payment_id UNIQUE, status, ...)`, `outbox`.

### 6.6 Realtime service

Four responsibilities, kept in separate modules:

**a) WebSocket gateway.** Manages sessions and pushes live updates (protocol in §11).

**b) Seat-map read model.** A CQRS-style projection of seat state, built from `inventory.seat-events`:
- Redis hash `seatmap:{evt:ID}`: `seatId → "<state>:<seatVersion>"`.
- Redis hash `seatcount:{evt:ID}`: `available`, `held`, `sold` counters.
- Redis counter `ver:{evt:ID}`: the event-wide delta version.
- Applying an event runs one Lua script that, per seat, updates only if the incoming `seatVersion` is greater than the stored one, adjusts `seatcount`, and **increments `ver` only if at least one seat changed**. It returns the new version and the changed seats; if nothing changed (duplicate or stale event), it returns nothing and no delta is published. This keeps the version sequence gap-free.
- Rebuildable at any time from Inventory's `seat-state` dump (rebuild bumps `ver` and broadcasts `resync_required`).
- Exactly one applier per event at a time: the Kafka consumer group, with `inventory.seat-events` keyed by `eventId`, assigns each event's partition to one consumer.

**c) Waiting room** (§10).

**d) Notification.** Consumes `booking.events`: pushes `booking_update` to the user's sessions (via pub/sub channel `userupdates`), logs an email, and on `BookingConfirmed` releases the user's admitted slot (§10.4).

Stateless apart from Redis and in-memory session registries, so it scales horizontally.

---

## 7. Redis: usage, keys, scripts

Redis is an **optimization and coordination layer. It is never the source of truth.** Anything in Redis must be rebuildable from Postgres (or is disposable, like rate-limit buckets and caches).

### Key design (hash tags for Redis Cluster compatibility)

| Key | Type | Owner | Purpose |
|---|---|---|---|
| `hold:{evt:42}:B2-K-14` | string = `holdId`, TTL | Inventory | Fast-path hold on one seat |
| `sold:{evt:42}` | set of seatIds | Inventory | Fast-path "already sold" |
| `queue:{evt:42}` | sorted set (score = sequence) | Realtime | Waiting-room line |
| `queueseq:{evt:42}` | counter | Realtime | Arrival sequence generator |
| `admitted:{evt:42}` | sorted set (member userId, score = slot expiry, ms) | Realtime | Users currently allowed in the buying flow |
| `queuestate:{evt:42}` | string | Realtime | `WAITING`, `OPEN`, `PAUSED_ALL_HELD`, `SOLD_OUT`, `CLOSED` |
| `seatmap:{evt:42}` | hash | Realtime | Read model |
| `seatcount:{evt:42}` | hash | Realtime | available / held / sold counts |
| `ver:{evt:42}` | counter | Realtime | Delta version |
| `snap:{evt:42}` | string (gzipped JSON), short TTL | Realtime | Cached snapshot for the current version |
| `wsticket:{id}` | string, TTL 30 s | Realtime | Single-use WebSocket ticket |
| `rl:{user|ip}:route` | hash (token bucket) | Gateway | Rate limiting |
| `idem:{user}:{key}` | string, TTL | Booking | Idempotency fast path (DB unique constraint is the truth) |
| `cache:event:{id}`, `cache:seatmap:{id}` | string/JSON | Catalog | Catalog cache |
| channels `seatdelta:{evt:42}`, `userupdates`, `queueupdates:{evt:42}` | pub/sub | Realtime | Cross-node WebSocket fan-out |

Hash tags (`{evt:42}`) put all keys for one event in one slot, which multi-key Lua needs in Redis Cluster. Trade-off: one event lives on one shard, so a mega-event is a potential hot key. This is a documented, discussable limit (§20).

### Atomic hold script (provisional)

```lua
-- KEYS[1]    = sold set key
-- KEYS[2..n] = seat hold keys
-- ARGV[1] = holdId, ARGV[2] = provisional ttl seconds, ARGV[3..n+1] = seatIds (parallel to KEYS[2..n])
local sold, holdId, ttl = KEYS[1], ARGV[1], tonumber(ARGV[2])
local conflicts = {}
for i = 2, #KEYS do
  local seatId = ARGV[i + 1]
  local owner = redis.call('GET', KEYS[i])          -- false when missing
  if redis.call('SISMEMBER', sold, seatId) == 1 or (owner and owner ~= holdId) then
    conflicts[#conflicts + 1] = seatId
  end
end
if #conflicts > 0 then return conflicts end       -- nothing written (all-or-nothing)
for i = 2, #KEYS do
  redis.call('SET', KEYS[i], holdId, 'EX', ttl)
end
return {}
```

Atomic because Redis executes a script as one uninterrupted unit. Idempotent because `holdId` is deterministic per booking, so a retry sees its own keys as non-conflicting.

### Promote / extend script (ownership-checked TTL change)

```lua
-- KEYS = seat hold keys, ARGV[1] = holdId, ARGV[2] = ttl seconds
local n = 0
for i = 1, #KEYS do
  if redis.call('GET', KEYS[i]) == ARGV[1] then
    redis.call('EXPIRE', KEYS[i], tonumber(ARGV[2])); n = n + 1
  end
end
return n
```
Used after the DB commit (provisional → full TTL) and on extend. Returning fewer than `#KEYS` is logged (the key expired or Redis restarted); correctness does not depend on it.

### Release script (only release what you own)

```lua
-- KEYS = seat hold keys, ARGV[1] = holdId
for i = 1, #KEYS do
  if redis.call('GET', KEYS[i]) == ARGV[1] then redis.call('DEL', KEYS[i]) end
end
return 1
```
The ownership check prevents a slow release from deleting a hold that a different booking acquired after expiry.

### Other Redis roles
- **Waiting room:** join, admission and slot-release scripts (§10).
- **Rate limiting:** token bucket via Lua (atomic refill-and-consume, Redis `TIME`).
- **Idempotency fast path** for `POST /bookings`.
- **Catalog cache** with stampede protection.
- **Pub/sub** for WebSocket fan-out across realtime nodes.

### Failure policy when Redis is down

| Component | Behaviour | Why |
|---|---|---|
| Inventory hold path | DB-only mode (skip gate) | Still correct, slower |
| Gateway rate limiter | Fails **open**, alert | Availability over strictness |
| Booking admission check | Signature-only token validation (§10.5) | Token is still bound to user, event and expiry |
| Waiting room | Queue unavailable: joins return `503`; admitted users keep their tokens; no new admissions | Admitting everyone would defeat the waiting room. Simple, honest degradation |
| Realtime | Clients fall back to polling the snapshot endpoint every 5 s (served from Inventory dump if the read model is gone) | UX only |

### What happens if Redis dies mid-sale
Holds and sold seats are rebuilt from `seat_state` on recovery (before the circuit closes, Inventory runs the rebuild). During the gap, Inventory runs DB-only. Postgres conditional updates and the `tickets` unique constraint still prevent oversell. The realtime read model is rebuilt from the Inventory dump and all clients receive `resync_required`. Performance degrades; correctness does not.

---

## 8. The end-to-end purchase flow

### Happy path

1. **Browse.** The user loads event details and static seat-map geometry (cached, ETag) and the availability snapshot from Realtime. The WebSocket then streams deltas.
2. **Waiting room.** At or before `onSaleAt`, the user joins the queue and watches their position. When admitted, they receive an `admitted` message and fetch a short-lived **admission token** whose expiry equals their admitted slot's expiry.
3. **Select seats** (client only, no server state yet). The UI disables seats that are not available.
4. **Create booking + hold:** `POST /api/v1/bookings` with headers `Authorization`, `Idempotency-Key`, `X-Admission-Token`, body `{eventId, seatIds[]}`.
   - Booking checks the idempotency key (same key + same hash → return the existing booking's current representation; same key + different hash → `422`).
   - Validates the admission token (signature, `sub`, `evt`, `exp`) and that the user is still in `admitted:{evt}` (§10.5).
   - Validates 1–6 seats and **R2**: seats in this user's `CONFIRMED` bookings for this event + requested ≤ 6, else `409 SEAT_LIMIT`.
   - Inserts a `PENDING` booking. A unique violation on `one_active_booking` → `409 ACTIVE_BOOKING_EXISTS` with the existing booking's id.
   - Calls Inventory `POST /internal/holds`.
   - `HELD` → booking `SEATS_HELD` with line items, total and `expiresAt` → `201`.
   - `REJECTED` → `HOLD_FAILED` → `409 SEAT_CONFLICT` with the conflicting seats (or `409 SALE_NOT_OPEN`).
   - Timeout / 5xx after retries → booking stays `PENDING` → `202 {bookingId, status:'PENDING'}`; the client polls `GET /bookings/{id}` (or waits for `booking_update`); the recovery job resolves it within ~30 s.
5. **Pay:** `POST /api/v1/bookings/{id}/pay {paymentToken}`.
   - Booking checks the booking is `SEATS_HELD` and calls Inventory `extend`. `HOLD_EXPIRED` → `EXPIRED` → `409 HOLD_EXPIRED`. **No charge is attempted.**
   - Transition `SEATS_HELD → PAYMENT_PENDING` (optimistic lock) and set `payment_requested_at`, in one transaction. If a concurrent cancel won, → `409`.
   - Call Payment create (`bookingId` as key). Store `payment_id`.
   - Return `202 Accepted`. The outcome arrives asynchronously.
6. **Payment result** arrives as `PaymentSucceeded` on `payment.events` (the recovery job polls Payment if it never arrives).
7. **Confirm.** Booking → `PAYMENT_SUCCEEDED`, calls Inventory `confirm`. Inventory commits `SOLD` + `tickets` in one transaction (the real decision point).
8. **Done.** Booking → `CONFIRMED`, emits `BookingConfirmed`. Realtime pushes `booking_update`, releases the user's admitted slot (letting the next person in), and every client's seat map shows the seats as sold.

### Failure paths

| Failure | Behaviour |
|---|---|
| Seat already taken at hold time | Lua returns conflicts (or DB update count mismatch). Nothing is held. `409` with the conflicting seats. Provisional Redis keys from this attempt are released |
| Sale not open yet / closed / Inventory still seeding | `409 SALE_NOT_OPEN` (not a seat conflict). Booking `HOLD_FAILED` |
| Hold call slow, Booking recovery runs first | Recovery calls `abort`; tombstone refuses the late hold; booking `HOLD_FAILED`. No orphan hold |
| Inventory crashes between Redis gate and DB commit | Provisional Redis keys expire within 30 s; DB never changed |
| Payment declined / failed | Booking → `PAYMENT_FAILED`, calls Inventory `release`, seats return to `AVAILABLE`, delta broadcast |
| Payment timeout, outcome unknown | Booking stays `PAYMENT_PENDING`. Payment row exists (`tok_timeout` commits before hanging), so the event or the recovery poll delivers the truth. Never assumes failure |
| Booking crashes after `PAYMENT_PENDING` but before calling Payment | Recovery voids by `bookingId`: tombstone, no charge, `PAYMENT_FAILED`, hold released |
| User abandons before paying | Sweeper expires the hold, emits `HoldExpired`, Booking → `EXPIRED`, admitted slot runs out normally |
| User tries to pay after the hold expired | Extend returns `HOLD_EXPIRED` → `EXPIRED`, no charge |
| **Hold lapses while the payment is processing** | Sweeper releases the seats; Booking stays `PAYMENT_PENDING` (sets `hold_lapsed_at`). On success, confirm runs: seats still free (or only held by another lapsed hold) → sale completes; someone else owns a seat → `REFUND_PENDING` → `REFUNDED`. The user could not start a second booking meanwhile (R1), so R2 holds |
| Payment success arrives for a booking that cannot use it (should be impossible) | Recorded in `orphan_payments`, refunded, alert. Booking status unchanged |
| Double click / client retry | Same `Idempotency-Key` + same payload returns the same booking. Different payload → `422`. No second hold, no second charge |
| Two tabs, different idempotency keys | Second insert hits `one_active_booking` → `409 ACTIVE_BOOKING_EXISTS` |
| Booking crashes mid-saga | State is persisted. Recovery fences and resumes |
| Redis down | DB-only holds, rate limiter fails open, queue paused (no new admissions), realtime polling fallback |
| Kafka down | Outbox rows accumulate, publish when Kafka returns. Synchronous purchase path continues; payment outcomes are still reachable by recovery polling |
| Duplicate Kafka message | Natural idempotency (state-machine guards, per-seat versions) or `processed_events` |
| Poison message | Retried with backoff, then `<topic>.DLT`; alert on DLT depth |
| Sweeper vs confirm race | Both lock the hold row first, so they serialize. Sweeper only releases seats still carrying its `hold_id`; confirm's `WHERE` clause handles every ordering |
| Two Inventory instances race on the same seats | Redis Lua serializes in the fast path; Postgres `FOR UPDATE` + conditional update arbitrates otherwise |
| Two late payers race for the same lapsed seat | Seat row lock serializes; first confirm wins; second gets `SEAT_CONFLICT` → refund |

---

## 9. Event-driven design: Outbox and Kafka

### The dual-write problem
Committing to the DB and then publishing to Kafka is two writes with no shared transaction. A crash between them loses the event. Publishing first and then failing the commit announces something false.

### Transactional Outbox
The business change and an `outbox` row are written **in the same database transaction**. A relay then publishes outbox rows to Kafka and marks them sent.

```sql
CREATE TABLE outbox (
  id             BIGSERIAL PRIMARY KEY,
  event_id       UUID NOT NULL UNIQUE,
  aggregate_type TEXT NOT NULL,
  aggregate_id   TEXT NOT NULL,
  topic          TEXT NOT NULL,
  msg_key        TEXT NOT NULL,
  type           TEXT NOT NULL,
  payload        JSONB NOT NULL,
  headers        JSONB,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at   TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE processed_events (
  consumer_group TEXT NOT NULL,
  event_id       UUID NOT NULL,
  processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_group, event_id)
);
```

**Relay:** exactly **one active relay per service**, elected with `pg_try_advisory_lock(<service-relay-id>)`; other instances stand by and retry the lock every few seconds. The leader polls `WHERE published_at IS NULL ORDER BY id LIMIT 500`, publishes with an idempotent producer (`acks=all`, `enable.idempotence=true`), waits for acks, then sets `published_at`. Because there is one relay and it publishes in `id` order, per-key ordering in Kafka matches commit order closely enough for all consumers. (Outbox `id` order can differ from commit order for concurrent transactions; consumers therefore still use per-seat versions or state-machine guards, never "last message wins".) Delivery: **at-least-once**.

**Upgrade path:** Debezium CDC (reads the WAL, true commit order, no polling latency).

### Event envelope (versioned JSON)
```json
{
  "eventId": "uuid",
  "type": "SeatsHeld",
  "schemaVersion": 1,
  "aggregateType": "Hold",
  "aggregateId": "holdId",
  "occurredAt": "2026-01-01T10:00:00Z",
  "producer": "inventory-service",
  "traceId": "...",
  "payload": { }
}
```
Trace context also travels in Kafka headers (`traceparent`). Consumers ignore unknown fields and reject unknown `schemaVersion` majors to the DLT.

### Topics

| Topic | Key | Events | Producers → Consumers |
|---|---|---|---|
| `catalog.events` | `eventId` | `EventPublished`, `SaleOpened`, `EventClosed` | Catalog → Inventory, Realtime |
| `inventory.events` | `eventId` | `InventoryReady` | Inventory → Catalog |
| `inventory.seat-events` | `eventId` | `SeatsHeld`, `SeatsReleased`, `SeatsSold` (per-seat entries with `seatVersion`) | Inventory → Realtime (read model) |
| `inventory.hold-events` | `bookingId` | `HoldExpired`, `HoldReleased` | Inventory → Booking |
| `booking.events` | `bookingId` | `BookingCreated`, `BookingSeatsHeld`, `BookingConfirmed`, `BookingFailed`, `BookingExpired`, `BookingCancelled`, `BookingRefunded` | Booking → Realtime (notifications, slot release), future analytics |
| `payment.events` | `bookingId` | `PaymentSucceeded`, `PaymentFailed`, `RefundCompleted` | Payment → Booking |
| `*.DLT` | original | poison messages | → alerts / manual replay |

Demo setup: 6 partitions per topic, replication factor 1. Production notes: RF=3, `min.insync.replicas=2`. Keying `inventory.seat-events` by `eventId` means one event's seat stream is single-partition: fine for 5,000 seats, and the documented limit for mega-events.

### Consumers
- **Idempotent consumers:** where the side effect is a DB write, insert into `processed_events` **in the same transaction**; a duplicate hits the primary key and is skipped. Prefer natural idempotency where it exists (state-machine guards in Booking, per-seat version compare in Realtime).
- **Error handling:** Spring Kafka `DefaultErrorHandler` with exponential backoff, then `DeadLetterPublishingRecoverer` → `.DLT`. Non-retryable exceptions (bad payload, unknown schema) skip retries.
- **Why not "exactly-once":** Kafka's transactional EOS covers Kafka-to-Kafka only. End to end it is **at-least-once delivery + idempotent processing = effectively-once**.

### Retention
| Data | Retention | Rule |
|---|---|---|
| Kafka topics | 7 days | |
| `processed_events` | 14 days | Must exceed Kafka retention, or a very old redelivery could be applied twice |
| Published `outbox` rows | 3 days | Purged hourly in small batches |
| `booking_transitions` | kept | Audit trail |
| Redis `admitted`, `queue` | deleted 24 h after `EventClosed` | |

### Why Kafka (and what else would work)
Notification, read-model and analytics consumers must not slow or break the purchase path, and a replayable, ordered log lets read models be rebuilt. RabbitMQ would also work for notifications. Kafka is chosen for the durable log, partition ordering and replay of the seat-event stream.

---

## 10. Virtual waiting room

### 10.1 Problem
At on-sale, 100k users hammer the hold endpoint. Rate limiting alone just returns errors and rewards whoever retries fastest. A waiting room controls how many people are inside the buying flow at once, and tells everyone else the truth about their chances.

### 10.2 Join
`POST /api/v1/queue/{eventId}/join` runs an atomic Lua script:
- If `queuestate` is `SOLD_OUT` or `CLOSED`, return that state (no entry created).
- If the user is already in `admitted:{evt}`, return `ADMITTED`.
- If the user is already in `queue:{evt}`, return their current rank.
- Otherwise `INCR queueseq` and `ZADD` with that sequence as score.

Re-joining or opening multiple tabs never changes position (one entry per `userId`). Joining is allowed from 30 minutes before `onSaleAt` (`queuestate = WAITING`).

### 10.3 Position
`ZRANK` → position plus estimated wait (position ÷ recent admission rate, smoothed). Pushed over WebSocket as `queue_position`, throttled to at most once per 5 s per user and only when it changed.

### 10.4 Admission controller
Scheduled every 1 s; safe on multiple instances because the move is one Lua script:
1. Remove expired members from `admitted:{evt}` (score < Redis `TIME`).
2. Read `seatcount:{evt}` and decide the queue state:
   - `available > 0` → `OPEN`.
   - `available = 0` and `held > 0` → `PAUSED_ALL_HELD` (seats may come back when holds expire).
   - `available = 0` and `held = 0` → `SOLD_OUT`.
3. If `OPEN`: `capacity = min(maxActive, available × admitFactor) − size(admitted)`. Pop up to `capacity` users from the queue head (`ZPOPMIN`) and add them to `admitted` with score `now + slotTtl`.
4. Publish newly admitted users and any state change on `queueupdates:{evt}`.

- `maxActive` is tuned to what Inventory can handle (config; optionally adaptive to Inventory p95).
- `admitFactor` (default 2) admits a little more than one person per available seat, because many admitted users fail or pick taken seats. It keeps 50,000 people from being admitted to fight over the last 10 seats.
- **Slot release:** a user's slot is removed early when their booking is `CONFIRMED` (Realtime consumes `BookingConfirmed`). Other outcomes (`HOLD_FAILED`, `EXPIRED`, `PAYMENT_FAILED`, `CANCELLED`) keep the slot until it expires so the user can retry with other seats.
- **Sold-out flow:** on `SOLD_OUT`, every queued user gets `queue_state {state:'SOLD_OUT'}` and the frontend shows a clear sold-out page. On `PAUSED_ALL_HELD`, queued users see "All seats are currently held. Some may be released in the next few minutes. Stay in line." If holds expire and seats return, the state goes back to `OPEN` and admissions resume, in queue order.
- **Close:** `EventClosed` sets `CLOSED`, clears admissions and notifies everyone.

### 10.5 Admission token
`POST /api/v1/queue/{eventId}/token` (user must be in `admitted`) returns a JWT: `sub` = userId, `evt` = eventId, `exp` = the slot's expiry, `jti`. Signed with Realtime's asymmetric key; Booking verifies it with the public key (JWKS from Realtime, cached).

Booking accepts a token only if: signature valid, `sub` matches the caller, `evt` matches the request, not expired, **and** `ZSCORE admitted:{evt} userId` exists (one Redis call). The last check means a slot released early (after a confirmed booking) cannot be reused. If Redis is unavailable, Booking falls back to signature + expiry only; the token is still short-lived and bound to user and event.

Without a valid token, `POST /bookings` returns `403 NOT_ADMITTED`.

### 10.6 Fairness and honest limitations
- **Fairness (stretch):** users who join *before* `onSaleAt` get a randomized position (score = random within the pre-sale cohort) rather than arrival order, so being first to click is not an advantage.
- **Not solved (document this):** bots, multi-account abuse and sophisticated queue-jumping. Real systems add identity checks and behavioral detection. This project demonstrates the architecture and the capacity-control mechanism.

---

## 11. Real-time layer (WebSockets)

### Why real-time here
1. Seats turn grey as others hold them, so users stop clicking seats that are gone.
2. Users see their queue position, sold-out state and the "you're in" moment.
3. Booking result notifications.

### Connection
- Browsers cannot set headers on WebSocket handshakes, so the client first calls `POST /api/v1/realtime/ws-ticket` (authenticated) to get a single-use ticket (stored in Redis for 30 s, consumed with `GETDEL`), then connects to `/ws?ticket=...`.
- Heartbeat ping/pong (30 s), idle timeout, max connections per node, per-user connection cap (5).

### Message protocol (JSON)

Client → server:
```json
{"type":"subscribe","eventId":42}
{"type":"unsubscribe","eventId":42}
{"type":"ping"}
```

Server → client:
```json
{"type":"delta","eventId":42,"fromVersion":1041,"toVersion":1043,"changes":[{"seatId":"B2-K-14","state":"HELD"},{"seatId":"B2-K-15","state":"HELD"}]}
{"type":"queue_position","eventId":42,"position":1840,"etaSeconds":210}
{"type":"queue_state","eventId":42,"state":"PAUSED_ALL_HELD"}
{"type":"admitted","eventId":42,"expiresAt":"..."}
{"type":"booking_update","bookingId":"...","status":"CONFIRMED"}
{"type":"resync_required","eventId":42}
```

### Correctness: snapshot + version-ranged deltas
Pub/sub is fire-and-forget and sockets drop, so messages can be missed. The fix:
1. Client calls `GET /api/v1/realtime/events/{id}/snapshot`. It returns all seat states and `version`, read **atomically** by one Lua script (`HGETALL seatmap` + `GET ver` in the same script).
2. The applier publishes one message per applied event on `seatdelta:{evt}` with its exact `version`. Each Realtime node buffers these per event and every ~150 ms flushes **one coalesced delta** per event to its sessions: `fromVersion` = first buffered version, `toVersion` = last, `changes` = the final state of each changed seat.
3. Client keeps `lastVersion`:
   - `toVersion <= lastVersion` → ignore (old).
   - `fromVersion == lastVersion + 1` → apply, set `lastVersion = toVersion`.
   - `fromVersion > lastVersion + 1` → **gap**: refetch the snapshot.
   - Overlap (`fromVersion <= lastVersion < toVersion`) → apply (states are absolute, so re-applying is harmless), set `lastVersion = toVersion`.
4. If a node notices a gap in what it received from pub/sub, it sends `resync_required` to its sessions for that event instead of a delta.
5. On reconnect, the client re-subscribes with exponential backoff + **full jitter** and refetches the snapshot.

### Snapshot at scale (resync storms)
If a Realtime node restarts, thousands of clients resync at once. The snapshot endpoint serves a cached, gzipped body per version (`snap:{evt}`, TTL 1 s, built under a single-flight lock), with an `ETag` of the version. One build serves everyone who asks within that second.

### Scaling
- Multiple Realtime nodes. One logical consumer group applies seat events (one applier per event, see §6.6) and publishes on `seatdelta:{evt}`. Every node subscribes and forwards to its local sessions.
- **Batching** (above) drastically reduces message volume during a rush.
- **Backpressure:** each session has a bounded outbound queue (e.g. 64 messages). A slow client is sent `resync_required` and its queue is cleared; if it stays behind it is disconnected and will reconnect and resnapshot. One slow client never blocks others.
- **Fan-out math to measure, not assume:** 10,000 clients × ~7 flushes/s is ~70k messages/s per node at peak. This is a k6 scenario (§16.9).

**Principle:** the WebSocket is a UX layer only. The server never trusts the client's view of availability; every hold is validated server-side.

---

## 12. Frontend (React + TypeScript)

Intentionally small but real. It exists mainly to make the concurrency story demonstrable.

- **Auth pages** (register/login), token handling (refresh from v1.0).
- **Event list and event detail** (shows `READY` "on sale at ...", `ON_SALE`, `CLOSED`).
- **Seat map** rendered on **canvas** (one draw layer, hit-testing for clicks), from static geometry + live state: available, selected, held by you, held by others, sold. Deltas repaint only changed seats. One React component per seat does not scale to large venues and would become the measured bottleneck.
- **Waiting room page:** position, ETA, queue state (`WAITING`, `OPEN`, `PAUSED_ALL_HELD`, `SOLD_OUT`, `CLOSED`), auto-redirect on admission, admission-slot countdown.
- **Seat selection** (max 6, minus seats already owned) → create booking → **hold countdown** driven by server `expiresAt`.
- **Checkout** with mock payment options (success / decline / slow / timeout / flaky) so every outcome can be demoed.
- **Result pages:** seat just taken (409 with seats highlighted), sale not open, payment failed, hold expired, "hold lapsed, still trying" (late payment), confirmed, refunded, sold out.
- **Pending state:** if `POST /bookings` returns `202 PENDING`, show "confirming your seats" and poll / listen for `booking_update`.
- **My bookings** page.
- Generates one `Idempotency-Key` per purchase attempt (new key for a new seat selection), disables double-submit, handles reconnects and resync.
- **Demo page** (admin only): seat map + live counters (available/held/sold, queue length, admitted) for showing a rush from the simulator.
- State: TanStack Query for server state; a small custom hook for the WebSocket store.

---

## 13. Security

- Passwords hashed (BCrypt/Argon2). Access JWTs short-lived, asymmetric signing. Refresh tokens hashed at rest, rotated, revocable.
- JWT validated at the gateway **and** in each service.
- Role-based authorization (`ADMIN` for catalog management, demo page and mock-PSP config).
- Admission tokens bound to user + event, expiry equal to the admitted slot, and checked against the live admitted set.
- WebSocket tickets single-use, 30 s.
- Input validation (Bean Validation), request-size limits, parameterized SQL only.
- Rate limiting at the gateway (per user and per IP).
- `/internal/**` never routed through the gateway. Service-to-service calls carry a service credential (client-credentials JWT from the auth module, or an internal API key; documented as a simplification of mTLS).
- Per-service Postgres users; each can only access its own schema.
- Test-auth profile cannot start alongside `prod`; its signing key is separate.
- No secrets in the repo: `.env.example` plus environment variables, `.gitignore` for real env files.
- Server-side price; the client is never trusted for amounts or availability.
- Dependency scanning (Dependabot) in CI.

---

## 14. Resilience patterns used (and where)

| Pattern | Where |
|---|---|
| Timeouts on every remote call | Booking → Inventory/Payment, Inventory → Catalog, Gateway → services |
| Retries with exponential backoff + jitter | Idempotent calls only |
| Circuit breaker | Booking → Payment, Booking → Inventory, Inventory → Redis (degrade to DB-only) |
| Bulkhead | Separate pools for Inventory vs Payment calls |
| Idempotency | API (`Idempotency-Key`), service calls (`bookingId`, deterministic `holdId`), consumers |
| **Fencing / tombstones** | Recovery aborts holds and voids payments by `bookingId` before deciding an outcome |
| Saga + compensation | Booking workflow |
| Transactional outbox | Every event producer |
| Leader election via advisory lock | Outbox relay, Catalog sale scheduler |
| DLQ | All Kafka consumers |
| Optimistic locking | Booking transitions; seat versions |
| Pessimistic locking with ordered keys | Multi-seat hold and confirm (`ORDER BY seat_id FOR UPDATE`) |
| Provisional leases | Redis gate keys (30 s until DB commit) |
| Load shedding / admission control | Waiting room, gateway rate limits |
| Graceful degradation | Redis down → DB-only; read model stale → snapshot resync; Kafka down → recovery polling |
| Reconciliation jobs | Booking recovery, Inventory sweeper, Redis rebuild, orphan-payment refunds |
| Graceful shutdown, health/readiness probes | All services (readiness false until Flyway done and, for Inventory, Redis rebuilt) |

---

## 15. Observability

- **Metrics (Micrometer → Prometheus → Grafana):** hold attempts/successes/conflicts and latency, Redis-gate rejections vs DB-arbiter rejections, provisional keys promoted vs expired, sale-not-open rejections, queue depth, admission rate, admitted users, queue state, time to sold-out, saga counts per state, recovery actions by type (aborts, voids, retries), bookings stuck beyond thresholds, late-payment outcomes (rescued vs refunded), orphan payments, outbox lag (oldest unpublished row age), Kafka consumer lag, DLT depth, WebSocket connections, deltas per second, outbound queue drops and resyncs, snapshot cache hit rate, payment outcome rates, JVM/HikariCP/Lettuce stats.
- **Tracing (OpenTelemetry → Jaeger):** one trace across Gateway → Booking → Inventory → Payment, with context propagated through Kafka headers so async hops join the same trace.
- **Logging:** structured JSON with `traceId`, `bookingId`, `eventId`, `userId` (no PII beyond IDs).
- **Dashboards (screenshot for the README):** "sale in progress" (conflicts, latency, queue, availability), "reliability" (outbox lag, DLT, stuck bookings, recovery actions, orphan payments), "infrastructure."
- **Alerts (documented rules):** outbox lag above threshold, DLT non-empty, stuck bookings above zero, orphan payments above zero, `sale_open_blocked`, error-rate spike.

---

## 16. Testing strategy (where the project earns credibility)

1. **Unit tests:** every allowed and every illegal booking transition, price calculation, idempotency logic, token validation, queue-state decision function, client delta-application logic (TypeScript).
2. **Property-based tests (jqwik):** random event sequences against the Booking state machine never reach an invalid state and never leave a terminal state. Random hold/release/confirm/expire sequences never violate seat-state invariants.
3. **Integration tests with Testcontainers:** real Postgres, Redis and Kafka. Lua scripts, hold/confirm/extend/abort SQL, outbox relay (including leader failover), consumer idempotency, DLT behaviour.
4. **Headline concurrency test:** 1,000+ concurrent clients fight for 100 seats (including overlapping multi-seat requests). Assert exactly 100 seats sold, zero seats in two bookings, every loser got a clean `409`.
5. **Targeted race tests** (one per hard case, each using test hooks that pause a thread at a named point, enabled only in the test profile):
   - *Recovery vs slow hold:* pause the hold after stage 2, let recovery abort, resume → hold refused, no `HELD` row, no Redis keys after 30 s.
   - *Crash between gate and commit:* kill the hold after stage 2 → provisional keys expire, seat bookable again within 30 s.
   - *Late payment, seat free:* hold lapses during `tok_slow` → `CONFIRMED`.
   - *Late payment, seat taken:* hold lapses, another user buys → `REFUNDED`, I2 holds.
   - *Two late payers, one seat:* exactly one `CONFIRMED`, the other `REFUNDED`.
   - *Pay after expiry:* `409 HOLD_EXPIRED`, no payment row.
   - *Crash before payment create:* recovery voids, `PAYMENT_FAILED`, no charge.
   - *Cancel vs pay:* exactly one wins.
   - *R1/R2:* two tabs → one booking; a user with 4 confirmed seats cannot hold 3 more.
   - *Sale gate:* holds before `InventoryReady` / `SaleOpened` → `SALE_NOT_OPEN`.
   - *Deltas:* batched deltas, dropped pub/sub message, duplicate seat events → client state equals snapshot.
6. **Invariant checker** (`chaos/invariant-checker`, a small Java CLI using the read-only all-schemas user; run after any test, load run or chaos run, after a quiesce period of hold TTL + recovery interval):
   - I1: no `(event_id, seat_id)` in more than one ticket; every `SOLD` seat has exactly one ticket whose booking is `CONFIRMED`.
   - I2: every `SUCCEEDED` payment maps to a `CONFIRMED` booking, a `REFUNDED` booking, or a `REFUNDED` orphan payment; every refund maps to a succeeded payment.
   - R1: at most one active booking per user per event.
   - R2: tickets per user per event ≤ 6.
   - R3: no booking non-terminal beyond its max age; no `HELD` seat with expiry older than sweeper tolerance.
   - No `HELD` seat without a `HELD` hold row owning it.
   - Redis consistent with Postgres (after rebuild if needed); read-model counts equal `seat_state` counts.
7. **Chaos / failure scripts** (`docker compose stop/kill/pause`, Toxiproxy for latency and partitions): kill Redis mid-sale, restart Booking mid-saga, pause Kafka, kill an Inventory instance during holds, make Payment time out, partition Booking from Payment. After each, run the invariant checker. Expected: all invariants hold, no stuck bookings, no lost events.
8. **Contract tests (optional):** Pact or Spring Cloud Contract between Booking and Inventory/Payment.
9. **Load tests (k6, scripts committed to the repo):**
   - *Hot-seat contention:* many users, small seat pool.
   - *Read-heavy browsing + resync storm:* event, snapshot endpoint, mass reconnect.
   - *Full purchase flow:* queue → hold → pay → confirm with a mix of payment outcomes.
   - *Waiting-room surge:* spike of joins, steady admission, through to sold-out.
   - *WebSocket fan-out:* 5,000–10,000 sockets subscribed to one event during a rush.
   - **Realism rules:** users are pre-minted with the test-auth profile (no BCrypt in the load path); the number of concurrent sockets is whatever one machine sustains (record it); 100k is stated as extrapolated from measured per-node capacity, never claimed.
   - Record p50/p95/p99, throughput, error rate, hardware and configuration. Find **one real bottleneck** (hot Redis key, Hikari pool exhaustion, slow outbox relay, Kafka partition skew, canvas repaint), fix it, document before/after. That story is worth more than the headline number.
10. **Frontend:** component tests for the seat map and delta logic, plus one end-to-end smoke test (Playwright) of queue → hold → pay → confirm.

### Rush simulator
`tools/rush-simulator` (Spring Boot CLI) creates and publishes a demo event, waits for `READY`, then spawns N virtual users (test-auth tokens) who join the queue, pick seats with configurable overlap, hold, and pay with a configurable token mix. It prints a live summary and finishes by running the invariant checker. Paired with the admin demo page and Grafana, it is the 60-second demo for interviews and the README GIF.

---

## 17. DevOps and delivery

- `docker compose up` starts everything: Postgres (one instance, schema per service), Redis, Kafka (KRaft), all services, frontend, Prometheus, Grafana, Jaeger. Healthchecks and `depends_on: condition: service_healthy`.
- `compose.lite.yml` for 8 GB machines and the hosted demo: Redpanda instead of Kafka, no Jaeger, one instance per service.
- **CI (GitHub Actions):** build, unit tests, Testcontainers integration tests, concurrency test, formatting/lint (Spotless/Checkstyle), Docker image builds, image push to GHCR on main, Compose smoke test that runs a small rush simulation plus the invariant checker.
- Flyway migrations run on service start.
- Configuration through environment variables (12-factor); every timing in §5 is a variable.
- **Hosted demo (recommended):** `compose.lite.yml` on a small VM, demo data seeded, "simulate rush" protected by an admin login. A link a recruiter can click is worth more than Kubernetes manifests.
- Kubernetes manifests are an optional extra after everything else is finished and tested.

### Suggested monorepo layout
```
seatlock/
├─ README.md                  # architecture diagram, run instructions, trade-offs, results, demo GIF
├─ docs/
│  ├─ adr/                    # Architecture Decision Records (list in §19)
│  ├─ architecture.md
│  ├─ failure-modes.md
│  └─ load-test-results.md
├─ services/
│  ├─ api-gateway/
│  ├─ user-catalog-service/
│  ├─ inventory-service/
│  ├─ booking-service/
│  ├─ payment-service/
│  └─ realtime-service/
├─ libs/
│  └─ common/                 # event envelope, outbox relay, idempotent-consumer helper,
│                             # tracing/logging helpers (NO shared domain entities)
├─ tools/
│  └─ rush-simulator/
├─ frontend/                  # React + Vite + TS
├─ load-tests/                # k6 scripts
├─ chaos/                     # failure-injection scripts + invariant checker
├─ deploy/
│  ├─ docker-compose.yml
│  ├─ compose.lite.yml
│  ├─ prometheus/ grafana/
│  └─ k8s/                    # optional
└─ .github/workflows/
```

---

## 18. API surface (summary)

All public APIs are versioned under `/api/v1`, documented with OpenAPI, and use RFC 7807 `application/problem+json` for errors, with a stable `code` field.

| Service | Endpoint | Notes |
|---|---|---|
| Auth | `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` | JWKS at `/.well-known/jwks.json` |
| Auth (loadtest profile only) | `POST /auth/test-token` | Mints synthetic-user tokens with the test key |
| Catalog | `GET /events`, `GET /events/{id}`, `GET /events/{id}/seatmap` | Cached, ETag |
| Catalog (admin) | `POST /admin/venues`, `POST /admin/events`, `POST /admin/events/{id}/publish`, `PATCH /admin/events/{id}` (onSaleAt/closesAt while not ON_SALE), `POST /admin/events/{id}/close` | `ADMIN` |
| Realtime | `POST /queue/{eventId}/join`, `GET /queue/{eventId}/status`, `POST /queue/{eventId}/token` | Waiting room |
| Realtime | `GET /realtime/events/{id}/snapshot`, `POST /realtime/ws-ticket`, `WS /ws` | Live data |
| Realtime (admin) | `GET /realtime/admin/events/{id}/stats` | Demo page counters |
| Booking | `POST /bookings` | Creates booking + hold. Requires `Idempotency-Key`, `X-Admission-Token`. `201`, `202 PENDING`, `409`, `403`, `422` |
| Booking | `POST /bookings/{id}/pay` | `202`, or `409 HOLD_EXPIRED` / `ILLEGAL_STATE` |
| Booking | `DELETE /bookings/{id}` | Cancel (only while `SEATS_HELD`) |
| Booking | `GET /bookings/{id}`, `GET /bookings?eventId=` | Status and history |
| Payment (admin) | `PUT /admin/mock-psp/config` | Latency and failure-rate controls |

Internal (never via gateway, service credential required):
- Inventory: `POST /internal/holds`, `POST /internal/holds/{id}/extend`, `/confirm`, `/release`, `POST /internal/holds/abort`, `GET /internal/holds?bookingId=`, `GET /internal/events/{id}/seat-state`.
- Catalog: `GET /internal/events/{id}/seats`.
- Payment: `POST /internal/payments`, `GET /internal/payments/{id}`, `GET /internal/payments?bookingId=`, `POST /internal/payments/void`, `POST /internal/payments/{id}/refund`.
- Realtime: `GET /.well-known/admission-jwks.json` (admission-token public keys).

Error semantics: `400` validation; `401` unauthenticated; `403 NOT_ADMITTED` / forbidden; `404`; `409` with `code` in `SEAT_CONFLICT`, `SALE_NOT_OPEN`, `ACTIVE_BOOKING_EXISTS`, `SEAT_LIMIT`, `HOLD_EXPIRED`, `ILLEGAL_STATE`; `422 IDEMPOTENCY_KEY_REUSED`; `429` rate limited; `503` degraded dependency.

---

## 19. Design principles applied (checklist) and ADRs

1. **Invariant-first design:** state what must never be false (I1, I2, R1–R3), then design everything to protect it.
2. **Single owner per piece of data;** communicate via APIs and events, never shared tables (enforced by DB grants).
3. **Defense in depth:** Redis gate, Postgres conditional update, unique-constraint backstop; quota checked at Booking and at confirm.
4. **Source of truth vs derived state:** Postgres truth; Redis and read model rebuildable.
5. **TTL is an optimization, not a guarantee:** a sweeper provides the guarantee.
6. **Idempotency everywhere,** with deterministic IDs so retries are recognisable.
7. **Fence, then read:** an unknown outcome is resolved by making the late operation impossible, never by guessing from silence.
8. **At-least-once + idempotent processing = effectively-once;** exactly-once is not claimed.
9. **Atomicity at the right level:** ACID inside a service, Saga across services, Lua for Redis multi-key atomicity.
10. **Terminal means terminal:** no transition leaves a terminal booking state.
11. **Fail fast and degrade gracefully:** timeouts, breakers, bulkheads, fallbacks, load shedding.
12. **Backpressure and admission control** instead of unbounded queues.
13. **Version-based ordering:** per-seat versions and gap-free, range-carrying delta versions.
14. **Never trust the client:** server decides availability, price and limits.
15. **Observability as a feature.**
16. **Prove it:** every claim is backed by an automated test or a reproducible load/chaos run.
17. **Small, finishable scope:** a working, defensible system at every ship point.

**ADRs to write (one page each, as you build):**
ADR-001 Postgres as arbiter, Redis as gate · ADR-002 Orchestrated saga · ADR-003 Outbox poller with single leader · ADR-004 Late-payment policy and R1 · ADR-005 Fencing recovery with tombstones · ADR-006 Provisional Redis leases · ADR-007 Prices frozen in Inventory · ADR-008 Raw WebSocket over SSE/STOMP · ADR-009 Version-ranged deltas · ADR-010 Waiting-room admission factor and sold-out states · ADR-011 Schema-per-service on one Postgres.

---

## 20. Known limitations and honest trade-offs

- Single Redis node and single Kafka broker in the demo. Production notes (Cluster, RF=3) are documented but not exercised.
- Hash-tagged keys and `eventId`-keyed seat topic put one event on one Redis shard and one partition: a mega-event hot-key trade-off.
- Outbox poller adds latency (polling interval) and is not as scalable as Debezium CDC.
- Mock payment provider only. Real PSP webhooks, 3-D Secure and PCI scope are out of scope.
- The late-payment rescue means a user who lets their hold lapse during payment keeps their one active booking slot until the payment resolves (bounded by the PSP max processing time).
- Waiting room does not defeat bots or multi-account abuse; when Redis is down, the queue pauses rather than degrading to open admission.
- Service-to-service auth is simplified (client-credentials JWT or internal key, not mTLS).
- Load-test numbers come from a laptop-class environment against a mock PSP, with pre-minted users and fewer than 100k sockets. They show the design's behaviour, not production capacity.
- No cancellation after sale in v1.

---

## 21. Build order and ship points

Every ship point is a complete, demoable, resume-worthy system. Stop at any of them and the project still stands.

| Ship point | Build | What you can claim |
|---|---|---|
| **v0.1 Contention core** | Monorepo, Compose (Postgres, Redis), Flyway. Catalog (venues, events, prices, publish) and Inventory (seeding, sale state, hold/confirm/release/sweeper) on **Postgres only** first; write the concurrency test and invariant checker (I1, R2) **now** and watch what breaks. Then add the Redis Lua gate with provisional leases; measure DB load before/after. Booking with create + hold only. Services validate JWTs themselves; simple login. CI with Testcontainers | "Zero oversell with 1,000 concurrent clients; Redis gate cut DB rejections by X%" |
| **v0.2 Saga** | Payment mock (async contract, all tokens, void), full Booking state machine, idempotency keys, optimistic locking, recovery job with fencing, late-payment policy, orphan-payment path, race tests from §16.5 | "Saga with compensation that survives timeouts, crashes and late payments; I2 checked automatically" |
| **v0.3 Events and live map** | Outbox + single-leader relay, Kafka, idempotent consumers, DLT, InventoryReady/SaleOpened gating, Realtime read model, WebSocket with version-ranged deltas and resync, minimal React seat map (canvas) and checkout | "Live seat map with gap-free versioned deltas; no lost events across Kafka outages" |
| **v1.0 Full system** | Waiting room with sold-out flow and admission tokens, API Gateway (JWT, rate limiting), refresh tokens, full frontend, observability dashboards, chaos scripts, k6 suites, one bottleneck fixed with before/after, rush simulator, README with ADRs and results, hosted demo | Full project |

The React frontend can start in parallel from v0.2.

**Stretch (only after v1.0 works):** cancellation + refund of confirmed bookings, Debezium instead of the poller, Kubernetes manifests, Avro + Schema Registry, per-section sharding of the event key space, randomized pre-sale queue fairness, Playwright end-to-end in CI, contract tests.

---

## 22. Interview talking points

- **How is double booking prevented?** Redis Lua gate for cheap rejection, Postgres conditional update as arbiter, `UNIQUE (event_id, seat_id)` on `tickets` as the final backstop.
- **What if Redis dies or lies?** State is rebuildable from Postgres; Inventory falls back to DB-only; correctness is unaffected, only performance. Provisional leases mean a crash can't make free seats look taken for long.
- **What if a request is slow enough to race with your own recovery job?** Recovery fences first: it aborts the hold (tombstone keyed by `bookingId`) or voids the payment, so the late request is refused. It never infers failure from silence.
- **Can a user end up with two confirmed bookings or more than 6 seats?** No. A booking whose hold lapses during payment stays active, so R1 blocks a second booking; R2 is checked at creation and enforced again at confirm from `tickets`.
- **Payment succeeded after the hold expired?** Confirm is attempted; if the seats are still free (or only held by another lapsed hold) the sale completes, otherwise refund compensation. I2 guarantees the money is never stuck.
- **Why a Saga instead of 2PC? Orchestration vs choreography?** Separate databases; a linear flow with explicit compensations suits an orchestrator with persisted state.
- **What does the Outbox solve and what are its semantics?** The dual-write problem; at-least-once publishing; one leader relay; idempotent consumers.
- **Is Kafka exactly-once?** Not end to end. At-least-once plus idempotent consumers gives effectively-once.
- **Why not rely on Redis key-expiry events?** They are not reliable. A DB-backed sweeper is.
- **Why does Lua make the hold atomic and what changes in Redis Cluster?** Single-threaded script execution; hash tags so all keys share a slot.
- **How do you avoid deadlocks on multi-seat holds?** Consistent lock ordering (`ORDER BY seat_id FOR UPDATE`), and the Redis gate removes most contention.
- **Your deltas are batched; how does the client detect a missed message?** Each delta carries `fromVersion`/`toVersion`; the version only increments on real changes, so any gap is detectable and triggers a snapshot.
- **What does someone who will never get a seat see?** Their queue position, then "all seats held, stay in line" or a definitive sold-out page, driven by live availability.
- **How did you load test 100k users on a laptop?** I didn't claim to. Pre-minted tokens, measured per-node socket capacity, and extrapolation stated as such.
- **How did you find a bottleneck?** k6 in the repo; one measured bottleneck found and fixed, with before/after numbers.
- **What changes at 100x scale?** Shard by event/section, a dedicated queue service, Debezium CDC, Redis Cluster, read replicas for catalog, Avro + Schema Registry, a CDN for static seat-map data and snapshots.

---

## 23. Glossary

- **Hold:** a temporary, expiring reservation of seats for one booking.
- **Provisional lease:** a Redis hold key with a short TTL, promoted to the full TTL only after the DB commit.
- **Tombstone / fence:** a row recorded for a `bookingId` that makes any later hold or payment for that booking impossible.
- **Saga:** a sequence of local transactions across services with compensating actions on failure.
- **Orchestration:** a central coordinator drives the saga's steps.
- **Outbox:** a table written in the same transaction as the business change, later published to the broker.
- **Idempotency:** repeating an operation produces the same result as doing it once.
- **DLQ / DLT:** dead-letter queue/topic for messages that repeatedly fail.
- **CQRS (light):** a separate read model (the realtime seat map) derived from write-side events.
- **Optimistic locking:** version checks that reject stale writers.
- **Backpressure:** slowing or shedding load instead of letting queues grow unbounded.
- **Admission token:** short-lived signed proof that the waiting room admitted a user, valid only while their slot is.
- **Late payment:** a payment that succeeds after the hold it was meant for has expired.
- **Orphan payment:** a successful charge for a booking that can no longer use it; always refunded.
- **Effectively-once:** at-least-once delivery combined with idempotent processing.

---

## Appendix A. Changes from v1

| # | v1 problem | v2 resolution | Where |
|---|---|---|---|
| 1 | `EXPIRED` was terminal yet could become `CONFIRMED`; a user could hold two confirmed bookings and 12 seats | Hold lapse during payment keeps the booking `PAYMENT_PENDING` (active); `EXPIRED` only before payment and truly terminal; R2 checked at creation and confirm; orphan-payment safety net | §5, §6.3, §6.4 |
| 2 | Hold expiry during `PAYMENT_PENDING` undefined | `HoldExpired` ignored as a transition; `hold_lapsed_at` set; outcome via payment then confirm | §6.4, §8 |
| 3 | Pay could charge after the hold expired | Extend before transition; `HOLD_EXPIRED` → `EXPIRED`, no charge | §8 |
| 4 | Recovery could create orphan holds by reading "not found" during a slow request | Fence-then-read: `abort` tombstones in Inventory, `void` tombstones in Payment | §6.3, §6.4, §6.5 |
| 5 | Lua idempotency depended on a `holdId` that could change per retry | `holdId = UUIDv5(bookingId)`; `seat_holds` checked first | §6.3, §7 |
| 6 | Crash between Redis gate and DB left phantom holds for 8 min | Provisional 30 s lease, promoted after commit | §6.3, §7 |
| 7 | Batched deltas broke consecutive-version gap detection | `fromVersion`/`toVersion`; version bumps only on real change; node-level gap handling | §6.6, §11 |
| 8 | Snapshot read not atomic | One Lua script for map + version; cached per version | §11 |
| 9 | Booking needed Catalog prices on the hot path | Prices frozen into `seat_state` at seeding; hold returns line items | §5, §6.3 |
| 10 | Sale could open before Inventory was seeded; no owner for `ON_SALE` | `InventoryReady` → `READY`; Catalog scheduler opens only `READY` events; Inventory sale state | §5, §6.2, §6.3 |
| 11 | Admission token outlived the admitted slot | Token `exp` = slot expiry; slot released on confirm; Booking checks live admitted set | §10.4, §10.5 |
| 12 | No sold-out flow | Queue states from live availability, `admitFactor`, sold-out and paused UX | §10.4, §12 |
| 13 | No per-user seat cap across bookings | R2 at creation and confirm | §5, §6.3 |
| 14 | Payment API sync vs async ambiguous | Async contract (`202 PROCESSING` + events), `tok_timeout` defined precisely | §6.5 |
| 15 | Gateway routes missing `/realtime/**`; phantom public Inventory route | Routing table rewritten | §6.1 |
| 16 | Outbox ordering claim relied on versions that most events lack | Single leader relay per service via advisory lock | §9 |
| 17 | Invariant checker crossed service DBs implicitly; transient states | Read-only all-schemas user, quiesce period, I2 and R1–R3 added | §16 |
| 18 | Sweeper could release seats already taken over | Release only seats still carrying the sweeper's `hold_id` | §6.3 |
| 19 | 8-minute timings made tests slow | All timings configurable | §5 |
| 20 | No retention for outbox / processed_events | Retention table tied to Kafka retention | §9 |
| 21 | Load-test realism (100k logins/sockets on a laptop) | Test-auth profile, measured socket capacity, explicit extrapolation | §6.2, §16.9 |
| 22 | Resync storms after node restart | Snapshot cached per version with single-flight | §11 |
| 23 | Seat map rendering at scale | Canvas renderer | §12 |
| 24 | Seat identifiers undefined across services | Canonical `seatId` format | §5 |
| 25 | No demo story | Rush simulator, admin demo page, hosted lite demo | §16, §17 |
| 26 | Scope risk | Ship points v0.1 → v1.0 | §21 |
