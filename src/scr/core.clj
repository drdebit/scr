(ns scr.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Document]))

(defn extract-text-from-html
  "Extract plain text from HTML content using Jsoup."
  [html-string]
  (-> (Jsoup/parse html-string)
      (.text)))

(defn extract-text-from-html-file
  "Extract plain text from an HTML file."
  [file-path]
  (-> (Jsoup/parse (io/file file-path) "UTF-8")
      (.text)))

(defn process-text-file-lazily
  "Process a text file lazily, applying f to each line.
   Returns a lazy sequence - must be consumed within with-open."
  [file-path f]
  (with-open [rdr (io/reader file-path)]
    (doall (map f (line-seq rdr)))))

(defn reduce-text-file
  "Reduce over lines of a text file with constant memory usage.
   Best for very large files."
  [file-path f init]
  (with-open [rdr (io/reader file-path)]
    (reduce f init (line-seq rdr))))

(defn extract-text-from-file
  "Extract text from a file. Dispatches based on file extension."
  [file-path]
  (let [path (str file-path)
        ext (str/lower-case (or (re-find #"\.[^.]+$" path) ""))]
    (case ext
      (".html" ".htm") (extract-text-from-html-file file-path)
      ;; Default: read as plain text
      (slurp file-path))))
