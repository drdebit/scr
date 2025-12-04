(ns scr.sec-filers
  "SEC filer registry for validating and normalizing entity names.

   Uses the SEC's company_tickers.json to build a lookup index that maps
   company names, tickers, and name variations to CIK numbers."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Data Loading
;; ============================================================================

(def ^:private data-file "sec/company_tickers.json")

(defn- load-sec-data
  "Load the SEC company tickers JSON file."
  []
  (let [resource (io/resource data-file)]
    (if resource
      (json/parse-string (slurp resource) true)
      (throw (ex-info "SEC company tickers file not found" {:file data-file})))))

;; ============================================================================
;; Name Normalization
;; ============================================================================

(def ^:private common-suffixes
  "Company name suffixes to strip for matching."
  [#"(?i),?\s*(inc\.?|incorporated)$"
   #"(?i),?\s*(corp\.?|corporation)$"
   #"(?i),?\s*(co\.?|company)$"
   #"(?i),?\s*(ltd\.?|limited)$"
   #"(?i),?\s*(llc|l\.l\.c\.)$"
   #"(?i),?\s*(plc|p\.l\.c\.)$"
   #"(?i),?\s*(lp|l\.p\.)$"
   #"(?i),?\s*(llp|l\.l\.p\.)$"
   #"(?i),?\s*(n\.v\.|nv)$"
   #"(?i),?\s*(s\.a\.|sa)$"
   #"(?i),?\s*(ag|a\.g\.)$"
   #"(?i),?\s*(gmbh)$"
   #"(?i),?\s*(se)$"
   #"(?i)\s*/[a-z]+/?$"  ; /DE/, /NEW/, etc.
   #"(?i)\s*\\[a-z]+\\?$"  ; \DE\, etc.
   #"(?i),?\s*&\s*co\.?$"])

(defn normalize-name
  "Normalize a company name for matching.
   Removes suffixes, extra whitespace, and converts to lowercase."
  [name]
  (when name
    (-> name
        str/trim
        ;; Apply all suffix removals
        ((fn [s] (reduce (fn [n re] (str/replace n re "")) s common-suffixes)))
        ;; Remove parenthetical notes like (The), (A), etc.
        (str/replace #"\s*\([^)]*\)\s*$" "")
        ;; Normalize whitespace
        (str/replace #"\s+" " ")
        str/trim
        str/lower-case)))

(defn name-key
  "Generate a key for exact matching."
  [name]
  (-> name
      normalize-name
      (str/replace #"[^a-z0-9]+" "")
      (str/replace #"^the" "")))

;; ============================================================================
;; Name Variations Generation
;; ============================================================================

(defn- generate-variations
  "Generate common variations of a company name for matching."
  [title]
  (let [normalized (normalize-name title)
        ;; Base variations
        base-key (name-key title)]
    (into #{base-key}
          (remove str/blank?
                  [;; With "the" prefix
                   (name-key (str "the " title))
                   ;; Without common words
                   (name-key (str/replace title #"(?i)\bholdings?\b" ""))
                   (name-key (str/replace title #"(?i)\bgroup\b" ""))
                   (name-key (str/replace title #"(?i)\binternational\b" ""))
                   (name-key (str/replace title #"(?i)\bglobal\b" ""))
                   (name-key (str/replace title #"(?i)\btechnolog(y|ies)\b" ""))
                   (name-key (str/replace title #"(?i)\bsystems?\b" ""))
                   (name-key (str/replace title #"(?i)\bservices?\b" ""))
                   (name-key (str/replace title #"(?i)\bsolutions?\b" ""))
                   ;; First word only (for single-name companies like "Apple", "Ford")
                   (when-let [first-word (first (str/split normalized #"\s+"))]
                     (when (>= (count first-word) 3)
                       first-word))]))))

;; ============================================================================
;; Index Building
;; ============================================================================

(defn- build-index
  "Build lookup indices from SEC data.
   Returns {:by-cik {cik -> company-info}
            :by-name {normalized-name -> cik}
            :by-ticker {ticker -> cik}
            :by-key {name-key -> #{ciks}}}"
  [sec-data]
  (let [companies (vals sec-data)]
    {:by-cik (into {}
                   (map (fn [{:keys [cik_str ticker title]}]
                          [(str cik_str) {:cik (str cik_str)
                                          :ticker ticker
                                          :name title}]))
                   companies)
     :by-name (into {}
                    (map (fn [{:keys [cik_str title]}]
                           [(normalize-name title) (str cik_str)]))
                    companies)
     :by-ticker (into {}
                      (map (fn [{:keys [cik_str ticker]}]
                             [(str/lower-case (or ticker "")) (str cik_str)]))
                      companies)
     :by-key (reduce (fn [idx {:keys [cik_str title ticker]}]
                       (let [cik (str cik_str)
                             variations (generate-variations title)
                             ;; Also add ticker as a key
                             all-keys (if ticker
                                        (conj variations (str/lower-case ticker))
                                        variations)]
                         (reduce (fn [i k]
                                   (update i k (fnil conj #{}) cik))
                                 idx
                                 all-keys)))
                     {}
                     companies)}))

;; ============================================================================
;; Registry (Lazy Loaded Singleton)
;; ============================================================================

(defonce ^:private registry (delay (build-index (load-sec-data))))

(defn get-registry
  "Get the SEC filer registry (lazy loaded)."
  []
  @registry)

;; ============================================================================
;; Lookup Functions
;; ============================================================================

(defn lookup-by-cik
  "Look up a company by CIK number."
  [cik]
  (let [cik-str (if (string? cik)
                  (str/replace cik #"^0+" "")
                  (str cik))]
    (get (:by-cik (get-registry)) cik-str)))

(defn lookup-by-name
  "Look up a company by exact normalized name."
  [name]
  (when-let [cik (get (:by-name (get-registry)) (normalize-name name))]
    (lookup-by-cik cik)))

(defn lookup-by-ticker
  "Look up a company by ticker symbol."
  [ticker]
  (when-let [cik (get (:by-ticker (get-registry)) (str/lower-case ticker))]
    (lookup-by-cik cik)))

(defn lookup-by-key
  "Look up companies matching a name key.
   Returns a set of company info maps (may have multiple matches)."
  [name]
  (let [key (name-key name)
        ciks (get (:by-key (get-registry)) key)]
    (when (seq ciks)
      (into #{} (map lookup-by-cik ciks)))))

(defn find-matches
  "Find all SEC filers matching a name.
   Uses multiple strategies: exact match, key match, ticker match.
   Returns a set of company info maps."
  [name]
  (let [normalized (normalize-name name)
        key (name-key name)]
    (into #{}
          (remove nil?
                  (concat
                   ;; Exact name match
                   [(lookup-by-name name)]
                   ;; Key-based match
                   (lookup-by-key name)
                   ;; Ticker match (if name looks like a ticker)
                   (when (and (re-matches #"[A-Z]{1,5}" (str/upper-case name))
                              (<= (count name) 5))
                     [(lookup-by-ticker name)]))))))

;; ============================================================================
;; Fuzzy Matching
;; ============================================================================

(defn- levenshtein-distance
  "Calculate Levenshtein distance between two strings.
   Optimized to use O(min(n,m)) space with two-row algorithm."
  [s1 s2]
  (let [len1 (count s1)
        len2 (count s2)]
    (cond
      (zero? len1) len2
      (zero? len2) len1
      ;; Optimize by making s1 the shorter string (less memory)
      (> len1 len2) (recur s2 s1)
      :else
      ;; Use two-row algorithm: O(min(n,m)) space instead of O(n*m)
      (let [s1-vec (vec s1)  ; Avoid repeated nth on strings
            s2-vec (vec s2)]
        (loop [i 0
               prev-row (vec (range (inc len2)))]
          (if (>= i len1)
            (peek prev-row)
            (let [c1 (s1-vec i)
                  curr-row (loop [j 0
                                  row [(inc i)]]
                             (if (>= j len2)
                               row
                               (let [c2 (s2-vec j)
                                     cost (if (= c1 c2) 0 1)
                                     val (min (inc (peek row))           ; insert
                                              (inc (prev-row (inc j)))   ; delete
                                              (+ (prev-row j) cost))]    ; substitute
                                 (recur (inc j) (conj row val)))))]
              (recur (inc i) curr-row))))))))

(defn- similarity-score
  "Calculate similarity score between two strings (0.0 to 1.0)."
  [s1 s2]
  (let [k1 (name-key s1)
        k2 (name-key s2)
        max-len (max (count k1) (count k2))]
    (if (zero? max-len)
      0.0
      (- 1.0 (/ (levenshtein-distance k1 k2) (double max-len))))))

(defn fuzzy-match
  "Find SEC filers with names similar to the given name.
   Returns matches with similarity >= threshold (default 0.8)."
  ([name] (fuzzy-match name 0.8))
  ([name threshold]
   (let [key (name-key name)
         all-names (keys (:by-name (get-registry)))]
     (->> all-names
          (map (fn [n]
                 (let [score (similarity-score name n)]
                   (when (>= score threshold)
                     {:name n
                      :score score
                      :company (lookup-by-name n)}))))
          (remove nil?)
          (sort-by :score >)
          (take 10)))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn sec-filer?
  "Check if a name matches any known SEC filer."
  [name]
  (boolean (seq (find-matches name))))

(defn resolve-entity
  "Resolve an entity name to its canonical SEC filer info.
   Returns nil if not found, or a map with :cik, :ticker, :name."
  [name]
  (let [matches (find-matches name)]
    (cond
      (empty? matches) nil
      (= 1 (count matches)) (first matches)
      ;; Multiple matches - prefer exact match
      :else (or (first (filter #(= (normalize-name (:name %))
                                   (normalize-name name))
                               matches))
                (first matches)))))

(defn stats
  "Return statistics about the SEC filer registry."
  []
  (let [reg (get-registry)]
    {:total-companies (count (:by-cik reg))
     :unique-names (count (:by-name reg))
     :unique-tickers (count (remove str/blank? (keys (:by-ticker reg))))
     :name-keys (count (:by-key reg))}))
