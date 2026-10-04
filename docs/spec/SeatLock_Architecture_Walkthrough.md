# SeatLock v2: Architecture and Workflow Walkthrough

This is the plain-language companion to `SeatLock_Project_Specification_v2.md`. The spec is the reference; this document explains how the pieces fit together and what happens, step by step, when 100,000 people try to buy 5,000 seats. Section references (§) point to the spec.

---

## 1. The idea in one paragraph

Postgres is the judge, and everything else exists to keep the judge from being overwhelmed or to tell people what the judge decided. The **waiting room** limits how many people can even try to buy. **Redis** turns most losing attempts away in microseconds, before they reach Postgres. **Postgres** makes the final, transactional decision about who gets each seat, and a unique constraint on `tickets` makes double-selling physically impossible. A **saga** in the Booking service walks each purchase through hold → pay → confirm, and it can undo steps if something fails. **Kafka**, fed by a transactional outbox, carries the news to everyone who needs it (the live seat map, notifications, the booking state machine) without slowing the purchase down.

Two promises sit above everything:

- **I1:** a seat is never sold twice.
- **I2:** nobody is charged without either getting tickets or getting a refund.

---

## 2. The services and what each one owns

```
 Browser ──REST──▶ API Gateway ──▶ Booking ──▶ Inventory ──▶ (Redis gate, Postgres)
    │                  │              │
    │                  │              └──────▶ Payment (mock PSP)
    │                  ├──▶ User & Catalog
    └──WebSocket──────▶└──▶ Realtime (waiting room, live seat map, notifications)

 Every service writes its events to its own outbox table → relay → Kafka → consumers
```

| Service | Owns | Think of it as |
|---|---|---|
| **API Gateway** | Nothing persistent | The front door: checks your login, rate-limits you, routes you |
| **User & Catalog** | Users, venues, seat geometry, prices, event lifecycle | The box office's planning office: decides what is for sale and when |
| **Inventory** | Every seat's state, frozen prices, holds, tickets | The judge. The only place that decides who gets a seat |
| **Booking** | Bookings and their state machine | The purchase coordinator. Remembers where each purchase is and drives it to an end |
| **Payment** | Payments and refunds | A fake Stripe that is deliberately slow, flaky and sometimes lies by timing out |
| **Realtime** | Waiting room, live seat-map copy, WebSocket sessions | The stadium screens and the queue barrier |

All of them share one Postgres instance but each has its own schema and its own DB user, so no service can read another's tables.

---

## 3. Before the sale: setting up an event

```
Admin            Catalog                 Kafka                 Inventory                Realtime
  │ publish        │                       │                       │                       │
  │──────────────▶ │ freeze seats+prices   │                       │                       │
  │                │ status=PUBLISHED      │                       │                       │
  │                │── EventPublished ────▶│──────────────────────▶│ copy seats + prices   │
  │                │                       │                       │ (paged, idempotent)   │
  │                │◀──── InventoryReady ──│◀──────────────────────│ sale state=READY      │
  │                │ status=READY          │                       │                       │
  │        (onSaleAt arrives)              │                       │                       │
  │                │ status=ON_SALE        │                       │                       │
  │                │── SaleOpened ────────▶│──────────────────────▶│ sale state=OPEN       │
  │                │                       │──────────────────────────────────────────────▶│ queue OPEN
```

Why it matters: in v1, a sale could open before Inventory had copied the seats, and every buyer would have seen a fake "seat taken" error. Now a sale can only open once Inventory has confirmed it is ready (§5, §6.2).

Prices are copied into Inventory at this step and never change afterwards. That is why Booking never needs to call Catalog during the rush (§6.3).

---

## 4. The rush: one buyer's journey

### Step 1: the waiting room

Ravi opens the event page 10 minutes early. The browser loads the seat-map drawing (cached, never changes) and a **snapshot** of which seats are free, then opens a WebSocket.

He clicks "Join queue". A Lua script in Redis gives him a sequence number. If he opens five tabs, he still has one place in line.

Every second, the **admission controller** looks at three numbers: how many people are already inside the buying area, how many seats are still available, and how many are only held. It lets in just enough people from the front of the line: never more than Inventory can handle (`maxActive`), and never more than about two buyers per available seat (`admitFactor`). When Ravi's turn comes, he gets an `admitted` message and fetches an **admission token** that expires exactly when his slot does (§10).

Meanwhile, the person at position 60,000 sees an honest status:
- **OPEN:** "You're in line, about 4 minutes."
- **PAUSED_ALL_HELD:** "All seats are held right now. Some may come back in the next few minutes."
- **SOLD_OUT:** "Sold out." No more waiting for nothing.

### Step 2: picking seats and holding them

Ravi picks 2 seats and clicks "Reserve". The browser sends `POST /bookings` with an `Idempotency-Key` (so a double-click is harmless) and his admission token.

```
Browser        Booking                         Inventory                     Redis          Postgres
  │ POST /bookings │                              │                            │               │
  │──────────────▶ │ token valid? still admitted? │                            │               │
  │                │ ≤ 6 seats per event total?   │                            │               │
  │                │ insert PENDING booking ─────────────────────────────────────────────────▶ │ (booking schema)
  │                │   (fails if he already has an active booking → 409)       │               │
  │                │ POST /internal/holds ───────▶│ holdId = UUIDv5(bookingId) │               │
  │                │                              │ sale OPEN?                 │               │
  │                │                              │ Lua: any seat sold/held? ─▶│               │
  │                │                              │  no → set keys, TTL 30 s   │               │
  │                │                              │ one transaction: ───────────────────────▶  │
  │                │                              │  claim bookingId, lock seats in order,     │
  │                │                              │  AVAILABLE→HELD, write outbox SeatsHeld    │
  │                │                              │ promote Redis keys to 8 min ▶│             │
  │                │◀── HELD, prices, expiresAt ──│                            │               │
  │                │ booking → SEATS_HELD         │                            │               │
  │◀── 201 ────────│                              │                            │               │
```

What happens to the losers:
- **Most losers** are stopped by the Redis Lua script and never touch Postgres. That is the whole point of the gate.
- **A few** pass Redis (for example, during a Redis restart) and are stopped by the Postgres conditional update. Same `409`, just slower.
- **If both had a bug**, the `UNIQUE (event_id, seat_id)` on `tickets` would still stop a double sale at confirm time.

Two small details make this robust:
- The `holdId` is calculated from the `bookingId`, so a retry is recognised as "the same hold", not a competitor (§7).
- The Redis keys start with a 30-second lifetime and are only extended after Postgres commits. If Inventory crashes in between, the seats free themselves within 30 seconds instead of looking taken for 8 minutes (§6.3).

### Step 3: paying

Ravi enters a (fake) card and clicks Pay.

```
Browser       Booking                            Inventory          Payment
  │ POST /pay   │                                   │                  │
  │───────────▶ │ extend hold to now+2min ─────────▶│                  │
  │             │   (if already expired → EXPIRED, 409, NO CHARGE)     │
  │             │ SEATS_HELD → PAYMENT_PENDING      │                  │
  │             │ (optimistic lock; a concurrent cancel loses or wins cleanly)
  │             │ POST /internal/payments ─────────────────────────────▶│ PROCESSING
  │◀── 202 ─────│                                   │                  │
  │                                                                     │ ...works in background
```

The order matters: the hold is extended **before** any charge is requested, so Ravi can never pay for a hold that already expired.

### Step 4: payment result and confirmation

```
Payment ── PaymentSucceeded (Kafka) ──▶ Booking: PAYMENT_PENDING → PAYMENT_SUCCEEDED
Booking ── confirm ──▶ Inventory, one transaction:
            • hold still mine? (or seats still free if my hold lapsed)
            • user's total tickets for this event ≤ 6?
            • seats → SOLD, insert tickets (unique per seat), outbox SeatsSold
Booking: → CONFIRMED, outbox BookingConfirmed
Realtime: pushes "confirmed" to Ravi, frees his waiting-room slot for the next person,
          and every browser's seat map turns those seats "sold"
```

---

## 5. How the live seat map stays correct

```
Inventory outbox ─▶ Kafka (inventory.seat-events, keyed by event) ─▶ Realtime applier (one per event)
     Lua: update each seat only if its version is newer; bump the event version only if something changed
     publish each change on Redis pub/sub
                         │
          ┌──────────────┼──────────────┐
     Realtime node 1  node 2  ...  node N   (each buffers 150 ms, sends one combined delta)
          │
     Browser: lastVersion = 1040
        delta 1041..1043 → apply, lastVersion = 1043
        delta 1047..1050 → gap! fetch a fresh snapshot
```

Every delta says which versions it covers (`fromVersion`, `toVersion`), so batching no longer hides gaps. The snapshot is read in one atomic Redis call and cached for a second, so a thousand reconnecting browsers trigger one build, not a thousand (§11).

The seat map is only for display. Even if a browser shows a seat as free when it isn't, the hold request is checked on the server.

---

## 6. When things go wrong

These are the cases that make the project worth talking about. Each one has a test in §16.5.

**Ravi double-clicks "Reserve".** Same idempotency key, same booking returned. One hold.

**Ravi opens a second tab and tries different seats.** The database allows one active booking per user per event, so the second request gets `409 ACTIVE_BOOKING_EXISTS`.

**The hold request is very slow, and Booking's recovery job runs first.** In v1 the recovery job asked "does a hold exist?", heard "no", marked the booking failed, and then the slow request created a hold nobody owned. In v2 the recovery job calls `abort`. Abort writes a tombstone for that booking, so when the slow request finally arrives it bumps into the tombstone and is refused. The rule is to **block the late operation first, then decide the outcome** (§6.4).

**Booking crashes after marking "paying" but before calling Payment.** Recovery calls `void` on Payment. If no payment exists, a tombstone guarantees none ever will. The booking becomes `PAYMENT_FAILED` and the seats are released. No charge.

**The payment request times out (`tok_timeout`).** Booking doesn't know if Ravi was charged, so it does not guess. The booking stays `PAYMENT_PENDING` until the payment event arrives or the recovery job asks Payment directly.

**The payment is slow and Ravi's hold expires while it's processing.** The sweeper frees his seats. Booking notes "hold lapsed" but keeps the booking active, which also stops Ravi from starting a second booking. When the payment succeeds:
- if nobody took the seats, the sale still completes;
- if someone did, confirm returns `SEAT_CONFLICT`, the booking goes to `REFUND_PENDING`, and Ravi is refunded.

Either way, I1 and I2 hold, and Ravi can never end up with two confirmed bookings (that was a real hole in v1).

**Two people paid late for the same lapsed seat.** Postgres row locks make them take turns. The first gets the seat and the second is refunded.

**A payment success arrives for a booking that can't use it.** By design this shouldn't happen. If it does, Booking records it as an orphan payment, refunds it, and raises an alert. The invariant checker makes sure every orphan ends up refunded.

**Redis dies mid-sale.** Inventory switches to Postgres-only holds: slower but still correct. The rate limiter lets traffic through and raises an alert. The waiting room pauses new admissions (it doesn't let everyone in). Browsers poll for snapshots. When Redis is back, Inventory rebuilds the hold and sold keys from Postgres before using the gate again.

**Kafka dies.** Events pile up safely in each service's outbox table and publish when Kafka returns. Purchases keep working, because holds, payments and confirms are direct calls, and the recovery job can ask Payment for outcomes directly.

**A Kafka message is delivered twice.** Consumers either check a `processed_events` table in the same transaction, or rely on the state machine and seat versions ignoring anything stale.

---

## 7. The booking state machine at a glance

```
PENDING ─▶ SEATS_HELD ─▶ PAYMENT_PENDING ─▶ PAYMENT_SUCCEEDED ─▶ CONFIRMED
   │          │  │  │            │                  │
   ▼          │  │  ▼            ▼                  ▼
HOLD_FAILED   │  │ EXPIRED   PAYMENT_FAILED    REFUND_PENDING ─▶ REFUNDED
              │  ▼
              │ EXPIRED (hold ran out before paying)
              ▼
          CANCELLED
```

- Six terminal states, and none of them can ever change again.
- "Active" (blocks a second booking): `PENDING`, `SEATS_HELD`, `PAYMENT_PENDING`, `PAYMENT_SUCCEEDED`.
- `REFUND_PENDING` is not active, so a user who lost a late-payment race can try again right away.

---

## 8. How you prove it works

1. **Concurrency test:** 1,000 clients fight for 100 seats. Exactly 100 sold, every loser gets a clean `409`.
2. **Race tests:** one test per scenario in section 6 above, using test-only "pause here" hooks.
3. **Invariant checker:** after every test, load run and chaos run it checks I1, I2, one active booking per user, at most 6 seats per user, nothing stuck, and Redis agreeing with Postgres.
4. **Chaos scripts:** kill Redis, Kafka, Booking or Inventory mid-sale, then run the checker.
5. **k6 load tests:** contention, browsing plus resync storms, full purchase, queue surge, WebSocket fan-out. Find one real bottleneck and document the fix.
6. **Rush simulator + demo page:** one command reproduces an on-sale while you watch seats go grey and Grafana spike. This is your interview demo.

---

## 9. What to build first

| Ship point | You have | You can say |
|---|---|---|
| v0.1 | Catalog + Inventory + basic Booking, Postgres arbiter, Redis gate, concurrency test, invariant checker, CI | "Zero oversell under contention, with measured gains from the Redis gate" |
| v0.2 | Payment mock, full saga, fencing recovery, late-payment policy | "A saga that survives timeouts, crashes and late payments" |
| v0.3 | Outbox, Kafka, live seat map, minimal React UI | "Live seat map with gap-free versioned updates" |
| v1.0 | Waiting room, gateway, full UI, observability, chaos, load tests, demo | The full project |

Each row works on its own. If time runs out at v0.2, you still have a strong project.
