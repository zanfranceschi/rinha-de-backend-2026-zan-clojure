(ns rinha-de-backend-2006-exemplo.authorization-requests-generation
  (:require [clojure.data.json :as json])
  (:import [org.locationtech.jts.geom Coordinate GeometryFactory PrecisionModel]
           [org.locationtech.jts.geom.util AffineTransformation]
           [org.locationtech.jts.triangulate VoronoiDiagramBuilder]
           [org.locationtech.jts.shape.random RandomPointsBuilder]))

(def factory (GeometryFactory. (PrecisionModel.) 4326))

(defn- scale-polygon [poly scale-factor]
  (let [centroid (.getCentroid poly)
        trans (AffineTransformation/scaleInstance scale-factor scale-factor (.getX centroid) (.getY centroid))]
    (.transform trans poly)))

(defn- poly->feature [poly]
  {:type "Feature"
   :properties {}
   :geometry {:type "Polygon"
              :coordinates [(mapv (fn [c] [(.x c) (.y c)])
                                  (.getCoordinates (.getExteriorRing poly)))]}})

(defn generate-islands-in-poly
  "Generates islands constrained within a specific JTS Polygon."
  [n boundary-poly keep-ratio shrink-factor]
  (let [;; 1. Generate random points strictly INSIDE the boundary polygon
        rp-builder (doto (RandomPointsBuilder. factory)
                     (.setExtent boundary-poly)
                     (.setNumPoints n))
        seeds (.getCoordinates (.getGeometry rp-builder))

        ;; 2. Build Voronoi using the boundary's envelope for the initial grid
        builder (doto (VoronoiDiagramBuilder.)
                  (.setSites (into [] seeds))
                  (.setClipEnvelope (.getEnvelopeInternal boundary-poly)))

        diagram (.getDiagram builder factory)]

    {:type "FeatureCollection"
     :features (->> (range (.getNumGeometries diagram))
                    (map #(.getGeometryN diagram %))
                    ;; 3. Intersect with the CUSTOM boundary polygon (the cookie cutter)
                    (map #(.intersection % boundary-poly))
                    (filter (fn [_] (< (rand) keep-ratio)))
                    (map #(scale-polygon % shrink-factor))
                    (filter #(not (.isEmpty %)))
                    (mapv poly->feature))}))

(defn- points->poly
  "Converts a nested vector [[lon lat] [lon lat] ...] into a JTS Polygon."
  [points]
  (let [;; 1. Ensure the ring is closed (last point == first point)
        closed-points (if (= (first points) (last points))
                        points
                        (conj (vec points) (first points)))
        ;; 2. Map to JTS Coordinate objects
        jts-coords (mapv (fn [[lon lat]] (Coordinate. lon lat)) closed-points)]

    ;; 3. Create the polygon using a Java Array
    (.createPolygon factory (into-array Coordinate jts-coords))))

;; --- Example Usage with a simple triangle boundary ---

(def brazil-boundary
  [[-69.8 -11.0]  ; Acre/Amazonas border area
   [-67.8  2.1]   ; Northern tip (Roraima)
   [-51.4  4.4]   ; Amapá coast
   [-34.8 -7.2]   ; Easternmost point (Paraíba)
   [-38.5 -13.0]  ; Bahia coast
   [-48.6 -28.3]  ; Southern coast (Santa Catarina)
   [-53.4 -33.7]  ; Southern tip (Rio Grande do Sul)
   [-57.6 -25.3]  ; Paraguay border area
   [-60.4 -13.2]  ; Mato Grosso/Bolivia border
   [-73.9 -10.9]])

(def sao-paulo-boundary
  [[-46.68 -23.38]  ;; Northern tip (Perus/Jaraguá)
   [-46.55 -23.45]  ;; North-East (Tremembé)
   [-46.38 -23.49]  ;; East (Itaim Paulista)
   [-46.40 -23.63]  ;; South-East (São Mateus)
   [-46.52 -23.70]  ;; Near ABC region
   [-46.62 -23.85]  ;; Heading South (Parelheiros)
   [-46.65 -23.97]  ;; Southernmost tip (Marsilac)
   [-46.75 -23.90]  ;; Climbing back up (Grajaú)
   [-46.83 -23.68]  ;; South-West (Capão Redondo)
   [-46.75 -23.55]  ;; West (Butantã)
   [-46.78 -23.45]  ;; North-West (Pirituba)
   [-46.68 -23.38]])

(let [;; Your friendly input format
      my-area sao-paulo-boundary

      ;; Convert to JTS Object
      boundary-poly (points->poly my-area)

      ;; Generate the islands
      islands (generate-islands-in-poly 1000 boundary-poly 0.6 0.2)]

  (println (json/write-str islands)))