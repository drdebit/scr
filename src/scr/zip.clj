(ns scr.zip
  "Process SEC filings directly from zip archives without extraction.
   Provides streaming/lazy access to zip entries to handle large archives."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.zip ZipFile ZipEntry]
           [java.io BufferedReader InputStreamReader]))

(defn list-entries
  "List all entries in a zip file. Returns a sequence of entry names.
   Options:
     :filter-fn - predicate to filter entries (default: all entries)
     :limit - max entries to return"
  [zip-path & {:keys [filter-fn limit]}]
  (with-open [zf (ZipFile. (io/file zip-path))]
    (let [entries (->> (enumeration-seq (.entries zf))
                       (map #(.getName ^ZipEntry %))
                       (filter (or filter-fn (constantly true))))]
      (vec (if limit (take limit entries) entries)))))

(defn count-entries
  "Count entries in a zip file matching an optional filter.
   The filter-fn receives the entry name (string), not the ZipEntry object."
  [zip-path & {:keys [filter-fn]}]
  (with-open [zf (ZipFile. (io/file zip-path))]
    (->> (enumeration-seq (.entries zf))
         (map #(.getName ^ZipEntry %))
         (filter (or filter-fn (constantly true)))
         count)))

(defn read-entry
  "Read a single entry from a zip file as a string.
   Returns nil if entry not found."
  [zip-path entry-name]
  (with-open [zf (ZipFile. (io/file zip-path))]
    (when-let [entry (.getEntry zf entry-name)]
      (with-open [is (.getInputStream zf entry)
                  reader (BufferedReader. (InputStreamReader. is "UTF-8"))]
        (slurp reader)))))

(defn- process-entry
  "Process a single zip entry with the given function.
   Returns the result of (f entry-name content) or error map on failure."
  [^ZipFile zf ^ZipEntry entry f]
  (let [entry-name (.getName entry)]
    (try
      (with-open [is (.getInputStream zf entry)
                  reader (BufferedReader. (InputStreamReader. is "UTF-8"))]
        (let [content (slurp reader)]
          {:success true
           :entry entry-name
           :result (f entry-name content)}))
      (catch Exception e
        {:success false
         :entry entry-name
         :error {:type (type e)
                 :message (.getMessage e)}}))))

(defn process-entries
  "Process entries from a zip file with a function (f entry-name content).
   Returns a lazy sequence of results. Each result is a map with
   :success, :entry, and either :result or :error.

   Options:
     :filter-fn - predicate on entry name to filter which entries to process
     :limit - max entries to process
     :parallel - if true, process in parallel (default: false)
     :progress-fn - called with (current total) for progress updates

   Note: The returned sequence must be fully consumed before the ZipFile
   is closed, so this function realizes the entire sequence."
  [zip-path f & {:keys [filter-fn limit parallel progress-fn]}]
  (with-open [zf (ZipFile. (io/file zip-path))]
    (let [all-entries (->> (enumeration-seq (.entries zf))
                           (remove #(.isDirectory ^ZipEntry %))
                           (filter (if filter-fn
                                     #(filter-fn (.getName ^ZipEntry %))
                                     (constantly true))))
          entries (if limit (take limit all-entries) all-entries)
          entries-vec (vec entries)
          total (count entries-vec)
          process-with-progress (fn [idx entry]
                                  (when progress-fn
                                    (progress-fn (inc idx) total))
                                  (process-entry zf entry f))
          results (if parallel
                    (pmap #(process-with-progress %1 %2)
                          (range total)
                          entries-vec)
                    (map-indexed process-with-progress entries-vec))]
      ;; Force realization before closing the ZipFile
      (vec results))))

(defn reduce-entries
  "Reduce over zip entries with a function.
   Calls (f acc entry-name content) for each entry.

   Options:
     :filter-fn - predicate on entry name
     :limit - max entries to process
     :progress-fn - called with (current total) for progress
     :continue-on-error - if true, skip failed entries (default: true)

   Returns the final accumulated value."
  [zip-path f init & {:keys [filter-fn limit progress-fn continue-on-error]
                      :or {continue-on-error true}}]
  (with-open [zf (ZipFile. (io/file zip-path))]
    (let [all-entries (->> (enumeration-seq (.entries zf))
                           (remove #(.isDirectory ^ZipEntry %))
                           (filter (if filter-fn
                                     #(filter-fn (.getName ^ZipEntry %))
                                     (constantly true))))
          entries (if limit (take limit all-entries) all-entries)
          entries-vec (vec entries)
          total (count entries-vec)]
      (reduce
       (fn [acc [idx ^ZipEntry entry]]
         (when progress-fn
           (progress-fn (inc idx) total))
         (let [entry-name (.getName entry)]
           (try
             (with-open [is (.getInputStream zf entry)
                         reader (BufferedReader. (InputStreamReader. is "UTF-8"))]
               (let [content (slurp reader)]
                 (f acc entry-name content)))
             (catch Exception e
               (if continue-on-error
                 acc
                 (throw e))))))
       init
       (map-indexed vector entries-vec)))))

(defn is-10k-filing?
  "Check if a zip entry name looks like a 10-K filing."
  [entry-name]
  (and (str/ends-with? entry-name ".txt")
       (not (str/ends-with? entry-name "index.html"))
       (not (str/ends-with? entry-name "wget-log"))
       ;; Exclude entries that are just directories
       (not (str/ends-with? entry-name "/"))))

(defn sample-entries
  "Get a random sample of entries from a zip file.
   Options:
     :filter-fn - predicate on entry name
     :n - number of entries to sample (default: 100)"
  [zip-path & {:keys [filter-fn n] :or {n 100}}]
  (let [all-entries (list-entries zip-path :filter-fn filter-fn)]
    (if (<= (count all-entries) n)
      all-entries
      (take n (shuffle all-entries)))))
