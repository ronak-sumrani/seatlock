# SeatLock Spec Review

Review of `SeatLock_Project_Specification.md` (v1, 23 sections). Section numbers below refer to the spec.

## Verdict

This is an unusually strong spec for a portfolio project. The invariant-first framing, Postgres-as-arbiter with Redis as a disposable gate, the `tickets` unique-constraint backstop, the honest "effectively-once" wording, and the invariant checker are all things interviewers rarely see from SDE-1 candidates. You do not need more features. What it needs is:

1. **Fixes to a handful of real correctness holes**, mostly at the seams between services (section A). Several of these are exactly the questions a sharp interviewer would ask, so fixing them in the doc is worth more than any new feature.
2. **Resolving inconsistencies between sections** (section B).
3. **A few small additions** that make the system complete (section C).
4. **A hard scope cut line**, because the full spec is several months of solo work (section D).

---

## A. Correctness gaps (fix these first)

### A1. Late payment after `EXPIRED` can break "one active booking" and the 6-seat cap
`EXPIRED` is listed as terminal (§6.4), yet the diagram allows `EXPIRED → late payment → CONFIRMED`. Worse, the partial unique index excludes `EXPIRED`, so this sequence is legal today:

1. Booking B1 holds A1–A6, user walks away, B1 → `EXPIRED`.
2. User creates B2 for B1–B6 (allowed, B1 is no longer "active").
3. B1's slow payment succeeds; A1–A6 are still `AVAILABLE`, so confirm sells them.
4. B2 also confirms. User now owns 12 seats with two confirmed bookings.

**Fix (pick one, and write it down as an ADR):**
- Simplest: once a booking is `EXPIRED`, a late `PaymentSucceeded` always goes to `REFUND_PENDING`. You lose the "rescue the sale" nicety but the rules become trivially defensible.
- Or keep the rescue, but make `EXPIRED` non-terminal while a payment exists (e.g. a `PAYMENT_PENDING` booking whose hold expired stays `PAYMENT_PENDING`, not `EXPIRED`), keep it in the partial index, and enforce a per-user-per-event seat cap at confirm time.

Either way, make the state diagram, the terminal-state list and the index agree.

### A2. Hold expiry while `PAYMENT_PENDING` is undefined
The diagram only has `SEATS_HELD → EXPIRED`. With `tok_slow` or `tok_timeout`, the hold can expire after the booking reached `PAYMENT_PENDING`. The sweeper will then release the seats and emit `HoldExpired` to Booking. Specify that Booking **ignores** `HoldExpired` in `PAYMENT_PENDING` (payment truth decides), and that the outcome then flows through the late-payment policy from A1.

### A3. `pay` must not charge if the hold extension fails
§8 step 5: transition to `PAYMENT_PENDING`, extend hold, call Payment. If the sweeper already released the hold, `extend` fails. Spell out: extend failure → booking `EXPIRED`, **no charge attempted**. Also say the extend updates both the DB `expires_at` and the Redis key TTLs, and the booking's own `hold_expires_at`.

### A4. Recovery job can create orphan holds (the "slow request vs. recovery" race)
`PENDING` older than 30 s → recovery asks `GET /internal/holds?bookingId=`. If the original hold request is still in flight (slow Postgres, GC pause), the lookup says "not found", Booking marks `HOLD_FAILED`, then the original request commits a hold. Seats are now held for 8 minutes by a dead booking.

**Fix:** recovery must not just *read*; it must *fence*. Call `POST /internal/holds/abort {bookingId}`, which inserts a tombstone row (`seat_holds` with status `ABORTED`, keyed by the unique `booking_id`). A late hold for that `bookingId` then hits the unique constraint and is rejected. Same pattern on Payment: recovery should "void by idempotency key" rather than infer failure from "not found". This is a great interview story in its own right.

### A5. `holdId` must be deterministic for the Lua retry to be idempotent
The Lua script is idempotent only "with the same `holdId`". If Inventory generates a fresh UUID per request, a retried hold from Booking will conflict with its own first attempt. Either derive `holdId` from `bookingId` (UUIDv5) or look up `seat_holds` by `booking_id` before stage 1 and return the existing result.

### A6. Phantom Redis holds when Inventory crashes between stage 1 and stage 2
If Inventory dies after the Lua `SET`s but before the Postgres commit, compensation (step 3) never runs. Those seats are `AVAILABLE` in Postgres but rejected by the Redis gate for the full 8-minute TTL. Correctness holds, but seats are falsely unavailable at the worst possible moment. Fix: set the Redis keys with a short provisional TTL (e.g. 15–30 s) in stage 1, and extend to the real TTL after the DB commit. Mention this in failure-modes.md.

### A7. Batched deltas break "consecutive version" gap detection
§11 says the client applies consecutive versions and resyncs on any gap, but also that deltas are coalesced every 100–250 ms. A coalesced message spans many versions, so as written every batch looks like a gap. Change the delta message to carry `fromVersion` and `toVersion`, and have the client accept a batch when `fromVersion == lastVersion + 1`. Also make sure the apply script does **not** bump `ver:{evt}` for duplicate/stale events, or you create gaps that no message fills.

### A8. Snapshot must be read atomically
`HGETALL seatmap` + `GET ver` must be one Lua call (or `MULTI`), otherwise a delta applied between the two reads produces a snapshot whose version lies.

### A9. Booking cannot price seats without a Catalog dependency
§8 says Booking "computes the price server-side from the catalog", but Booking has no sync call to Catalog and does not consume `catalog.events`. At 3,000 holds/sec, a sync Catalog call per booking is a needless hot-path dependency. Recommended: Inventory stores `price_tier_id` / `price_minor` per row in `seat_state` when it seeds an event, and the hold response returns the line items and total. Booking stores the snapshot. One fewer service in the hot path, and the price is fixed at publish time (which matches "seat map immutable once published").

### A10. Sale can open before Inventory has seeded the seats
`EventPublished` → Inventory fetches seats asynchronously. If `onSaleAt` arrives first, holds update 0 rows and every user gets a bogus `409`. Add an `InventoryReady {eventId, seatCount}` event, and only let the event go `ON_SALE` (and the waiting room start admitting) after it. Also define who flips `PUBLISHED → ON_SALE` at `onSaleAt` (a scheduler in Catalog emitting `SaleOpened`), since nothing currently owns it.

### A11. Admission token outlives the admitted slot
`admitted` entries expire on their own schedule, but the token is valid for 10–15 min. A user dropped from `admitted` (freeing capacity for the next person) can still book with their token, so actual concurrency exceeds `maxActive`. Make token `exp` equal to the admitted expiry, and **free the slot early** when the user's booking reaches a terminal state (Realtime already consumes `booking.events`).

---

## B. Inconsistencies between sections

| Where | Issue | Suggested fix |
|---|---|---|
| §6.4 terminal list vs. diagram | `EXPIRED` is terminal but has an outgoing transition | See A1 |
| §6.1 routing vs. §11/§18 | Gateway routes `/queue/**` and `/ws` but not `/api/v1/realtime/**` (snapshot, ws-ticket) | Add the route |
| §6.1 vs. §18 | `/api/v1/inventory/**` "public read" is routed, but no public Inventory endpoint exists | Remove it; snapshot comes from Realtime |
| §6.5 vs. §8 | Is `POST /internal/payments` synchronous (tok_timeout "delays the response") or async (result via `payment.events`)? | Say: Payment returns `202 PENDING` quickly; outcome arrives via event; `tok_timeout` makes the *request* hang past the client timeout while the charge commits. Both paths exist on purpose |
| §8 vs. §5 | Who checks `now >= onSaleAt`? Booking has no event data | Inventory checks a per-event `sale_open` flag it set on `SaleOpened` |
| §9 ordering note | "Rely on version numbers in payloads, which this design does" is true for seat events only; booking/hold/payment events have no versions | Say those rely on state-machine guards instead, or use one relay per service via advisory lock (simpler, recommended) |
| §16 invariant checker | "Every SOLD seat has a CONFIRMED booking" joins two service databases, and is briefly false between Inventory confirm and Booking's update | Note that the checker is an ops tool allowed to read all schemas, and runs after a quiesce period |

---

## C. Things worth adding

**Must-add (small, closes real holes)**
- **Per-user, per-event seat cap**, not just "one active booking". Today a user can buy 6, then 6 more, forever. Enforce at confirm in Inventory (count tickets for user+event) or at booking creation.
- **Sold-out flow.** When available seats hit 0, the waiting room should stop admitting and tell queued users "sold out, you may still get a seat if holds expire" (or close the queue). With 100k users and 5k seats this is the experience of 95% of your users, and it's currently undefined.
- **Event close.** What happens to active holds and the queue at `EventClosed`?
- **Second invariant, stated explicitly:** *"No user is charged without either receiving tickets or being refunded."* It's in the checker already; promote it next to the core invariant, because the saga exists to protect it.
- **Configurable timings for tests.** An 8-minute TTL makes expiry and late-payment tests slow. Make hold TTL, payment window, sweeper interval and admission expiry config values, and set them to seconds in tests.
- **Retention jobs** for `processed_events`, published `outbox` rows and `booking_transitions`, with the retention window longer than Kafka's max redelivery horizon.

**High-value for the portfolio**
- **A "simulate rush" button / script** that seeds an event, spawns N bot users and lets you watch the seat map go grey and Grafana spike. This is the 60-second demo that sells the project in an interview or a README GIF.
- **Load-test realism section.** A laptop cannot run 100k WebSocket clients or mint 100k logins through BCrypt. Plan for it: pre-mint test JWTs (test-only signing key), test 5–10k concurrent sockets, and state that you extrapolate. Saying this up front is more credible than claiming 100k.
- **Resync storm protection.** If a Realtime node restarts, thousands of clients hit `/snapshot` at once. Cache the serialized snapshot per `version` (or for ~200 ms) so it's built once, and add jitter to client reconnect (you have backoff already; make jitter explicit).
- **Seat map rendering note.** For large venues use canvas (or SVG with a single delta-applied layer), not one React component per seat; otherwise the frontend becomes your measured bottleneck.
- **Stable seat identifiers.** Define the canonical `seatId` format (e.g. `SEC-ROW-NUM`) shared by Catalog, Inventory, Realtime and the frontend.
- **Hosted demo.** A cheap VM running a reduced stack (or Redpanda instead of Kafka) that a recruiter can click is worth more than the Kubernetes manifests in the stretch list.

**Explicitly fine to leave out**
- Pact/contract tests, Avro, Debezium, Kubernetes, randomized fairness. They're correctly in stretch already.

---

## D. Scope risk and a suggested cut line

The full spec is realistically 4–6 months of evenings for one person, and the risk is ending with seven half-finished services. Your build order (§21) is good; add explicit "ship points" so every stage is a complete resume entry:

| Ship point | Contents | Resume-ready claim |
|---|---|---|
| **v0.1** (most important) | Build steps 1–2: Catalog, Inventory, Booking on Postgres; Redis Lua gate; headline concurrency test; invariant checker; Testcontainers in CI | "Zero oversell under 1,000 concurrent clients; Redis gate cut DB load by X%" |
| **v0.2** | Step 3: mock PSP, orchestrated saga, recovery job (with A4 fencing), late-payment policy | "Saga with compensation; survives payment timeouts and service crashes" |
| **v0.3** | Steps 4–5: outbox + Kafka, read model, WebSocket deltas, minimal React seat map | "Live seat map with versioned deltas and resync" |
| **v1.0** | Steps 6–8: waiting room, gateway, k6 + one bottleneck story, chaos runs, dashboards, README/ADRs | Full project |

Suggestions to reduce load without weakening the story:
- **Keep auth minimal** at first: login + JWT access tokens only; add refresh-token rotation in v1.0. It isn't what the project is about.
- **Delay the gateway** until v1.0; services can validate JWTs themselves before then.
- **Run one Postgres instance with a schema per service** (you already plan this); don't run separate containers.
- **Write the ADRs as you go**, one per decision already in the spec (Redis-as-gate, orchestration, outbox poller, late-payment policy, WebSocket vs SSE). They're the cheapest interview prep you'll do.

---

## E. Interview questions this review suggests you prepare for

- "Walk me through what happens if the recovery job and a slow hold request race." (A4)
- "Can a user end up with two confirmed bookings for the same event?" (A1)
- "Your deltas are batched; how does the client detect a gap?" (A7)
- "Where does the price come from, and what if the catalog is down at on-sale?" (A9)
- "What does a user who will never get a seat see?" (sold-out flow)
- "How did you generate 100k users on a laptop?" (load-test realism)
