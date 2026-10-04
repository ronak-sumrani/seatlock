/**
 * Infrastructure helpers shared by SeatLock services: event envelope, outbox relay, idempotent-consumer helper,
 * tracing and logging helpers (spec §17).
 *
 * <p>Never put domain entities here. Each service owns its own model and data; a shared entity would couple
 * services through a library the same way a shared table couples them through the database.
 */
package com.seatlock.common;
