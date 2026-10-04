---
name: adr
description: Draft an Architecture Decision Record
disable-model-invocation: true
argument-hint: <number> <title>
---

# Draft an ADR

Arguments: `$ARGUMENTS` (for example `001 Postgres as arbiter, Redis as gate`).
The first token is the ADR number; the rest is the title.

1. Read the relevant section of docs/spec/SeatLock_Project_Specification_v2.md and the
   current code that implements (or will implement) this decision.
2. Write `docs/adr/ADR-<number>.md` with:

   ```markdown
   # ADR-<number>: <title>

   ## Context
   ## Decision
   ## Alternatives considered
   (each alternative, and why it was rejected)
   ## Consequences
   (good and bad, including trade-offs we accept)
   ## How it is tested
   (the specific tests that prove the decision holds; say so if they don't exist yet)
   ```

3. Write in plain language I could say out loud in an interview: short sentences, no
   buzzwords, concrete numbers and failure scenarios where they help.
4. If the spec and the code disagree, point it out instead of picking one silently.
