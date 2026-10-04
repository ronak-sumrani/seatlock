---
name: ship-check
description: Verify the current step is complete and ready to commit
disable-model-invocation: true
---

# Ship check

Verify the current step is complete and ready to commit.

1. Run `./mvnw spotless:apply`.
2. Run `./mvnw verify`.
3. If anything fails, find and fix the root cause. Never skip, disable or weaken a test
   to make it pass.
4. If the change touches holds, confirm, payments or the saga, also run the concurrency
   test and the invariant checker (once they exist) and paste their summary output.
5. Check that every new behaviour in the diff has a test that would fail without it.
6. Report:
   - What changed (files and behaviour).
   - Test results (paste the summary lines).
   - Any deviation from docs/spec/SeatLock_Project_Specification_v2.md, with the section.
