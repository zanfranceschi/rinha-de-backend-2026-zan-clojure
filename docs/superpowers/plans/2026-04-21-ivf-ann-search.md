# IVF ANN Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the brute-force KNN classifier with an IVF (Inverted File) ANN index so the production 100k-vector reference set fits inside the 0.4 CPU / 80 MB per-instance budget at ≥ 97% fraud_score recall vs exact KNN.

**Architecture:** Offline `lein convert-references` runs k-means (`nlist=256`) over 100k labeled vectors, buckets each point into its nearest centroid's cell, and writes `resources/references.bin` in a new IVF format (header + centroids block + per-cell blocks with byte labels and double vectors). At startup the app reads this binary into a primitive-array-backed index `{:centroids double[256][14], :cells Cell[256]}`. Per-request classify ranks centroids (`nprobe=8` nearest), brute-forces top-5 KNN within the union of those cells using the existing `PriorityQueue`/`worst-first` machinery, and votes for `fraud_score`.

**Tech Stack:** Clojure 1.12.4, Leiningen, `java.util.PriorityQueue`, primitive `double[]` arrays, `DataInput/OutputStream`, `clojure.test`. Full spec at [docs/superpowers/specs/2026-04-21-ivf-ann-search-design.md](../specs/2026-04-21-ivf-ann-search-design.md).

---

## Task 1: Rewrite `knn.clj` for IVF (TDD)

**Files:**
- Modify: `src/rinha_de_backend_2026/knn.clj` (full rewrite of public API)
- Modify: `test/rinha_de_backend_2026/knn_test.clj` (update to IVF API)

This task introduces the new runtime API. The builder accepts a pre-clustered
`{:centroids :cells}` shape (tests construct this manually; production reads
it from disk in Task 3). Classify adds the centroid-rank step before the
top-k scan.

- [ ] **Step 1: Write the failing test for the IVF classify API**

Replace the entire contents of `test/rinha_de_backend_2026/knn_test.clj` with:

```clojure
(ns rinha-de-backend-2026.knn-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026.knn :as knn]))

;; Two manually-specified cells so tests are fully deterministic — no k-means
;; at test time. Cell 0 = fraud cluster, Cell 1 = legit cluster.

(def test-index
  (knn/build-index
   {:centroids [[0.9 0.1 0.0]
                [0.0 0.1 0.9]]
    :cells     [{:vectors [[1.0 0.0 0.0]
                           [0.9 0.1 0.0]
                           [0.8 0.2 0.0]]
                 :labels  ["fraud" "fraud" "fraud"]}
                {:vectors [[0.0 0.0 1.0]
                           [0.0 0.1 0.9]
                           [0.0 0.2 0.8]]
                 :labels  ["legit" "legit" "legit"]}]}))

;; nprobe=2 visits both cells → all 6 points reachable, so top-5 is exact.

(deftest classify-fraud
  (testing "Vector near fraud cluster returns fraud classification"
    (let [result (knn/classify [0.95 0.05 0.0] test-index 5 0.6 2)]
      (is (false? (:approved result)))
      (is (>= (:fraud_score result) 0.6)))))

(deftest classify-legit
  (testing "Vector near legit cluster returns legit classification"
    (let [result (knn/classify [0.0 0.05 0.95] test-index 5 0.6 2)]
      (is (true? (:approved result)))
      (is (< (:fraud_score result) 0.6)))))

(deftest classify-fraud-score-is-ratio
  (testing "fraud_score is fraud_count / k"
    (let [result (knn/classify [0.95 0.05 0.0] test-index 5 0.6 2)]
      (is (number? (:fraud_score result)))
      (is (<= 0.0 (:fraud_score result) 1.0)))))

(deftest classify-nprobe-one-picks-correct-cell
  (testing "nprobe=1 with fraud query reaches only fraud cell and approves=false"
    (let [result (knn/classify [0.95 0.05 0.0] test-index 3 0.6 1)]
      (is (false? (:approved result)))
      (is (>= (:fraud_score result) 0.6)))))

(deftest classify-response-shape
  (testing "Response contains approved and fraud_score"
    (let [result (knn/classify [0.95 0.05 0.0] test-index 5 0.6 2)]
      (is (contains? result :approved))
      (is (contains? result :fraud_score))
      (is (boolean? (:approved result))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `lein test :only rinha-de-backend-2026.knn-test`

Expected: compilation/resolution error or failures referencing `knn/build-index`
(does not exist yet) and/or `knn/classify` arity mismatch (arity-5 not
implemented yet).

- [ ] **Step 3: Rewrite `src/rinha_de_backend_2026/knn.clj`**

Replace the entire contents with:

```clojure
(ns rinha-de-backend-2026.knn
  (:import [java.util PriorityQueue Comparator]))

;; Compile-time ceiling for points per cell. Heap entries pack
;; (cellIdx * MAX-CELL-SIZE + pointIdx) into the [1] slot of a double[2],
;; mirroring the pre-IVF packing trick. 65536 comfortably fits n/nlist at
;; n=100k/nlist=256 and stays well under 2^53.
(def ^:const MAX-CELL-SIZE 65536)

(defn- squared-distance
  ^double [^doubles x ^doubles y]
  (let [n (alength x)]
    (loop [i 0 sum 0.0]
      (if (< i n)
        (let [d (- (aget x i) (aget y i))]
          (recur (inc i) (+ sum (* d d))))
        sum))))

(def ^:private ^Comparator worst-first
  (reify Comparator
    (compare [_ a b]
      (Double/compare (aget ^doubles b 0) (aget ^doubles a 0)))))

(defn- ->doubles ^doubles [v]
  (if (instance? (Class/forName "[D") v)
    v
    (double-array v)))

(defn- ->matrix ^"[[D" [vectors]
  (into-array (Class/forName "[D") (map ->doubles vectors)))

(defn- ->byte-labels ^bytes [labels]
  (let [n   (count labels)
        out (byte-array n)]
    (dotimes [i n]
      (aset-byte out i (if (= "fraud" (nth labels i)) 1 0)))
    out))

(defn build-index
  "Build an IVF search index from pre-clustered data.
   Input shape:
     {:centroids [[d1..dD] * nlist]
      :cells     [{:vectors [[...]...], :labels [\"fraud\"|\"legit\"...]} * nlist]}
   Output is opaque to callers. Cells may be empty (size 0) and cells.length
   must equal centroids.length. Any cell exceeding MAX-CELL-SIZE throws."
  [{:keys [centroids cells]}]
  (let [nlist        (count centroids)
        _            (assert (= nlist (count cells))
                             "centroids/cells length mismatch")
        centroids-m  (->matrix centroids)
        cell-objs    (object-array nlist)]
    (dotimes [i nlist]
      (let [{:keys [vectors labels]} (nth cells i)
            sz (count vectors)]
        (when (> sz MAX-CELL-SIZE)
          (throw (ex-info "Cell exceeds MAX-CELL-SIZE"
                          {:cell i :size sz :max MAX-CELL-SIZE})))
        (assert (= sz (count labels)) "vectors/labels length mismatch")
        (aset cell-objs i
              {:matrix (->matrix vectors)
               :labels (->byte-labels labels)})))
    {:centroids centroids-m
     :cells     cell-objs}))

(defn- top-nprobe-cell-indices
  "Return an int-array of the nprobe nearest centroid indices to query."
  ^ints [^doubles query ^"[[D" centroids ^long nprobe]
  (let [n    (alength centroids)
        np   (min nprobe n)
        heap (PriorityQueue. (int np) worst-first)]
    (dotimes [i n]
      (let [d (squared-distance query (aget centroids i))]
        (if (< (.size heap) np)
          (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i))))
          (let [^doubles worst (.peek heap)]
            (when (< d (aget worst 0))
              (.poll heap)
              (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i)))))))))
    (let [out (int-array (.size heap))
          it  (.iterator heap)]
      (loop [i 0]
        (if (.hasNext it)
          (let [^doubles e (.next it)]
            (aset out i (int (aget e 1)))
            (recur (inc i)))
          out)))))

(defn- scan-cell!
  "Push points from the given cell into the top-k heap. pack-base is
   cellIdx * MAX-CELL-SIZE; the second heap slot stores pack-base + pointIdx."
  [^doubles query ^"[[D" matrix ^long pack-base ^PriorityQueue heap ^long k]
  (let [n (alength matrix)]
    (dotimes [p n]
      (let [d (squared-distance query (aget matrix p))]
        (if (< (.size heap) k)
          (.offer heap (doto (double-array 2)
                         (aset 0 d)
                         (aset 1 (double (+ pack-base p)))))
          (let [^doubles worst (.peek heap)]
            (when (< d (aget worst 0))
              (.poll heap)
              (.offer heap (doto (double-array 2)
                             (aset 0 d)
                             (aset 1 (double (+ pack-base p))))))))))))

(defn classify
  "Classify a vector using IVF KNN.
   - search-index: result of build-index
   - k:            top-k neighbors for the vote
   - threshold:    fraud_score >= threshold => not approved
   - nprobe:       number of nearest cells to scan

   Returns {:approved bool :fraud_score double}."
  [vector search-index k threshold nprobe]
  (let [query         (->doubles vector)
        ^"[[D" centroids (:centroids search-index)
        ^objects cells (:cells search-index)
        top-cells     (top-nprobe-cell-indices query centroids (long nprobe))
        heap          (PriorityQueue. (int k) worst-first)]
    (dotimes [i (alength top-cells)]
      (let [ci       (aget top-cells i)
            cell     (aget cells ci)
            matrix   ^"[[D" (:matrix cell)
            pack-base (* (long ci) (long MAX-CELL-SIZE))]
        (scan-cell! query matrix pack-base heap (long k))))
    (let [fraud-count (reduce (fn [acc ^doubles entry]
                                (let [packed (long (aget entry 1))
                                      ci     (quot packed MAX-CELL-SIZE)
                                      pi     (rem  packed MAX-CELL-SIZE)
                                      ^bytes labels (:labels (aget cells (int ci)))]
                                  (if (== 1 (aget labels (int pi)))
                                    (inc acc)
                                    acc)))
                              0
                              heap)
          fraud-score (double (/ fraud-count k))]
      {:approved    (< fraud-score threshold)
       :fraud_score fraud-score})))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `lein test :only rinha-de-backend-2026.knn-test`

Expected: `Ran 5 tests containing N assertions. 0 failures, 0 errors.`

- [ ] **Step 5: Commit**

```bash
git add src/rinha_de_backend_2026/knn.clj test/rinha_de_backend_2026/knn_test.clj
git commit -m "feat(knn): IVF classify with centroid rank + cell scan

Replace brute-force linear scan with an IVF-style top-k: rank the nprobe
nearest centroids, then brute-force within those cells only. Labels move
to byte arrays per cell so the hot loop only touches primitives."
```

---

## Task 2: Add IVF build + writer to `convert-references`

**Files:**
- Modify: `src/rinha_de_backend_2026/convert_references.clj`

We add (a) a function that turns raw labeled refs + `nlist` into a
`{:centroids :cells}` shape via the existing k-means, and (b) a binary writer
for the new IVF format. The `-main` entry point with no args switches from
writing the old flat format to the new IVF format. The medoid compression
path (with `max-size` arg) stays untouched per the spec.

- [ ] **Step 1: Add `build-ivf` and `write-ivf-bin` to `convert_references.clj`**

Insert the following after the existing `extract-medoids` / `compress-class`
section and before `write-bin`:

```clojure
;; ---------------------------------------------------------------------------
;; IVF build + writer
;; ---------------------------------------------------------------------------

(defn- build-ivf
  "Run k-means with k=nlist over all refs and bucket points into cells.
   Returns {:centroids [[...]] :cells [{:vectors [[...]] :labels [...]}]}
   in the shape consumed by knn/build-index."
  [refs nlist iters]
  (let [mtx          (to-matrix refs)
        {:keys [centroids assignments]} (kmeans mtx nlist iters)
        nrefs        (count refs)
        cell-vecs    (object-array nlist)
        cell-labs    (object-array nlist)]
    (dotimes [i nlist]
      (aset cell-vecs i (transient []))
      (aset cell-labs i (transient [])))
    (dotimes [p nrefs]
      (let [c (aget ^ints assignments p)
            r (nth refs p)]
        (aset cell-vecs c (conj! (aget cell-vecs c) (:vector r)))
        (aset cell-labs c (conj! (aget cell-labs c) (:label  r)))))
    {:centroids (mapv #(vec (aget ^"[[D" centroids %)) (range nlist))
     :cells     (mapv (fn [i]
                        {:vectors (persistent! (aget cell-vecs i))
                         :labels  (persistent! (aget cell-labs i))})
                      (range nlist))}))

(defn- write-ivf-bin
  "Write an IVF-formatted binary. See spec section 'Binary format'."
  [{:keys [centroids cells]} output-file]
  (let [nlist (count centroids)
        dim   (count (first centroids))
        total (reduce + 0 (map (comp count :vectors) cells))]
    (with-open [dos (DataOutputStream. (BufferedOutputStream. (FileOutputStream. output-file)))]
      (.writeInt dos total)
      (.writeInt dos dim)
      (.writeInt dos nlist)
      ;; centroids
      (doseq [c centroids]
        (doseq [v c]
          (.writeDouble dos (double v))))
      ;; cells
      (doseq [{:keys [vectors labels]} cells]
        (.writeInt dos (count vectors))
        (dotimes [i (count vectors)]
          (.writeByte dos (if (= "fraud" (nth labels i)) 1 0))
          (doseq [v (nth vectors i)]
            (.writeDouble dos (double v))))))
    (println (format "Wrote IVF: total=%d dim=%d nlist=%d -> %s (%.1f MB)"
                     total dim nlist
                     (.getPath output-file)
                     (/ (.length output-file) 1048576.0)))))
```

- [ ] **Step 2: Switch the no-args `-main` path to produce IVF output**

Add a named constant near the top of the file (just after `kmeans-iters`):

```clojure
(def ^:private default-nlist 256)
```

Replace the current `-main` body (the `if` that picks between `write-bin` and
the compress path) with:

```clojure
(defn -main
  "Convert references.json -> references.bin.
   With no args: build IVF index with default-nlist cells and write IVF format.
   With a max-size arg: compress via per-class k-means medoids (legacy flat
   format, preserved for the non-IVF workflow)."
  [& args]
  (let [input    (io/resource "references.json")
        output   (io/file "resources/references.bin")
        data     (json/read-str (slurp input) :key-fn keyword)
        total    (count data)
        max-size (some-> (first args) Integer/parseInt)]
    (if (nil? max-size)
      (do
        (println (format "Building IVF index over %d refs, nlist=%d..."
                         total default-nlist))
        (let [ivf (build-ivf data default-nlist kmeans-iters)]
          (write-ivf-bin ivf output)))
      (if (>= max-size total)
        (write-bin data output)
        (let [by-class (group-by :label data)
              fraud-in (get by-class "fraud" [])
              legit-in (get by-class "legit" [])
              f-count  (count fraud-in)
              l-count  (count legit-in)
              fraud-k  (max 1 (Math/round (double (* max-size (/ f-count total)))))
              legit-k  (max 0 (- max-size fraud-k))]
          (println (format "Input:  %d total (fraud %d / legit %d)"
                           total f-count l-count))
          (println (format "Target: %d total (fraud %d / legit %d)"
                           max-size fraud-k legit-k))
          (let [fraud-out (compress-class fraud-in fraud-k kmeans-iters "fraud")
                legit-out (compress-class legit-in legit-k kmeans-iters "legit")
                final     (concat
                           (map (fn [v] {:vector (vec v) :label "fraud"}) fraud-out)
                           (map (fn [v] {:vector (vec v) :label "legit"}) legit-out))]
            (write-bin final output)))))))
```

- [ ] **Step 3: Sanity-compile**

Run: `lein check`

Expected: no errors, no warnings beyond what the repo already shows.

- [ ] **Step 4: Commit**

```bash
git add src/rinha_de_backend_2026/convert_references.clj
git commit -m "feat(convert): IVF build pipeline and binary writer

Default no-args invocation now runs k-means with nlist=256 over the full
ref set and writes references.bin in the IVF format. The medoid max-size
path is preserved for the non-IVF workflow."
```

---

## Task 3: IVF loader + nprobe wiring in `fraud_score.clj`

**Files:**
- Modify: `src/rinha_de_backend_2026/fraud_score.clj`

Replace the current flat-binary reader with an IVF reader that yields the
`{:centroids :cells}` shape consumed by `knn/build-index`. Add `nlist` and
`nprobe` constants. Update the warmup loop to use the new `classify` arity.

- [ ] **Step 1: Replace the reader and wire up new knobs**

Replace the entire contents of `src/rinha_de_backend_2026/fraud_score.clj`
with:

```clojure
(ns rinha-de-backend-2026.fraud-score
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [rinha-de-backend-2026.knn :as knn]
   [rinha-de-backend-2026.normalization :as norm])
  (:import [java.io DataInputStream BufferedInputStream]))

;; ---------------------------------------------------------------------------
;; Load resource files at startup
;; ---------------------------------------------------------------------------

(def normalization-config
  (json/read-str (slurp (io/resource "normalization.json")) :key-fn keyword))

(def mcc-risk
  (json/read-str (slurp (io/resource "mcc_risk.json"))))

(defn- read-ivf-bin
  "Read an IVF-formatted binary resource and return the {:centroids :cells}
   shape expected by knn/build-index. Labels are materialized as strings
   here; build-index converts them to byte arrays."
  [resource-name]
  (with-open [dis (DataInputStream. (BufferedInputStream. (.openStream (io/resource resource-name))))]
    (let [_total (.readInt dis)
          dim    (.readInt dis)
          nlist  (.readInt dis)
          centroids (vec (for [_ (range nlist)]
                           (vec (for [_ (range dim)]
                                  (.readDouble dis)))))
          cells (vec (for [_ (range nlist)]
                       (let [sz (.readInt dis)]
                         (loop [i 0
                                vs (transient [])
                                ls (transient [])]
                           (if (< i sz)
                             (let [lbl (if (== 1 (.readUnsignedByte dis)) "fraud" "legit")
                                   v   (double-array dim)]
                               (dotimes [j dim]
                                 (aset v j (.readDouble dis)))
                               (recur (inc i)
                                      (conj! vs v)
                                      (conj! ls lbl)))
                             {:vectors (persistent! vs)
                              :labels  (persistent! ls)})))))]
      {:centroids centroids :cells cells})))

(def search-index
  (knn/build-index (read-ivf-bin "references.bin")))

;; ---------------------------------------------------------------------------
;; IVF + KNN parameters
;; ---------------------------------------------------------------------------

(def ^:const k         5)
(def ^:const threshold 0.6)
(def ^:const nlist     256)  ; informational only; actual nlist comes from the file
(def ^:const nprobe    8)

;; ---------------------------------------------------------------------------
;; Score
;; ---------------------------------------------------------------------------

(defn score
  "Score a transaction for fraud using IVF KNN detection.
   Returns {:approved bool :fraud_score float}."
  [request]
  (let [vector (norm/normalize request normalization-config mcc-risk)]
    (knn/classify vector search-index k threshold nprobe)))

;; ---------------------------------------------------------------------------
;; JIT warm-up. Runs synthetic classifications so HotSpot promotes the IVF
;; hot paths (centroid rank, cell scan, PriorityQueue ops) before the server
;; accepts real traffic. Blocks server startup via the ns-load chain.
;; ---------------------------------------------------------------------------

(let [;; pick any non-empty cell to read the dimension from
      dim (let [cells (:cells search-index)
                n     (alength cells)
                probe (loop [i 0]
                        (if (< i n)
                          (let [m (:matrix (aget cells i))]
                            (if (pos? (alength ^"[[D" m))
                              (aget ^"[[D" m 0)
                              (recur (inc i))))
                          (throw (ex-info "all cells empty" {}))))]
            (alength ^doubles probe))]
  (dotimes [_ 2000]
    (knn/classify (vec (repeatedly dim #(rand)))
                  search-index k threshold nprobe)))
```

- [ ] **Step 2: Run the existing fraud-score tests**

Do NOT run yet — `references.bin` is still in the old flat format, so the
reader will throw. Skip to Task 4 which regenerates the binary; only then
verify tests.

Commit this change now so Task 4's regen step is isolated:

```bash
git add src/rinha_de_backend_2026/fraud_score.clj
git commit -m "feat(fraud-score): IVF binary reader and nprobe wiring

Reader produces the {:centroids :cells} shape consumed by knn/build-index.
Warmup loop unchanged in count; inner call now goes through IVF classify."
```

---

## Task 4: Regenerate `references.bin` and verify existing tests

**Files:**
- Replace: `resources/references.bin` (regenerated)

- [ ] **Step 1: Run the IVF converter**

Run: `lein convert-references`

Expected output (approximate; numbers will vary):

```
Building IVF index over 100000 refs, nlist=256...
  iter 1/20
  iter 2/20
  ...
  iter 20/20
Wrote IVF: total=100000 dim=14 nlist=256 -> resources/references.bin (11.0 MB)
```

Wall time: 30–90 seconds on a developer laptop. This is the offline build
referenced in the design doc.

- [ ] **Step 2: Confirm file size is in the expected range**

Run: `ls -l resources/references.bin`

Expected: size between 10 MB and 12 MB (formula: 12 + 256·14·8 + Σ(4 + sz·(1 + 14·8)) ≈ 11.0 MB at n=100k).

- [ ] **Step 3: Run the full test suite**

Run: `lein test`

Expected: all four existing test namespaces (`endpoints-test`,
`fraud-score-test`, `knn-test`, `normalization-test`) pass with 0 failures
and 0 errors. The `fraud-score-test` suite is the integration gate — if
`authorize-legit-transaction` flips to non-approved or
`authorize-fraud-transaction` flips to approved, the cell assignment for
those specific vectors is off and nprobe=8 isn't reaching the right cluster.
In that case the first diagnostic step is rerunning with nprobe=16 by
temporarily editing the `nprobe` const; if that fixes it, the default
nprobe in fraud_score.clj should be raised.

- [ ] **Step 4: Commit the regenerated binary**

```bash
git add resources/references.bin
git commit -m "chore(resources): regenerate references.bin in IVF format

Produced by 'lein convert-references' at nlist=256 over the full 100k
reference set. Existing fraud-score and normalization tests still pass."
```

---

## Task 5: Recall test (validation gate)

**Files:**
- Create: `test/rinha_de_backend_2026/recall_test.clj`

The recall test is the deliverable that proves IVF preserves classifier
behavior. It loads all 100k references, builds two classifiers — the IVF
production one and a self-contained brute-force oracle defined inside the
test — and runs the same 1000 synthetic queries through both, asserting
fraud_score agreement ≥ 97%.

- [ ] **Step 1: Write the recall test**

Create `test/rinha_de_backend_2026/recall_test.clj` with:

```clojure
(ns rinha-de-backend-2026.recall-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rinha-de-backend-2026.fraud-score :as fraud-score]
   [rinha-de-backend-2026.knn :as knn])
  (:import [java.util PriorityQueue Comparator Random]))

;; ---------------------------------------------------------------------------
;; Self-contained brute-force oracle. Intentionally a separate implementation
;; from knn/classify so this test is a real apples-to-apples comparison.
;; ---------------------------------------------------------------------------

(defn- sq-dist
  ^double [^doubles x ^doubles y]
  (let [n (alength x)]
    (loop [i 0 sum 0.0]
      (if (< i n)
        (let [d (- (aget x i) (aget y i))]
          (recur (inc i) (+ sum (* d d))))
        sum))))

(def ^:private ^Comparator worst-first
  (reify Comparator
    (compare [_ a b]
      (Double/compare (aget ^doubles b 0) (aget ^doubles a 0)))))

(defn- flatten-index
  "Materialize the IVF search-index into flat [^\"[[D\" matrix, ^bytes labels]
   for brute-force scanning."
  [search-index]
  (let [^objects cells (:cells search-index)
        n              (alength cells)
        total          (loop [i 0 s 0]
                         (if (< i n)
                           (recur (inc i)
                                  (+ s (alength ^"[[D" (:matrix (aget cells i)))))
                           s))
        flat-m         (make-array (Class/forName "[D") total)
        flat-l         (byte-array total)]
    (loop [ci 0 off 0]
      (if (< ci n)
        (let [cell    (aget cells ci)
              ^"[[D" m (:matrix cell)
              ^bytes l (:labels cell)
              sz      (alength m)]
          (dotimes [p sz]
            (aset ^objects flat-m (+ off p) (aget m p))
            (aset-byte flat-l (+ off p) (aget l p)))
          (recur (inc ci) (+ off sz)))
        [flat-m flat-l]))))

(defn- brute-classify
  [vector [^"[[D" flat-m ^bytes flat-l] k threshold]
  (let [query (double-array vector)
        n     (alength flat-m)
        heap  (PriorityQueue. (int k) worst-first)]
    (dotimes [i n]
      (let [d (sq-dist query (aget flat-m i))]
        (if (< (.size heap) k)
          (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i))))
          (let [^doubles worst (.peek heap)]
            (when (< d (aget worst 0))
              (.poll heap)
              (.offer heap (doto (double-array 2) (aset 0 d) (aset 1 (double i)))))))))
    (let [fraud-count (reduce (fn [acc ^doubles entry]
                                (let [idx (int (aget entry 1))]
                                  (if (== 1 (aget flat-l idx))
                                    (inc acc) acc)))
                              0
                              heap)
          fraud-score (double (/ fraud-count k))]
      {:approved    (< fraud-score threshold)
       :fraud_score fraud-score})))

;; ---------------------------------------------------------------------------
;; Recall gate
;; ---------------------------------------------------------------------------

(def ^:private recall-target 0.97)
(def ^:private num-queries   1000)
(def ^:private dim           14)
(def ^:private rng-seed      42)

(defn- synthetic-queries [^long n ^long dim ^long seed]
  (let [rng (Random. seed)]
    (vec (for [_ (range n)]
           (vec (for [_ (range dim)]
                  (.nextDouble rng)))))))

(deftest ivf-recall-matches-brute-force
  (testing "IVF fraud_score agrees with brute-force on ≥ 97% of queries"
    (let [idx          fraud-score/search-index
          oracle-data  (flatten-index idx)
          queries      (synthetic-queries num-queries dim rng-seed)
          agreements   (reduce (fn [acc q]
                                 (let [ivf-r   (knn/classify q idx
                                                             fraud-score/k
                                                             fraud-score/threshold
                                                             fraud-score/nprobe)
                                       brute-r (brute-classify q oracle-data
                                                               fraud-score/k
                                                               fraud-score/threshold)]
                                   (if (== (:fraud_score ivf-r)
                                           (:fraud_score brute-r))
                                     (inc acc) acc)))
                               0
                               queries)
          recall       (/ (double agreements) num-queries)]
      (println (format "IVF recall vs brute-force: %d/%d = %.3f"
                       agreements num-queries recall))
      (is (>= recall recall-target)
          (format "Recall %.3f below target %.3f — consider raising nprobe."
                  recall recall-target)))))
```

- [ ] **Step 2: Run the recall test**

Run: `lein test :only rinha-de-backend-2026.recall-test`

Expected: line like `IVF recall vs brute-force: 973/1000 = 0.973` followed
by test pass. First run also pays the JIT warmup embedded in
`fraud-score`'s ns load (~1s) plus the 1000-query eval (a few seconds
since each query runs both IVF and brute-force over 100k). Total wall time
around 10–30 seconds on a developer laptop.

If recall comes in below 0.97, DO NOT lower `recall-target`. First raise
`nprobe` in [fraud_score.clj](../../src/rinha_de_backend_2026/fraud_score.clj)
to 12 and rerun. If 12 doesn't clear, try 16. If 16 still fails, the k-means
run produced unusually imbalanced cells — rerun `lein convert-references`
(k-means is randomly initialized; a different run may cluster better) and
re-measure.

- [ ] **Step 3: Commit**

```bash
git add test/rinha_de_backend_2026/recall_test.clj
git commit -m "test: IVF recall gate (≥97% fraud_score agreement)

Deterministic synthetic-query oracle with a self-contained brute-force
classifier. Fails loudly if a future retune regresses recall below the
design target."
```

---

## Task 6: End-to-end verification

**Files:**
- None modified; this task is pure verification.

- [ ] **Step 1: Full test suite**

Run: `lein test`

Expected: 0 failures, 0 errors across `endpoints-test`, `fraud-score-test`,
`knn-test`, `normalization-test`, `recall-test`.

- [ ] **Step 2: Build the container**

Run: `cd containerization && ./build-and-publish.sh && cd ..`

Expected: `rinha-de-backend-2026-zan-clojure:latest` image built without
errors. If the image fails the Dockerfile `COPY` of the uberjar, check that
`references.bin` was included in the uberjar build (it lives under
`resources/` and should be packaged automatically by Leiningen).

- [ ] **Step 3: Boot the stack locally**

Run: `docker compose -f containerization/docker-compose.yml up -d`

Wait ~10s for JIT warmup to finish inside each api container, then:

Run: `curl -s http://localhost:9999/ready`

Expected: `{"status":"ok"}`

Run the legit sample from the README:

```bash
curl -s -X POST http://localhost:9999/fraud-score \
  -H "Content-Type: application/json" \
  -d '{"id":"tx-001","transaction":{"amount":50.0,"installments":1,"requested_at":"2026-03-16T14:00:00Z"},"customer":{"avg_amount":60.0,"tx_count_24h":2,"known_merchants":["MERC-001"]},"merchant":{"id":"MERC-001","mcc":"5411","avg_amount":45.0},"terminal":{"is_online":false,"card_present":true,"km_from_home":2.0},"last_transaction":{"timestamp":"2026-03-16T12:00:00Z","km_from_current":1.5}}'
```

Expected: `{"approved":true,"fraud_score":0.0}` (or similarly low;
must-haves: `approved:true` and `fraud_score < 0.6`).

Run the fraud sample:

```bash
curl -s -X POST http://localhost:9999/fraud-score \
  -H "Content-Type: application/json" \
  -d '{"id":"tx-fraud","transaction":{"amount":9500.0,"installments":12,"requested_at":"2026-03-14T03:00:00Z"},"customer":{"avg_amount":200.0,"tx_count_24h":15,"known_merchants":["MERC-001"]},"merchant":{"id":"MERC-999","mcc":"7995","avg_amount":8000.0},"terminal":{"is_online":true,"card_present":false,"km_from_home":500.0},"last_transaction":{"timestamp":"2026-03-14T02:55:00Z","km_from_current":300.0}}'
```

Expected: `{"approved":false,"fraud_score":...}` with `fraud_score >= 0.6`.

- [ ] **Step 4: Run the k6 load test**

Run: `./run-test.sh`

Expected: test completes without HTTP errors; p95 and p99 latencies visibly
lower than the pre-IVF baseline run (the delta is the whole point of this
change). Record the k6 summary output into the task notes — the numbers
tell the deployment story.

- [ ] **Step 5: Tear the stack down**

Run: `docker compose -f containerization/docker-compose.yml down`

- [ ] **Step 6: No commit**

This task has no file changes. If any verification step revealed a defect,
return to the relevant earlier task, fix it, and rerun from Step 1.

---

## Post-implementation checklist

- [ ] `lein test` clean (all 5 namespaces).
- [ ] `lein convert-references` deterministically re-produces a working
      `references.bin` from `references.json`.
- [ ] Recall test reports ≥ 0.97.
- [ ] Live docker-compose stack passes the README curl samples.
- [ ] k6 load-test latency is lower than the pre-IVF baseline.
- [ ] No changes to `normalization.clj`, `endpoints.clj`, resources
      other than `references.bin`, README rebuild instructions, or the
      medoid-compression path in `convert-references.clj`.
