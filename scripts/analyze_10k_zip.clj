#!/usr/bin/env clojure
;; Script to analyze 10-K filings from a zip archive
;; Run with: clj -M:dev scripts/analyze_10k_zip.clj

(ns analyze-10k-zip
  (:require [scr.entity :as e]
            [scr.zip :as z]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

(def zip-path "/media/extra2/10Ks.zip")
(def output-dir "/home/mdeangelis/clojure/scr/resources/10k_analysis")

;; Filter for 2010-2014 filings (better HTML structure)
(defn recent-filing? [name]
  (and (z/is-10k-filing? name)
       (re-find #"-1[0-4]-" name)))

(defn -main [& args]
  (let [limit (when (and (seq args) (first args) (not (empty? (first args))))
                (Integer/parseInt (first args)))]

    ;; Create output directory
    (.mkdirs (io/file output-dir))

    (println "=" (apply str (repeat 70 "=")))
    (println "10-K Filing Analysis")
    (println "=" (apply str (repeat 70 "=")))
    (println "\nZip file:" zip-path)
    (println "Output dir:" output-dir)
    (println "Limit:" (or limit "all"))

    ;; Count available filings
    (println "\nCounting available filings...")
    (let [total-count (z/count-entries zip-path :filter-fn recent-filing?)]
      (println "Found" total-count "filings from 2010-2014")
      (println "Will process:" (if limit (min limit total-count) total-count)))

    (println "\nStarting analysis...")
    (let [start-time (System/currentTimeMillis)

          ;; Run the analysis
          result (e/analyze-zip-archive zip-path
                                        :sec-filers-only true
                                        :filter-fn recent-filing?
                                        :limit limit
                                        :progress-interval 500)

          end-time (System/currentTimeMillis)
          elapsed-hrs (/ (- end-time start-time) 1000.0 3600.0)

          ;; Find bidirectional relationships
          _ (println "\nFinding bidirectional relationships...")
          bidirectional (when (> (count (:results result)) 1)
                          (e/find-bidirectional-relationships (:results result)))

          ;; Prepare summary
          filings-with-entities (filter #(pos? (:entity-count %)) (:results result))
          total-entities (reduce + 0 (map :entity-count (:results result)))
          unique-ciks (->> (:results result)
                           (mapcat :entities)
                           (map :cik)
                           (remove nil?)
                           distinct
                           count)

          summary {:analysis-date (str (java.time.LocalDateTime/now))
                   :zip-path zip-path
                   :filter "2010-2014 filings"
                   :limit limit
                   :stats {:total-filings (count (:results result))
                           :filings-with-entities (count filings-with-entities)
                           :total-entity-mentions total-entities
                           :unique-entities-mentioned unique-ciks
                           :bidirectional-relationships (count bidirectional)
                           :errors (count (:errors result))
                           :elapsed-hours elapsed-hrs
                           :rate-per-second (/ (count (:results result)) (/ (- end-time start-time) 1000.0))}
                   :bidirectional bidirectional}]

      ;; Print summary
      (println "\n" (apply str (repeat 70 "=")))
      (println "ANALYSIS COMPLETE")
      (println (apply str (repeat 70 "=")))
      (println "\nStatistics:")
      (doseq [[k v] (:stats summary)]
        (println "  " k ":" v))

      (println "\nBidirectional relationships found:" (count bidirectional))
      (when (seq bidirectional)
        (println "\nTop bidirectional pairs:")
        (doseq [{:keys [company-a company-b]} (take 20 bidirectional)]
          (println "  " (:name company-a) "<->" (:name company-b)
                   "(" (:mentions-b company-a) "/" (:mentions-a company-b) "mentions)")))

      ;; Save results
      (let [summary-path (str output-dir "/summary.edn")
            bidir-path (str output-dir "/bidirectional.edn")
            errors-path (str output-dir "/errors.edn")

            ;; Save full filing data (without contexts to save space)
            filings-summary (->> (:results result)
                                 (map #(dissoc % :entities))
                                 (map #(assoc % :entity-count (:entity-count %))))]

        ;; Save summary
        (with-open [w (io/writer summary-path)]
          (pp/pprint summary w))
        (println "\nSaved summary to:" summary-path)

        ;; Save bidirectional relationships
        (with-open [w (io/writer bidir-path)]
          (pp/pprint bidirectional w))
        (println "Saved bidirectional relationships to:" bidir-path)

        ;; Save errors if any
        (when (seq (:errors result))
          (with-open [w (io/writer errors-path)]
            (pp/pprint (:errors result) w))
          (println "Saved errors to:" errors-path))

        ;; Save entity index (all entities found)
        (let [entity-index-path (str output-dir "/entity_index.csv")
              all-entities (for [filing (:results result)
                                 entity (:entities filing)]
                             {:filer-cik (:cik filing)
                              :filer-name (:filer-name filing)
                              :filing-date (:filing-date filing)
                              :entity-cik (:cik entity)
                              :entity-name (:sec-name entity)
                              :entity-ticker (:ticker entity)
                              :mention-count (:mention-count entity)
                              :relationships (clojure.string/join "," (map name (:relationships entity)))})]
          (with-open [w (io/writer entity-index-path)]
            (.write w "filer_cik,filer_name,filing_date,entity_cik,entity_name,entity_ticker,mention_count,relationships\n")
            (doseq [e all-entities]
              (.write w (str (:filer-cik e) ","
                             (pr-str (:filer-name e)) ","
                             (:filing-date e) ","
                             (:entity-cik e) ","
                             (pr-str (:entity-name e)) ","
                             (or (:entity-ticker e) "") ","
                             (:mention-count e) ","
                             (:relationships e) "\n"))))
          (println "Saved entity index to:" entity-index-path)
          (println "Total entity records:" (count all-entities))))

      (println "\nDone!"))))

;; Run main
(-main (first *command-line-args*))
