# Compactor Simulation Test Improvement Plan

Gaps and rework opportunities identified by comparing the allium specs (`compaction.allium`, `trie-cat.allium`, `db.allium`) against `core/src/test/kotlin/xtdb/compactor/CompactorSimulationTest.kt`.

GC is out of scope — it has its own simulation test.

## Phase 1: Fix existing test weaknesses (no new tests)

Quick wins — reworking what's already there to be more precise and correct.

### 1.1 Rename `addL0s` → `seedTries`

The method adds tries at any level but is named `addL0s`.
Rename to `seedTries` (or `addTries`) across all call sites.
Pure tidy.

### 1.2 Fix `blocksPerWeek` default inconsistency

`CompactorDriverConfig` defaults to `140`, `@WithCompactorDriverConfig` defaults to `14`.
Align them — probably both should be `14` since the annotation is the more commonly used path.

### 1.3 Fix weak assertion in `biggerMultiSystemCompactorRun`

Change:
```kotlin
trieKeys.map { it.size }.distinct().size == 1  // same count
```
to:
```kotlin
trieKeys.map { it.toSet() }.distinct().size == 1  // same set
```

### 1.4 Switch assertions from `listAllTrieKeys` to state-aware queries

`listAllTrieKeys` returns all trie keys regardless of state (live, nascent, and garbage).
Per `trie-cat.allium`, different consumers see different subsets:

- **Scan operator** uses `currentTries(table)` — **live tries only** (nascent excluded).
- **Compactor** sees live + nascent (to avoid re-doing work).

Replace `listAllTrieKeys` with the appropriate query for each assertion:
- Use `currentTries` (or equivalent live-only query) for "what would a query see" checks.
- Use `listLiveAndNascentTrieKeys` for "what does the compactor see" checks.
- Add `garbageTries` assertions where input tries should have been retired.

This is the highest-value change in this phase — it turns every existing test into a trie-lifecycle test for free.

---

## Phase 2: Add trie lifecycle tests

Test the nascent → live → garbage transitions specified in `trie-cat.allium`.

### 2.1 Assert garbage state of input tries after compaction

After L0 → L1 compaction: assert L0 inputs appear in `garbageTries`.
After L1C → L2C compaction: assert L1C inputs appear in `garbageTries`.
Can be added as extra assertions to existing tests (`singleL0Compaction`, `l1cToL2cCompaction`, etc.).

### 2.2 Test nascent-until-group-completes for L1C → L2C

Per `trie-cat.allium`: "A nascent trie is visible to the compactor (so it doesn't re-do the work) but is not yet used for queries."
Per `compaction.allium`: all 4 L2C partition outputs are nascent until the last one is uploaded, then all become live simultaneously.

Write a test (or extend `l1cToL2cCompaction`) that inspects catalog state mid-compaction to verify:
- Partially-completed job groups have nascent outputs.
- `currentTries` does **not** include nascent outputs (queries don't see partial compaction).
- `listLiveAndNascentTrieKeys` **does** include nascent outputs (compactor sees them).

This may require a custom `Driver` wrapper that pauses after each `executeJob` to allow mid-flight inspection.

### 2.3 Test intermediate state validity

At any point during compaction, the scan operator's view (`currentTries` — live tries only) must be consistent.
Add a test that hooks into the compactor loop (via a wrapper Driver or a catalog observer) and asserts at each step that `currentTries` returns a valid set.

"Valid" means: for every IID path, the set of live tries covers all data without gaps.
Nascent tries must not interfere with this — they should be invisible to queries.

---

## Phase 3: Historical side coverage

The historical compaction path is almost entirely untested.

### 3.1 Dedicated L1H → L2H test

Seed the catalog with L1H files in specific weekly recency partitions.
Run compaction.
Assert:
- L2H files are created in the correct recency partitions.
- Trigger condition: 4 L1H files in same partition, or size > 100MB with fewer files.
- Input L1H files become garbage.

### 3.2 Test weekly recency bucketing correctness

Per `trie-cat.allium`, `TrieKey.recency` is `null` for current (∞) and a `LocalDate` for historical (week ending Monday 0000Z).

Write a test using `BOTH` or `HISTORICAL` splitting that verifies events land in the correct weekly partition.
Assert on the recency portion of historical trie keys (e.g. `l01-r20200106-b00`) matching expected Monday boundaries.

### 3.3 LnH → L(n+1)H for n ≥ 2

Seed enough L2H files to trigger L3H compaction.
Verify output keys and partition structure match the general-case rules from the compaction spec.

### 3.4 Add historical assertions to `biggerMultiSystemCompactorRun`

Since it already uses `BOTH`, add assertions on historical key structure — verify `l01-r*` and `l02-r*` keys exist and have well-formed recency partitions.

---

## Phase 4: Job selection unit tests

Separate job selection logic from end-to-end compaction to test trigger conditions precisely.

### 4.1 Test `JobCalculator.availableJobs` in isolation

Create a test class that directly calls `availableJobs` against a seeded `TrieCatalog` and asserts on the returned job list.
Test cases:
- Single L0 → produces one L0→L1 job
- 4 full L1C files → produces L1C→L2C job
- 3 full L1C files → produces nothing (below threshold)
- 4 L1H files in same recency partition → produces L1H→L2H job
- Mixed levels → correct prioritisation

### 4.2 Property test: deterministic job selection

Given the same catalog state, two independent `JobCalculator` instances must return identical job lists.
Generate random catalog states and assert equality.
This directly tests the coordination-free invariant from `compaction.allium`.

### 4.3 Test size-threshold triggering

Specifically test that L1H → L2H triggers at size > 100MB even with fewer than 4 files.
Seed a partial L2H (50MB) + 2 L1H files (30MB each) and verify the job is produced.

### 4.4 Test partial L1C merge at L0 → L1

Per `compaction.allium`: L0 → L1 takes "the L0 file + a partial L1C file (if one exists, i.e. < 100MB)" as input.
Verify that the job calculator produces a job with inputs = `[L0, existing-partial-L1C]` when a partial L1C exists alongside a new L0.

---

## Phase 5: Robustness and edge cases

### 5.1 Failure path test

Wrap the mock driver to throw on specific jobs.
Verify:
- The compactor doesn't crash.
- Other jobs still complete.
- The failed job's input tries remain live (not garbage).
- Re-running compaction retries the failed job.

### 5.2 Multi-table with heterogeneous compaction levels

Create a scenario where tables are at different stages:
- Table A: has L0s only (needs L0→L1)
- Table B: has L1Cs ready for L2C
- Table C: already has L2Cs, needs L3C

Run concurrent compaction and verify each table reaches the correct state independently.

### 5.3 Scale up `numberOfSystems` to 3–5

The coordination-free design should tolerate more than 2 nodes.
Add a test variant with 3+ systems sharing the same flow, asserting catalog convergence.

### 5.4 Stale `TriesAdded` message filtering

Per `db.allium`, `ProcessTriesAddedMessage` requires `storage_version == current_storage_version` and `storage_epoch == current_storage_epoch`.
Test that tries-added messages with a wrong version or epoch are silently discarded and don't pollute the catalog.

### 5.5 Back-pressure behaviour (if implemented)

The compaction spec has an open question about back-pressure.
If/when implemented, add a test that floods the compactor with more jobs than it can handle concurrently and verifies it doesn't OOM or drop jobs.

---

## Suggested priority order

| Priority | Item | Effort | Value |
|----------|------|--------|-------|
| 1 | 1.4 — State-aware assertions | Medium | High — every test becomes a lifecycle test |
| 2 | 1.3 — Fix weak multi-system assertion | Tiny | High — likely a real bug |
| 3 | 4.1 — Job selection unit tests | Medium | High — tests the core algorithm directly |
| 4 | 2.1 — Assert garbage state | Small | Medium — validates clean replacement |
| 5 | 3.1 — L1H → L2H test | Medium | High — entire pathway untested |
| 6 | 4.2 — Deterministic selection property test | Medium | High — tests key design invariant |
| 7 | 1.1 — Rename `addL0s` | Tiny | Low — pure tidy |
| 8 | 1.2 — Fix `blocksPerWeek` default | Tiny | Low — consistency |
| 9 | 3.2 — Weekly bucketing test | Small | Medium |
| 10 | 2.2 — Nascent-until-group-completes | Large | Medium — needs mid-flight inspection |
| 11 | 5.1 — Failure paths | Medium | Medium |
| 12 | 2.3 — Intermediate state validity | Large | High — but hard to implement well |
| 13 | 5.4 — Stale message filtering | Small | Low — db.allium invariant |
| 14 | 5.2 — Heterogeneous tables | Small | Low |
| 15 | 5.3 — 3+ systems | Tiny | Low |
| 16 | 3.3 — L2H→L3H | Medium | Low |
