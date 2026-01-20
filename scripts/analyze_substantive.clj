#!/usr/bin/env clojure
;; Run substantive-only entity analysis on the large 10-K sample
;; Usage: clj -M scripts/analyze_substantive.clj

(require '[scr.entity :as e])
(require '[clojure.java.io :as io])

(def sample-dir "resources/filings_large_sample")
(def output-dir "resources/contexts_substantive")

(println "Starting SUBSTANTIVE-ONLY analysis of large 10-K sample...")
(println)

;; Get all files
(def files (->> (file-seq (io/file sample-dir))
                (filter #(.isFile %))
                (filter #(.endsWith (.getName %) ".txt"))
                (map #(.getPath %))
                sort
                vec))

(println "Found" (count files) "filings to analyze")
(println)

;; Run the analysis with substantive-only filtering
(def result (e/run-analysis
             :files files
             :sec-filers-only true
             :substantive-only true  ;; NEW: Only substantive mentions
             :parallel true
             :show-common false
             :show-bidirectional true
             :show-errors true))

;; Print summary stats
(println)
(println "========================================")
(println "SUBSTANTIVE ANALYSIS COMPLETE")
(println "========================================")
(println "Filings processed:" (:successful (:stats result)) "/" (:total (:stats result)))
(println "Failed:" (:failed (:stats result)))
(println "Substantive entity mentions found:" (reduce + (map :entity-count (:filings result))))
(println "Cross-company relationships:" (count (:common-entities result)))
(println "Bidirectional relationships:" (count (:bidirectional-relationships result)))
(println)

;; Show relationship type distribution
(println "=== Relationship Type Distribution ===")
(def rel-dist (->> (:filings result)
                   (mapcat :entities)
                   (mapcat :relationships)
                   frequencies
                   (sort-by val >)))
(doseq [[rel cnt] rel-dist]
  (println (format "  %-25s %d" (name rel) cnt)))
(println)

;; Export contexts
(println "Exporting contexts to" output-dir "...")
(.mkdirs (io/file output-dir))
(def export-result (e/export-all-contexts result :output-dir output-dir))
(println "Done!")
(println)
(println "Results saved to:" output-dir)
(println "Index file:" (:index-path export-result))

;; Save result for later analysis
(spit "resources/substantive_analysis.edn" (pr-str (dissoc result :filings)))
(println "Summary data saved to: resources/substantive_analysis.edn")
