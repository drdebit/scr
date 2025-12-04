(ns scr.sec
  "Extract and filter text from SEC filings."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Document Element TextNode]
           [org.jsoup.select Elements]))

(defn- extract-sec-header-metadata
  "Extract metadata from the SEC-HEADER section of an EDGAR .txt file.
   Returns a map with :cik, :filing-type, :period-of-report, :company-name, :filed-date."
  [content]
  (let [header-match (re-find #"(?s)<SEC-HEADER>(.*?)</SEC-HEADER>" content)
        header (when header-match (second header-match))]
    (when header
      {:cik (when-let [m (re-find #"CENTRAL INDEX KEY:\s*(\d+)" header)]
              (second m))
       :filing-type (when-let [m (re-find #"CONFORMED SUBMISSION TYPE:\s*(\S+)" header)]
                      (second m))
       :period-of-report (when-let [m (re-find #"CONFORMED PERIOD OF REPORT:\s*(\d+)" header)]
                           (let [date-str (second m)]
                             ;; Convert YYYYMMDD to YYYY-MM-DD
                             (when (= 8 (count date-str))
                               (str (subs date-str 0 4) "-"
                                    (subs date-str 4 6) "-"
                                    (subs date-str 6 8)))))
       :filed-date (when-let [m (re-find #"FILED AS OF DATE:\s*(\d+)" header)]
                     (let [date-str (second m)]
                       (when (= 8 (count date-str))
                         (str (subs date-str 0 4) "-"
                              (subs date-str 4 6) "-"
                              (subs date-str 6 8)))))
       :company-name (when-let [m (re-find #"COMPANY CONFORMED NAME:\s*([^\n]+)" header)]
                       (str/trim (second m)))})))

(defn- extract-html-from-edgar-txt
  "Extract the HTML document from an EDGAR .txt complete submission file.
   EDGAR .txt files contain SGML-wrapped documents. This extracts the primary
   HTML document (typically the 10-K, 10-Q, or 8-K filing).

   The structure is:
     <DOCUMENT>
     <TYPE>10-K
     <FILENAME>...
     <TEXT>
     <XBRL>
     <html>...</html>
     </XBRL>
     </TEXT>
     </DOCUMENT>"
  [content]
  ;; The documents can be very large, so we use a more efficient approach
  ;; Find the first document that is the main filing (10-K, 10-Q, 8-K, etc.)
  (let [;; Find all document start positions
        doc-starts (loop [pos 0
                          starts []]
                     (let [idx (str/index-of content "<DOCUMENT>" pos)]
                       (if idx
                         (recur (+ idx 10) (conj starts idx))
                         starts)))]
    (when (seq doc-starts)
      ;; For each document start, check if it's the main filing
      (loop [starts doc-starts]
        (when (seq starts)
          (let [start (first starts)
                ;; Get enough text to find the TYPE
                snippet-end (min (+ start 200) (count content))
                snippet (subs content start snippet-end)
                type-match (re-find #"<TYPE>([^\n<]+)" snippet)]
            (if (and type-match
                     (let [doc-type (str/upper-case (str/trim (second type-match)))]
                       (or (= doc-type "10-K")
                           (= doc-type "10-Q")
                           (= doc-type "8-K")
                           (str/starts-with? doc-type "10-K")
                           (str/starts-with? doc-type "10-Q")
                           (str/starts-with? doc-type "8-K")
                           (str/starts-with? doc-type "DEF "))))
              ;; Found the main filing, extract the HTML
              (let [;; Find the end of this document
                    doc-end (str/index-of content "</DOCUMENT>" start)
                    doc-content (when doc-end (subs content start doc-end))]
                (when doc-content
                  ;; Extract the content between <TEXT> and </TEXT>
                  (let [text-match (re-find #"(?s)<TEXT>(.*)</TEXT>" doc-content)]
                    (when text-match
                      (let [text-content (second text-match)]
                        ;; Remove XBRL wrapper if present
                        (-> text-content
                            (str/replace #"^\s*<XBRL>\s*" "")
                            (str/replace #"\s*</XBRL>\s*$" "")
                            ;; Also handle XML declaration if present
                            (str/replace #"^<\?xml[^?]*\?>\s*" "")
                            ;; Remove XML comments at the start
                            (str/replace #"^(<!--[^>]*-->\s*)+" "")))))))
              ;; Not the main filing, try next document
              (recur (rest starts)))))))))

(defn- is-edgar-txt-file?
  "Check if the file content appears to be an EDGAR .txt submission file."
  [content]
  ;; Check for SEC-DOCUMENT tag or SEC-HEADER (some files may have slight variations)
  (or (str/includes? content "<SEC-DOCUMENT>")
      (str/includes? content "<SEC-HEADER>")))

(defn parse-html-file
  "Parse an HTML file and return a Jsoup Document.
   Handles both regular HTML files and EDGAR .txt complete submission files."
  [file-path]
  (let [content (slurp (io/file file-path))]
    (if (is-edgar-txt-file? content)
      ;; Extract HTML from EDGAR .txt wrapper
      (let [html (extract-html-from-edgar-txt content)]
        (if html
          (Jsoup/parse html)
          (throw (ex-info "Could not extract HTML from EDGAR .txt file"
                          {:file file-path}))))
      ;; Regular HTML file - parse from string (already read)
      (Jsoup/parse content))))

(defn parse-edgar-file
  "Parse an EDGAR file (HTML or .txt format) and return both the Jsoup Document
   and any metadata extracted from the SEC header.
   Returns {:doc Document, :metadata map-or-nil, :raw-content String}.
   The raw-content is included to avoid re-reading the file."
  [file-path]
  (let [content (slurp (io/file file-path))]
    (if (is-edgar-txt-file? content)
      ;; Extract HTML and metadata from EDGAR .txt wrapper
      (let [html (extract-html-from-edgar-txt content)
            metadata (extract-sec-header-metadata content)]
        (if html
          {:doc (Jsoup/parse html)
           :metadata metadata
           :raw-content content}
          (throw (ex-info "Could not extract HTML from EDGAR .txt file"
                          {:file file-path}))))
      ;; Regular HTML file - no SEC header metadata
      {:doc (Jsoup/parse content)
       :metadata nil
       :raw-content content})))

(defn extract-text-blocks
  "Extract text blocks from an HTML document.
   Returns a sequence of text strings, one per block-level element (div, p, span, td)."
  [^Document doc]
  (->> (.select doc "div, p, span, td")
       (map #(.ownText ^Element %))
       (remove str/blank?)))

(defn matches-keywords?
  "Check if text contains any of the given keywords (case-insensitive)."
  [text keywords]
  (let [lower-text (str/lower-case text)]
    (some #(str/includes? lower-text (str/lower-case %)) keywords)))

(defn extract-matching-blocks
  "Extract text blocks from an HTML file that match any of the given keywords."
  [file-path keywords]
  (->> (parse-html-file file-path)
       extract-text-blocks
       (filter #(matches-keywords? % keywords))))

(defn extract-customer-supplier-text
  "Extract all text blocks mentioning customers or suppliers from an SEC filing."
  [file-path]
  (extract-matching-blocks file-path ["customer" "supplier"]))

(defn looks-like-prose?
  "Filter out XBRL metadata, URLs, and other non-prose content."
  [text]
  (and (> (count text) 100)
       ;; Must contain spaces (prose has words)
       (str/includes? text " ")
       ;; Not XBRL/XML data
       (not (str/includes? text "http://fasb.org"))
       (not (str/includes? text "http://xbrl"))
       (not (str/starts-with? text "FALSE"))
       ;; Has sentence-like structure (contains period followed by space or end)
       (re-find #"\.\s|\.$" text)))

(defn remove-nested-duplicates
  "Remove strings that are substrings of other strings in the collection.
   Optimized: sorts by length descending and checks only longer strings."
  [texts]
  (let [;; Sort by length descending - longer strings checked first
        sorted-texts (sort-by (comp - count) (distinct texts))]
    (loop [remaining sorted-texts
           kept []]
      (if (empty? remaining)
        kept
        (let [current (first remaining)
              rest-texts (rest remaining)]
          ;; Check if current is substring of any already-kept (longer) string
          (if (some #(str/includes? % current) kept)
            (recur rest-texts kept)
            (recur rest-texts (conj kept current))))))))

(defn extract-with-context
  "Extract paragraphs containing keywords, preserving more context.
   Uses larger text units (div with nested content) for better readability."
  [^Document doc keywords]
  (->> (.select doc "div")
       (map #(.text ^Element %))
       (remove str/blank?)
       (filter #(matches-keywords? % keywords))
       (filter looks-like-prose?)
       distinct
       remove-nested-duplicates))

(defn extract-customer-supplier-paragraphs
  "Extract paragraphs mentioning customers or suppliers from an SEC filing.
   Returns fuller context than extract-customer-supplier-text."
  [file-path]
  (-> (parse-html-file file-path)
      (extract-with-context ["customer" "supplier"])))

(defn extract-to-file
  "Extract customer/supplier text from an SEC filing and write to output file.
   Each paragraph is separated by a blank line."
  [input-path output-path]
  (let [paragraphs (extract-customer-supplier-paragraphs input-path)]
    (spit output-path (str/join "\n\n---\n\n" paragraphs))
    (count paragraphs)))

(defn print-paragraphs
  "Print paragraphs with numbered headers and separators.
   Options:
     :limit  - max paragraphs to print (default: all)
     :width  - wrap text at this width (default: 80)"
  [paragraphs & {:keys [limit width] :or {width 80}}]
  (let [ps (if limit (take limit paragraphs) paragraphs)
        wrap (fn [s]
               (->> (str/split s #"\s+")
                    (reduce (fn [[lines current] word]
                              (if (> (+ (count current) (count word) 1) width)
                                [(conj lines current) word]
                                [lines (if (empty? current) word (str current " " word))]))
                            [[] ""])
                    ((fn [[lines last]] (conj lines last)))
                    (str/join "\n")))]
    (doseq [[i p] (map-indexed vector ps)]
      (println (str "\n[" (inc i) "/" (count paragraphs) "] " (apply str (repeat 60 "-"))))
      (println (wrap p)))))

(defn run
  "Run the full extraction pipeline on the Ford 10-K filing.
   Options:
     :limit  - max paragraphs to display (default: 10)
     :all    - if true, show all paragraphs
     :file   - input file path (default: Ford 10-K in resources)"
  [& {:keys [limit all file] :or {limit 10 file "resources/filings/f-20241231.html"}}]
  (println "Extracting customer/supplier text from:" file)
  (let [paragraphs (extract-customer-supplier-paragraphs file)]
    (println "Found" (count paragraphs) "paragraphs\n")
    (print-paragraphs paragraphs :limit (when-not all limit))
    (when (and (not all) (> (count paragraphs) limit))
      (println (str "\n... and " (- (count paragraphs) limit) " more. Use (run :all true) to see all.")))
    {:total (count paragraphs) :shown (if all (count paragraphs) (min limit (count paragraphs)))}))

;; ============================================================================
;; Named Entity Extraction
;; ============================================================================

(def ^:private relationship-patterns
  "Regex patterns that indicate a business relationship context."
  [#"(?i)(partnership|joint venture|alliance|agreement|contract|arrangement|relationship)\s+(with|between)\s+([A-Z][A-Za-z\s,&]+?)(?=[\.,;]|\s+(?:to|for|that|which|and|in|is|was|has|have))"
   #"(?i)(supplier|customer|partner|vendor)\s+(?:is|named|called|known as)?\s*([A-Z][A-Za-z\s,&]+?)(?=[\.,;]|\s+(?:to|for|that|which))"
   #"(?i)(supplied by|purchased from|sold to|acquired from|partnered with)\s+([A-Z][A-Za-z\s,&]+?)(?=[\.,;]|\s+(?:to|for|that|which|and|in))"])

(def ^:private known-entities
  "Known company names that appear in SEC filings as suppliers/partners/customers.
   This list can be expanded based on the specific filing being analyzed."
  #{"SK On" "SK Innovation" "SK Battery" "BlueOval SK" "BlueOval"
    "Rivian" "Argo AI" "Argo"
    "CATL" "LG Energy" "LG Chem" "Panasonic" "Samsung SDI"
    "Magna" "Bosch" "Continental" "Denso" "Aptiv" "ZF" "Getrag"
    "Takata" "ARC Automotive" "Delphi" "Delphi Automotive"
    "Changan" "Changan Ford" "Mazda" "AAT" "AutoAlliance"
    "Jiangling" "JMC" "Jiangling Motors"
    "Diesel Song Cong" "Vietnam Engine"
    "UAW" "Unifor"
    "Ford Credit" "FCE"
    "Volkswagen" "Google" "Amazon" "Qualcomm" "NVIDIA" "Mobileye"
    "Department of Energy" "DOE" "NHTSA" "California"})

(defn- contains-named-entity?
  "Check if text contains any known named entity."
  [text]
  (some #(str/includes? text %) known-entities))

(defn- extract-entity-names
  "Extract potential company/entity names from text using patterns.
   Returns a set of entity names found."
  [text]
  (let [;; Pattern for capitalized multi-word sequences (potential company names)
        cap-pattern #"[A-Z][a-z]+(?:\s+[A-Z][a-z]+)+(?:\s+(?:LLC|Inc|Ltd|Co|Corp|Corporation|Company|Limited|LP|LLP))?"
        ;; Find all matches
        matches (re-seq cap-pattern text)]
    (into #{} (map str/trim matches))))

(defn extract-named-relationships
  "Extract paragraphs that mention specific named entities in supplier/customer/partner context.
   Returns a sequence of maps with :text and :entities keys."
  [^Document doc]
  (let [;; Get all prose paragraphs
        all-paragraphs (->> (.select doc "div")
                            (map #(.text ^Element %))
                            (remove str/blank?)
                            (filter looks-like-prose?)
                            distinct
                            remove-nested-duplicates)
        ;; Filter to those with named entities
        with-entities (filter contains-named-entity? all-paragraphs)]
    (map (fn [text]
           {:text text
            :entities (into (sorted-set)
                            (filter #(str/includes? text %)
                                    known-entities))})
         with-entities)))

(defn extract-named-customer-supplier
  "Extract paragraphs mentioning named customers, suppliers, or partners from an SEC filing.
   Returns a sequence of maps with :text and :entities."
  [file-path]
  (-> (parse-html-file file-path)
      extract-named-relationships))

(defn print-named-entities
  "Print extracted paragraphs with their associated entity names.
   Options:
     :limit  - max paragraphs to print (default: all)
     :width  - wrap text at this width (default: 80)"
  [results & {:keys [limit width] :or {width 80}}]
  (let [rs (if limit (take limit results) results)
        wrap (fn [s]
               (->> (str/split s #"\s+")
                    (reduce (fn [[lines current] word]
                              (if (> (+ (count current) (count word) 1) width)
                                [(conj lines current) word]
                                [lines (if (empty? current) word (str current " " word))]))
                            [[] ""])
                    ((fn [[lines last]] (conj lines last)))
                    (str/join "\n")))]
    (doseq [[i {:keys [text entities]}] (map-indexed vector rs)]
      (println (str "\n[" (inc i) "/" (count results) "] " (apply str (repeat 60 "-"))))
      (println "Entities:" (str/join ", " entities))
      (println)
      (println (wrap text)))))

(defn run-named
  "Run extraction pipeline for named entities only.
   Options:
     :limit  - max paragraphs to display (default: 10)
     :all    - if true, show all paragraphs
     :file   - input file path (default: Ford 10-K in resources)"
  [& {:keys [limit all file] :or {limit 10 file "resources/filings/f-20241231.html"}}]
  (println "Extracting named customer/supplier/partner references from:" file)
  (let [results (extract-named-customer-supplier file)]
    (println "Found" (count results) "paragraphs with named entities\n")
    (print-named-entities results :limit (when-not all limit))
    (when (and (not all) (> (count results) limit))
      (println (str "\n... and " (- (count results) limit) " more. Use (run-named :all true) to see all.")))
    {:total (count results)
     :shown (if all (count results) (min limit (count results)))
     :all-entities (into (sorted-set) (mapcat :entities results))}))
