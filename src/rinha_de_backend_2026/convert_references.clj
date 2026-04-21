(ns rinha-de-backend-2026.convert-references
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.io DataOutputStream BufferedOutputStream FileOutputStream]))

(def ^:private kmeans-iters 20)

(def ^:private default-nlist 256)

;; ---------------------------------------------------------------------------
;; Primitive-array helpers
;; ---------------------------------------------------------------------------

(defn- sq-dist
  ^double [^doubles a ^doubles b ^long dim]
  (loop [i 0 s 0.0]
    (if (< i dim)
      (let [d (- (aget a i) (aget b i))]
        (recur (inc i) (+ s (* d d))))
      s)))

(defn- closest-index
  ^long [^doubles point ^"[[D" centroids ^long dim]
  (let [k (alength centroids)]
    (loop [i      1
           best   0
           best-d (sq-dist point (aget centroids 0) dim)]
      (if (< i k)
        (let [d (sq-dist point (aget centroids i) dim)]
          (if (< d best-d)
            (recur (inc i) i d)
            (recur (inc i) best best-d)))
        best))))

(defn- to-matrix
  "Convert a seq of {:vector [..]} into a [[D primitive matrix."
  ^"[[D" [entries]
  (let [n   (count entries)
        dim (count (:vector (first entries)))
        m   (make-array Double/TYPE n dim)]
    (loop [idx 0 xs (seq entries)]
      (when xs
        (let [v            (:vector (first xs))
              ^doubles row (aget ^"[[D" m idx)]
          (dotimes [j dim]
            (aset row j (double (nth v j)))))
        (recur (inc idx) (next xs))))
    m))

;; ---------------------------------------------------------------------------
;; K-means + medoid extraction
;; ---------------------------------------------------------------------------

(defn- kmeans
  "Run k-means on points. Returns {:centroids [[D :assignments int[]}."
  [^"[[D" points k iters]
  (let [n           (alength points)
        dim         (alength ^doubles (aget points 0))
        init        (->> (range n) shuffle (take k) vec)
        centroids   (into-array (Class/forName "[D")
                                (map #(aclone ^doubles (aget points %)) init))
        assignments (int-array n)]
    (dotimes [iter iters]
      (let [sums   (make-array Double/TYPE k dim)
            counts (int-array k)]
        (dotimes [p n]
          (let [c                (closest-index (aget points p) centroids dim)
                ^doubles sum-row (aget ^"[[D" sums c)
                ^doubles point   (aget points p)]
            (aset assignments p c)
            (aset counts c (inc (aget counts c)))
            (dotimes [j dim]
              (aset sum-row j (+ (aget sum-row j) (aget point j))))))
        (dotimes [ci k]
          (let [cnt (aget counts ci)]
            (when (> cnt 0)
              (let [^doubles sum-row (aget ^"[[D" sums ci)
                    ^doubles cen     (aget centroids ci)
                    d                (double cnt)]
                (dotimes [j dim]
                  (aset cen j (/ (aget sum-row j) d))))))))
      (println (format "  iter %d/%d" (inc iter) iters)))
    ;; Final reassignment so assignments match final centroids
    (dotimes [p n]
      (aset assignments p (closest-index (aget points p) centroids dim)))
    {:centroids centroids :assignments assignments}))

(defn- extract-medoids
  "For each cluster c, pick the real point whose squared distance to the
   centroid is smallest. Returns a seq of ^doubles vectors."
  [^"[[D" points ^"[[D" centroids ^ints assignments]
  (let [n        (alength points)
        k        (alength centroids)
        dim      (alength ^doubles (aget points 0))
        best-idx (int-array k -1)
        best-d   (double-array k Double/POSITIVE_INFINITY)]
    (dotimes [p n]
      (let [c (aget assignments p)
            d (sq-dist (aget points p) (aget centroids c) dim)]
        (when (< d (aget best-d c))
          (aset best-d c d)
          (aset best-idx c p))))
    (for [ci (range k)
          :let [idx (aget best-idx ci)]
          :when (>= idx 0)]
      (aclone ^doubles (aget points idx)))))

(defn- compress-class
  "Cluster entries of a single class down to target-k medoids. If the class has
   fewer points than target-k, pass through unchanged."
  [entries target-k iters label]
  (if (<= (count entries) target-k)
    (do
      (println (format "Class %s: %d <= %d, passing through"
                       label (count entries) target-k))
      (mapv #(double-array (:vector %)) entries))
    (do
      (println (format "Clustering %d %s points -> %d medoids..."
                       (count entries) label target-k))
      (let [mtx (to-matrix entries)
            {:keys [centroids assignments]} (kmeans mtx target-k iters)]
        (vec (extract-medoids mtx centroids assignments))))))

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

;; ---------------------------------------------------------------------------
;; Binary writer
;; ---------------------------------------------------------------------------

(defn- write-bin
  [entries output-file]
  (let [n   (count entries)
        dim (count (:vector (first entries)))]
    (with-open [dos (DataOutputStream. (BufferedOutputStream. (FileOutputStream. output-file)))]
      (.writeInt dos n)
      (.writeInt dos dim)
      (doseq [entry entries]
        (.writeByte dos (if (= "fraud" (:label entry)) 1 0))
        (doseq [v (:vector entry)]
          (.writeDouble dos (double v)))))
    (println (format "Wrote %d entries (%dd) to %s (%.0f KB)"
                     n dim (.getPath output-file) (/ (.length output-file) 1024.0)))))

;; ---------------------------------------------------------------------------
;; Entry points
;; ---------------------------------------------------------------------------

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

(defn analyze-duplicates
  "Report exact-duplicate statistics over references.json.
   Keys on the full vector (ignoring label); surfaces conflicting-label groups
   separately since identical vectors with different labels are data-quality noise."
  [& _args]
  (let [input            (io/resource "references.json")
        data             (json/read-str (slurp input) :key-fn keyword)
        total            (count data)
        groups           (group-by :vector data)
        unique-vecs      (count groups)
        dup-entries      (- total unique-vecs)
        conflict-groups  (filter (fn [[_ entries]]
                                   (and (> (count entries) 1)
                                        (> (count (distinct (map :label entries))) 1)))
                                 groups)
        n-conflict-grp   (count conflict-groups)
        n-conflict-entry (reduce + 0 (map (comp count second) conflict-groups))
        by-class         (frequencies (map :label data))]
    (println (format "Total entries:          %d" total))
    (println (format "Unique vectors:         %d" unique-vecs))
    (println (format "Duplicate entries:      %d (%.1f%% of total)"
                     dup-entries (* 100.0 (/ dup-entries (double total)))))
    (println (format "Conflicting groups:     %d vectors with mixed labels"
                     n-conflict-grp))
    (println (format "Conflicting entries:    %d (in those groups)"
                     n-conflict-entry))
    (println)
    (println "Per-class counts:")
    (doseq [[label cnt] (sort-by first by-class)]
      (println (format "  %-6s %6d (%.1f%%)"
                       label cnt (* 100.0 (/ cnt (double total))))))))
