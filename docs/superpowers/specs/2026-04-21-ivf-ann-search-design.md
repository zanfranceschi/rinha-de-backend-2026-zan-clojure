# IVF ANN Search for Fraud KNN

**Status:** Approved
**Date:** 2026-04-21
**Author:** zanfranceschi

## Motivation

The production reference set contains 100,000 labeled 14-dim vectors, but the
current classifier in [`knn.clj`](../../../src/rinha_de_backend_2026/knn.clj)
brute-forces KNN via a linear scan. At n=100k, each query costs ~1.4M FLOPs plus
100k heap ops per request, and the production budget per API instance is 0.4
CPU and 80 MB heap. Brute force does not fit.

This design replaces the brute-force scan with an **IVF (Inverted File)** index
using k-means Voronoi cells. We accept a small, bounded recall loss in exchange
for ~30× fewer distance computations per query. The design is pinned to an
end-to-end recall target of **≥ 97%** — measured as `fraud_score` agreement
with exact KNN on a 1000-query sample.

## Non-goals

- Residual coding, PQ, or any compressed representation of vectors — at 14
  dims, vectors are already small enough.
- Reranking pass with exact distance — raw Euclidean is used throughout.
- Online index updates — the reference set is static at startup.
- Replacing the existing offline medoid-compression path in
  [`convert-references`](../../../src/rinha_de_backend_2026/convert_references.clj).
  That mode stays as-is for other workflows; the IVF path is additive.

## Parameters

| Name | Value | Where |
| --- | --- | --- |
| `nlist` | 256 | compile-time constant, `fraud_score.clj` |
| `nprobe` | 8 | compile-time constant, `fraud_score.clj` |
| `k` | 5 | unchanged |
| `threshold` | 0.6 | unchanged |
| k-means iters (offline) | 20 | unchanged, reuses existing `kmeans-iters` |

`nlist ≈ √n` is the standard IVF sizing. `nprobe = 8` (3.1% of cells) is chosen
conservatively to absorb cell-boundary queries at the target recall. Both are
tunable via the recall test; production defaults must pass the recall gate.

## Architecture

Three unchanged layers — offline build, startup load, per-request query — each
extended to carry the IVF structure.

### Offline build (`lein convert-references`)

1. Read `resources/references.json` → seq of `{:vector [..] :label "fraud"|"legit"}`.
2. Convert to a primitive `double[n][14]` matrix (existing `to-matrix`).
3. Run k-means with `k=nlist=256`, 20 iters (existing `kmeans`), producing
   `{:centroids, :assignments}`.
4. Bucket points by their assignment into `nlist` cell collections. Preserve
   each point's label.
5. Write to `resources/references.bin` in the IVF format below.

Build cost is one-time and offline. The existing medoid-compression path
(triggered by a `max-size` CLI arg) is preserved unchanged.

### Binary format (`references.bin`)

All integers 32-bit big-endian via `DataOutputStream`; all vector components
64-bit doubles.

```
int   total          ; total vector count across all cells, sanity check
int   dim            ; 14
int   nlist          ; 256
;; --- centroids ---
for i in 0..nlist-1:
  dim doubles        ; centroid vector
;; --- cells (written in centroid-index order) ---
for i in 0..nlist-1:
  int  cell-size
  for j in 0..cell-size-1:
    byte    label    ; 1=fraud, 0=legit
    dim doubles      ; vector
```

Size at n=100k, d=14, nlist=256:

- Header: 12 B
- Centroids: 256 × 14 × 8 ≈ 28.7 KB
- Data: 100k × (1 + 14·8) ≈ 11.0 MB
- **Total on disk and on heap:** ~11.0 MB, well within the 80 MB budget.

No magic bytes or version field — the format is owned by a single producer
(`convert-references`) and a single consumer (`fraud-score`). A format change
means a rebuild; the README already documents that step.

### Startup load (`fraud_score.clj`)

Replace the current `references` def with an `index` def of shape:

```clojure
{:centroids ^"[[D" double-array-of-arrays   ; double[nlist][dim]
 :cells     ^objects array-of-Cell}         ; Cell[nlist]
```

Where each `Cell` is a plain Clojure map (mirroring the existing
`build-search` return shape — no record type needed):

```clojure
{:matrix ^"[[D" double-array-of-arrays      ; double[count][dim]
 :labels ^bytes byte-array}                 ; byte[count], 1=fraud
```

`count` may be zero (k-means can collapse a cluster). The scan tolerates empty
cells — it simply iterates zero points.

Labels are bytes rather than the existing `mapv :label refs` of strings. This
removes a `nth` into a persistent vec and a string `=` compare from any hot
loop that touches labels.

JIT warmup in `fraud_score.clj` stays: 2000 synthetic `classify` calls before
the HTTP server accepts traffic.

### Query (`knn/classify`)

```
classify(query, index, k, threshold, nprobe):
  ; 1. Rank centroids
  ;    Compute squared distance to all nlist centroids, keep nprobe nearest
  ;    via a size-nprobe max-heap (same worst-first comparator pattern used today).
  top-cells = nprobe-nearest-centroid-indices(query, centroids, nprobe)

  ; 2. Brute-force top-k across the union of top-cells.
  heap = PriorityQueue(k, worst-first)
  for ci in top-cells:
    cell = cells[ci]
    for p in 0..cell.matrix.length - 1:
      d = squared-distance(query, cell.matrix[p])
      maintain top-k heap; each entry packs (d, cellIdx, pointIdx)

  ; 3. Vote
  fraud       = count heap entries where cells[cellIdx].labels[pointIdx] == 1
  fraud_score = fraud / k
  approved    = fraud_score < threshold
```

Inner-loop specifics that matter at d=14:

- The existing `squared-distance` in
  [`knn.clj:4-11`](../../../src/rinha_de_backend_2026/knn.clj#L4-L11) is kept
  unchanged — primitive `^doubles` + `aget`, already JIT-vectorized.
- Heap entries remain a `double[2]`: `[0]` is the distance (the key for the
  max-heap ordering, matching
  [`knn.clj:28-31`](../../../src/rinha_de_backend_2026/knn.clj#L28-L31)); `[1]`
  encodes the global point index as `(cellIdx * MAX_CELL_SIZE + pointIdx)`.
  `nlist · MAX_CELL_SIZE` stays well below 2^53, so the double-encoding is
  lossless. `MAX_CELL_SIZE` is a compile-time ceiling of `65536`, set once at
  the top of `knn.clj`. Builds that would exceed this (not possible at
  n=100k/nlist=256) must fail loudly at load time rather than silently alias
  indices.
- Label lookup happens k=5 times per query, off the hot path, after the heap
  settles. The packed index is decoded back to `(cellIdx, pointIdx)`.
- The centroid-ranking step uses the same `squared-distance` and the same
  max-heap pattern, just with `nprobe=8` entries. No new primitives needed.

## Expected performance

Back-of-envelope at the default parameters:

- Centroid rank: 256 × 14 ≈ 3.6k FLOPs + ~256 heap touches.
- Cell scan (average, uniform cells): 8/256 × 100k × 14 ≈ 44k FLOPs.
- Total: ~48k FLOPs/query vs ~1.4M for brute force → **~30× speedup**.

Imbalanced cells can inflate the cell-scan worst case. At nlist=256 / n=100k,
per-cluster sizes typically fall in ~50–1500 even on skewed inputs; `nprobe=8`
worst case is ~12k points — still 8× better than brute. This is the
characteristic IVF failure mode, and the mitigation (bumping `nprobe`) is the
first tuning lever.

## Testing

### Recall gate (new, blocking)

`test/rinha_de_backend_2026/recall_test.clj`:

1. Load the full 100k reference set twice — once as a brute-force index (the
   exact oracle), once as the IVF index.
2. Sample 1000 query vectors. Either synthetic (uniform in `[0,1]^14` matching
   the normalized feature space) or a held-out slice of the reference data;
   synthetic is simpler and adequate for cell-boundary coverage.
3. For each query, run both classifiers with the same `k` and `threshold`.
4. Assert `fraud_score` agreement on ≥ 97% of queries.
5. Fail the test if recall falls below 97%. This blocks silent recall
   regressions from retuning `nlist` or `nprobe`.

`fraud_score` agreement (rather than full neighbor-set equivalence) is the
right metric here because it is the actual user-facing output and is strictly
weaker than requiring identical neighbor sets. Two IVF queries that pick
different-but-equivalent neighbors will still pass.

### Existing tests

- Unit tests under `test/rinha_de_backend_2026/` continue to pass with the new
  `classify` signature.
- End-to-end k6 load test (`./run-test.sh`) verifies real-world latency
  improvement and stability under load.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| k-means produces imbalanced cells, hurting worst-case latency | Bump `nprobe` or `nlist`; recall test guards the quality side. |
| Adversarial cell-boundary queries miss true neighbors | `nprobe=8` is deliberately conservative; first lever if recall dips is `nprobe=12` or `16` before changing structure. |
| Binary format drift between build and load | Producer and consumer are both in-tree; a format bump is a single coordinated change plus a rebuild documented in README. |
| Startup time balloons | Index build stays offline; startup only reads and allocates arrays. |

## Out-of-scope cleanup explicitly included

- Switching labels from `mapv` of strings to `byte[]` per cell. This is
  consequential in the hot path and inseparable from the IVF cell layout, so
  it rides with this change rather than as a separate refactor.

## Files touched

- [`src/rinha_de_backend_2026/knn.clj`](../../../src/rinha_de_backend_2026/knn.clj)
  — replace `build-search` and `classify` with IVF variants; keep
  `squared-distance` and the `worst-first` comparator.
- [`src/rinha_de_backend_2026/fraud_score.clj`](../../../src/rinha_de_backend_2026/fraud_score.clj)
  — replace the `references` loader with an IVF-format reader; add `nlist` and
  `nprobe` constants; adjust the warmup loop to call the new `classify`.
- [`src/rinha_de_backend_2026/convert_references.clj`](../../../src/rinha_de_backend_2026/convert_references.clj)
  — add an IVF build + writer; keep `kmeans`, `to-matrix`, the existing medoid
  path, and `analyze-duplicates` untouched.
- `test/rinha_de_backend_2026/recall_test.clj` — new file, recall gate.
- [`resources/references.bin`](../../../resources/references.bin) —
  regenerated in the new format as part of landing the change.
- [`README.md`](../../../README.md) — mention the new binary format under
  "Converting references" if the rebuild instructions change.
