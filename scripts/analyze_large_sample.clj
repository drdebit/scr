#!/usr/bin/env clojure
;; Run entity analysis on the large 10-K sample
;; Usage: clj -M scripts/analyze_large_sample.clj

(require '[scr.entity :as e])
(require '[clojure.java.io :as io])

(def sample-dir "resources/filings_large_sample")
(def output-dir "resources/contexts_large_sample")

(println "Starting analysis of large 10-K sample...")
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

;; Run the analysis
(def result (e/run-analysis
             :files files
             :sec-filers-only true
             :parallel true
             :show-common false       ; Skip printing - too verbose for 480 files
             :show-bidirectional true
             :show-errors true))

;; Print summary stats
(println)
(println "========================================")
(println "ANALYSIS COMPLETE")
(println "========================================")
(println "Filings processed:" (:successful (:stats result)) "/" (:total (:stats result)))
(println "Failed:" (:failed (:stats result)))
(println "Entity mentions found:" (reduce + (map :entity-count (:filings result))))
(println "Cross-company relationships:" (count (:common-entities result)))
(println "Bidirectional relationships:" (count (:bidirectional-relationships result)))
(println)

;; Export contexts
(println "Exporting contexts to" output-dir "...")
(def export-result (e/export-all-contexts result :output-dir output-dir))
(println "Done!")
(println)
(println "Results saved to:" output-dir)
(println "Index file:" (:index-path export-result))

;; Save result for later analysis
(spit "resources/large_sample_analysis.edn" (pr-str (dissoc result :filings)))
(println "Summary data saved to: resources/large_sample_analysis.edn")
