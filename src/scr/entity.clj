(ns scr.entity
  "Extract and normalize named entities from SEC filings for cross-filing matching.

   Key concepts:
   - Each filing has a CIK (Central Index Key) that uniquely identifies the filer
   - Entities mentioned in filings can be matched across filings by normalizing names
   - Relationship context (supplier, customer, partner, etc.) is preserved
   - Entities are validated against the SEC filer registry for accurate matching
   - Known entity names are loaded from resources/sec/known_entities.edn"
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [scr.sec :as sec]
            [scr.sec-filers :as filers]
            [scr.known-entities :as known])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Document Element]))

;; ============================================================================
;; Filing Metadata Extraction
;; ============================================================================

(defn- extract-cik-from-html
  "Extract the CIK (Central Index Key) from HTML string.
   Returns the CIK as a string (with leading zeros preserved)."
  [^String html]
  (or (when-let [match (re-find #"EntityCentralIndexKey[^>]*>(\d+)" html)]
        (second match))
      (when-let [match (re-find #"CIK\">(\d+)" html)]
        (second match))
      (when-let [match (re-find #"scheme=\"http://www.sec.gov/CIK\">(\d+)" html)]
        (second match))))

(defn extract-cik
  "Extract the CIK from a Jsoup Document. Prefer extract-cik-from-html for efficiency."
  [^Document doc]
  (extract-cik-from-html (str doc)))

(defn- extract-filer-name-from-html
  "Extract the filer's company name from HTML string."
  [^String html]
  (or (when-let [match (re-find #"EntityRegistrantName[^>]*>([^<]+)" html)]
        (str/trim (second match)))
      (when-let [match (re-find #"<title>([^<]+)</title>" html)]
        (-> (second match)
            (str/replace #"-\d+$" "")
            str/trim
            str/upper-case))))

(defn extract-filer-name
  "Extract the filer's company name from a Jsoup Document. Prefer extract-filer-name-from-html."
  [^Document doc]
  (extract-filer-name-from-html (str doc)))

(defn- extract-cik-from-raw
  "Extract CIK from raw file content string (first ~200 lines worth).
   Used as fallback when HTML extraction fails."
  [^String content]
  (let [;; Just look at the first portion for CIK
        first-portion (subs content 0 (min 20000 (count content)))]
    (or (when-let [match (re-find #"EntityCentralIndexKey[^>]*>(\d+)" first-portion)]
          (second match))
        (when-let [match (re-find #"CIK\">(\d+)" first-portion)]
          (second match))
        (when-let [match (re-find #"/CIK\">(\d+)" first-portion)]
          (second match))
        (when-let [match (re-find #"edgar/data/(\d+)/" first-portion)]
          (second match)))))

(defn- extract-filing-date-from-html
  "Extract the fiscal period end date from HTML string.
   Returns a string like '2024-12-31' or nil."
  [^String html file-path]
  (or
   ;; Try DocumentPeriodEndDate from XBRL
   (when-let [match (re-find #"DocumentPeriodEndDate[^>]*>(\d{4}-\d{2}-\d{2})" html)]
     (second match))
   ;; Try to extract from filename pattern like f-20241231.html
   (when-let [match (re-find #"(\d{4})(\d{2})(\d{2})\.html?$" file-path)]
     (str (nth match 1) "-" (nth match 2) "-" (nth match 3)))
   ;; Try context period enddate
   (when-let [match (re-find #"<xbrli:enddate>(\d{4}-\d{2}-\d{2})</xbrli:enddate>" html)]
     (second match))))

(defn- extract-filing-type-from-html
  "Extract the filing type (10-K, 10-Q, 8-K, etc.) from HTML string."
  [^String html]
  (or
   ;; Try DocumentType from XBRL
   (when-let [match (re-find #"DocumentType[^>]*>([^<]+)" html)]
     (str/trim (second match)))
   ;; Try to infer from common patterns
   (cond
     (re-find #"(?i)form\s+10-k" html) "10-K"
     (re-find #"(?i)form\s+10-q" html) "10-Q"
     (re-find #"(?i)form\s+8-k" html) "8-K"
     (re-find #"(?i)annual\s+report" html) "10-K"
     (re-find #"(?i)quarterly\s+report" html) "10-Q"
     :else nil)))

(defn extract-filing-metadata
  "Extract metadata from an SEC filing.
   Returns a map with :cik, :name, :file-path, :filing-date, :filing-type, :doc, :html-str.
   For EDGAR .txt files, also extracts metadata from the SEC header.
   The :html-str is cached for efficient reuse by other extraction functions."
  [file-path]
  (let [{:keys [doc metadata raw-content]} (sec/parse-edgar-file file-path)
        ;; Stringify document ONCE for all extraction functions
        html-str (str doc)
        ;; Use SEC header metadata if available, fall back to HTML extraction
        cik (or (:cik metadata)
                (extract-cik-from-html html-str)
                (extract-cik-from-raw raw-content))
        filing-date (or (:period-of-report metadata)
                        (extract-filing-date-from-html html-str file-path))
        filing-type (or (:filing-type metadata)
                        (extract-filing-type-from-html html-str))
        filer-name (or (:company-name metadata)
                       (extract-filer-name-from-html html-str))]
    {:cik cik
     :name filer-name
     :file-path file-path
     :filing-date filing-date
     :filing-type filing-type
     :filed-date (:filed-date metadata)
     :doc doc
     :html-str html-str}))

;; ============================================================================
;; Entity Name Patterns and Detection
;; ============================================================================

(def ^:private company-suffixes
  "Common company name suffixes."
  #{"LLC" "Inc" "Inc." "Ltd" "Ltd." "Co" "Co." "Corp" "Corp."
    "Corporation" "Company" "Limited" "LP" "LLP" "PLC" "plc"
    "GmbH" "AG" "S.A." "N.V." "B.V."})

;; Forward declaration for normalize-entity-name (used by looks-like-company-name?)
(declare normalize-entity-name)

(def ^:private relationship-indicators
  "Words/phrases that indicate a business relationship."
  ["joint venture" "partnership" "agreement" "alliance" "arrangement"
   "contract" "supplier" "customer" "partner" "vendor" "subsidiary"
   "affiliate" "investee" "equity method" "acquisition" "acquired"
   "purchased from" "sold to" "supplied by" "partnered with"])

(def ^:private generic-terms
  "Terms that look like entity names but are generic."
  #{"United States" "North America" "South America" "Europe" "Asia"
    "China" "Mexico" "Canada" "Germany" "Japan" "Korea" "India" "Russia"
    "The Company" "Our Company" "Ford Motor" "General Motors"
    "Ford Blue" "Ford Pro" "Ford Model" "GM Financial"
    "Risk Factors" "Item" "Note" "Notes" "Financial Statements"
    "Board of Directors" "Annual Report" "Form"
    ;; Common SEC filing boilerplate
    "The Com" "Our Com" "Most Com" "Net Inc" "Net" "Fixed Inc" "Fixed"
    "Such" "These" "Internal Con" "Audit Com" "Executive Com"
    "Changes in Com" "Total Com" "EU Com" "Transit Con"
    "Discussion and Analysis" "Forward-Looking Statements"
    "Consolidated Statements" "Management" "Part" "Exhibit"
    "Rule" "Regulation" "Securities and Exchange"
    "Committee of Sponsoring Organizations"
    "European Com" "California Con" "Defined Con" "Significant Con"
    "Long-Term" "December" "January" "February" "EVs"
    "Zip Cod" "Interest" "Automotive" "Leasing Inc" "Leasing"
    ;; Common truncated words from regex
    "Cor" "Com" "Con" "Inc" "Cos"})

(defn- known-single-word-company?
  "Check if a string is a known single-word company name.
   Uses the dynamic known-entities registry."
  [s]
  (known/known-entity? s))

(defn- looks-like-company-name?
  "Check if a string looks like a company name."
  [s]
  (let [trimmed (str/trim s)
        normalized (normalize-entity-name trimmed)]
    (or
     ;; Known single-word companies always pass
     (known-single-word-company? trimmed)
     ;; Otherwise apply standard rules
     (and (>= (count trimmed) 3)
          (<= (count trimmed) 100)
          ;; Starts with capital letter
          (re-find #"^[A-Z]" trimmed)
          ;; Not a generic term (check both original and normalized)
          (not (generic-terms trimmed))
          (not (generic-terms normalized))
          ;; Not ending with common truncation patterns
          (not (re-find #"\s+(?:Com|Con|Cor|Inc|Cos)$" trimmed))
          ;; Not a phrase that starts with articles/pronouns
          (not (re-find #"^(?:The|Our|These|Such|Unlike|Based|Most|Total|Changes|Internal|Report|Discussion|Forward|Consolidated|Part|Item|Note|Exhibit|Rule|Includes|Reflects|Equity)\s" trimmed))
          ;; Must have at least one lowercase letter (not all caps abbreviations in context)
          (or (> (count trimmed) 5)
              (some company-suffixes (str/split trimmed #"\s+"))
              (re-find #"[a-z]" trimmed))
          ;; Additional: should not be too long (likely a sentence fragment)
          (< (count (str/split trimmed #"\s+")) 12)))))

;; Cache for the known company pattern - invalidated when registry changes
(defonce ^:private known-company-pattern-cache (atom {:count 0 :pattern nil}))

(defn- build-known-company-pattern
  "Build a regex pattern to match known single-word company names in text.
   Uses the dynamic known-entities registry. Cached for performance."
  []
  (let [entities (known/get-all)
        current-count (count entities)
        cached @known-company-pattern-cache]
    ;; Return cached pattern if registry hasn't changed
    (if (and (= current-count (:count cached)) (:pattern cached))
      (:pattern cached)
      ;; Rebuild pattern and cache it
      (let [capitalized (map str/capitalize entities)
            pattern (when (seq capitalized)
                      (re-pattern (str "\\b(" (str/join "|" capitalized) ")\\b")))]
        (reset! known-company-pattern-cache {:count current-count :pattern pattern})
        pattern))))

;; Pre-compiled patterns for entity extraction (avoid rebuilding on every call)
(def ^:private suffix-pattern
  (re-pattern
   (str "([A-Z][A-Za-z0-9&\\-\\s]+?\\s+(?:"
        (str/join "|" company-suffixes)
        "))")))

(def ^:private quoted-pattern
  #"[\"\u201c\u201d]([A-Z][^\"\u201c\u201d]+)[\"\u201c\u201d]")

(def ^:private paren-pattern
  #"([A-Z][A-Za-z\s&\-]+(?:Corporation|Company|Ltd|Inc)[A-Za-z\s\.]*)\s*\([\"\u201c]?([A-Z]{2,})[\"\u201d]?\)")

(def ^:private relationship-pattern
  (re-pattern
   (str "([A-Z][A-Za-z0-9\\s&\\-\\.]+?)\\s+(?:is|are|was|were)?\\s*(?:a|an|our)?\\s*(?:"
        (str/join "|" (map #(str/replace % " " "\\s+") relationship-indicators))
        ")")))

(def ^:private after-prep-pattern
  #"(?:with|between|from|to)\s+([A-Z][A-Za-z0-9\s&\-\.]+?)(?=[\.,;]|\s+(?:and|to|for|in|that|which|is|was|has|have|we|the|a|an|\())")

(defn extract-potential-entities
  "Extract potential entity names from text using multiple strategies.
   Returns a set of potential entity name strings."
  [text]
  (let [;; Strategy 1: Capitalized sequences with company suffixes
        suffix-matches (map second (re-seq suffix-pattern text))

        ;; Strategy 2: Quoted entity names (handles straight and curly quotes)
        quoted-matches (map second (re-seq quoted-pattern text))

        ;; Strategy 3: Parenthetical definitions (e.g., "Shanghai Automotive Industry Corporation (SAIC)")
        paren-matches (mapcat (fn [[_ full abbrev]] [full abbrev])
                              (re-seq paren-pattern text))

        ;; Strategy 4: Names before relationship indicators
        rel-matches (map second (re-seq relationship-pattern text))

        ;; Strategy 5: Names after "with/between/from/to" in relationship context
        after-prep-matches (map second (re-seq after-prep-pattern text))

        ;; Strategy 6: Known company names (single words) - uses cached dynamic registry pattern
        known-pattern (build-known-company-pattern)
        known-matches (when known-pattern
                        (map second (re-seq known-pattern text)))]

    (->> (concat suffix-matches quoted-matches paren-matches rel-matches after-prep-matches known-matches)
         (map str/trim)
         (filter looks-like-company-name?)
         (into #{}))))

;; ============================================================================
;; Entity Normalization
;; ============================================================================

(defn normalize-entity-name
  "Normalize an entity name for matching across filings.
   Returns a canonical form of the name."
  [name]
  (-> name
      str/trim
      ;; Remove common suffixes for matching
      (str/replace #"(?i)\s*(,?\s*(LLC|Inc\.?|Ltd\.?|Co\.?|Corp\.?|Corporation|Company|Limited|LP|LLP|PLC|plc|GmbH|AG|S\.A\.|N\.V\.|B\.V\.))+\s*$" "")
      ;; Normalize whitespace
      (str/replace #"\s+" " ")
      ;; Remove trailing punctuation
      (str/replace #"[,\.\s]+$" "")
      str/trim))

(defn entity-key
  "Generate a normalized key for entity matching.
   This key can be used to match entities across filings."
  [name]
  (-> name
      normalize-entity-name
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

;; ============================================================================
;; Relationship Extraction
;; ============================================================================

(defn- classify-relationship
  "Classify the type of relationship mentioned in text for an entity."
  [text entity-name]
  (let [lower-text (str/lower-case text)
        entity-lower (str/lower-case entity-name)
        ;; Find the context around the entity mention
        idx (str/index-of lower-text entity-lower)
        context (when idx
                  (subs lower-text
                        (max 0 (- idx 100))
                        (min (count lower-text) (+ idx (count entity-lower) 100))))]
    (cond
      (and context (re-find #"(?:supplier|supplied by|supply|supplies)" context)) :supplier
      (and context (re-find #"(?:customer|sold to|sells to|sale to)" context)) :customer
      (and context (re-find #"(?:joint venture|jv\b)" context)) :joint-venture
      (and context (re-find #"(?:partner|partnership|alliance)" context)) :partner
      (and context (re-find #"(?:subsidiary|affiliate|owned by|ownership)" context)) :affiliate
      (and context (re-find #"(?:acquired|acquisition|purchased|bought)" context)) :acquisition
      (and context (re-find #"(?:invest|equity method|stake)" context)) :investment
      :else :mentioned)))

(def ^:private section-patterns
  "Patterns to identify SEC filing sections."
  [;; Item numbers (10-K/10-Q structure)
   [#"(?i)\bitem\s+1a[\.\s:]+risk\s+factors" :risk-factors]
   [#"(?i)\bitem\s+1[\.\s:]+business\b" :business]
   [#"(?i)\bitem\s+7[\.\s:]+management.{1,30}discussion" :md-and-a]
   [#"(?i)\bitem\s+8[\.\s:]+financial\s+statements" :financial-statements]
   [#"(?i)\bitem\s+1[\.\s:]+legal\s+proceedings" :legal-proceedings]
   ;; Section headers
   [#"(?i)\brisk\s+factors\b" :risk-factors]
   [#"(?i)\bmanagement.{1,20}discussion\s+and\s+analysis\b" :md-and-a]
   [#"(?i)\bnotes?\s+to\s+(?:consolidated\s+)?financial\s+statements?\b" :notes-to-financials]
   [#"(?i)\bexhibit\s+index\b" :exhibit-index]
   [#"(?i)\bsignatures?\b" :signatures]
   [#"(?i)\bdescription\s+of\s+business\b" :business]
   [#"(?i)\brelated\s+party\s+transactions?\b" :related-party]
   [#"(?i)\bcommitments\s+and\s+contingencies\b" :commitments]
   [#"(?i)\bsignificant\s+customers?\b" :significant-customers]
   [#"(?i)\bsupply\s+(?:chain|agreement)" :supply-chain]])

(defn- detect-section
  "Detect which section of the filing a text block belongs to.
   Returns a keyword like :risk-factors, :md-and-a, :notes-to-financials, etc."
  [text]
  (let [lower-text (str/lower-case text)]
    (or (some (fn [[pattern section]]
                (when (re-find pattern lower-text)
                  section))
              section-patterns)
        :unknown)))

(def ^:private materiality-indicators
  "Words/phrases that indicate materiality or significance."
  #{"significant" "material" "substantial" "principal" "major" "primary"
    "largest" "key" "critical" "important" "essential" "meaningful"
    "considerable" "notable" "prominent"})

(defn- extract-materiality-indicators
  "Extract materiality indicator words near an entity mention.
   Returns a set of indicator words found."
  [text entity-name]
  (let [entity-lower (str/lower-case entity-name)
        lower-text (str/lower-case text)
        idx (str/index-of lower-text entity-lower)]
    (when idx
      (let [;; Look within 100 chars before and after
            context (subs lower-text
                          (max 0 (- idx 100))
                          (min (count lower-text) (+ idx (count entity-lower) 100)))
            words (set (str/split context #"\W+"))]
        (set/intersection words materiality-indicators)))))

(defn- extract-monetary-values
  "Extract monetary values mentioned near an entity.
   Returns a vector of maps with :amount and :unit."
  [text entity-name]
  (let [entity-lower (str/lower-case entity-name)
        idx (str/index-of (str/lower-case text) entity-lower)]
    (when idx
      (let [;; Look within 200 chars before and after for monetary values
            context (subs text
                          (max 0 (- idx 200))
                          (min (count text) (+ idx (count entity-lower) 200)))
            ;; Pattern for monetary values: $X, $X.X, $X million/billion, X million/billion
            patterns [;; $X.X billion/million
                      #"\$\s*([\d,]+(?:\.\d+)?)\s*(billion|million|thousand|B|M|K)?"
                      ;; X billion/million (without $)
                      #"([\d,]+(?:\.\d+)?)\s+(billion|million|thousand)\s+(?:dollars?|USD)?"]]
        (->> patterns
             (mapcat #(re-seq % context))
             (map (fn [match]
                    (let [[_ amount unit] match
                          clean-amount (str/replace (or amount "") "," "")
                          parsed (try (Double/parseDouble clean-amount) (catch Exception _ nil))]
                      (when parsed
                        {:amount parsed
                         :unit (cond
                                 (nil? unit) :dollars
                                 (re-find #"(?i)^b" unit) :billions
                                 (re-find #"(?i)^m" unit) :millions
                                 (re-find #"(?i)^[tk]" unit) :thousands
                                 :else :dollars)
                         :raw (first match)}))))
             (remove nil?)
             vec)))))

(defn extract-entity-relationships
  "Extract entities and their relationships from a paragraph.
   Returns a sequence of maps with :entity, :normalized, :key, :relationship, :context,
   :section, :materiality-indicators, :monetary-values."
  [text section]
  (let [entities (extract-potential-entities text)]
    (for [entity entities]
      {:entity entity
       :normalized (normalize-entity-name entity)
       :key (entity-key entity)
       :relationship (classify-relationship text entity)
       :section section
       :materiality-indicators (extract-materiality-indicators text entity)
       :monetary-values (extract-monetary-values text entity)
       :context (let [idx (str/index-of text entity)]
                  (when idx
                    (subs text
                          (max 0 (- idx 50))
                          (min (count text) (+ idx (count entity) 150)))))})))

;; ============================================================================
;; Filing Analysis
;; ============================================================================

(defn- extract-prose-paragraphs
  "Extract prose paragraphs from a document."
  [^Document doc]
  (->> (.select doc "div")
       (map #(.text ^Element %))
       (remove str/blank?)
       (filter sec/looks-like-prose?)
       distinct
       sec/remove-nested-duplicates))

(def ^:private common-words
  "Common English words that should not match SEC filers on their own."
  #{"california" "israel" "trade" "forward" "when" "lease" "trust"
    "first" "second" "third" "new" "one" "two" "three" "four" "five"
    "general" "national" "american" "united" "international" "global"
    "north" "south" "east" "west" "central"
    "financial" "capital" "investment" "credit" "bank"
    "energy" "power" "oil" "gas" "electric"
    "technology" "systems" "services" "solutions" "group"
    "health" "medical" "life" "bio"
    "real" "property" "land" "home"
    "food" "water" "air"
    "net" "web" "digital" "data" "cloud"
    "gold" "silver" "iron" "steel"
    "fixed" "december" "january" "plans"})

;; known-company-names is now loaded from the dynamic registry (scr.known-entities)

(defn- looks-like-company-reference?
  "Check if the extracted entity looks like an intentional company reference.
   Filters out common words that happen to match SEC company names."
  [entity normalized]
  (let [lower-entity (str/lower-case entity)
        lower-normalized (str/lower-case normalized)
        word-count (count (str/split entity #"\s+"))]
    (and
     ;; Not just a common word
     (not (common-words lower-normalized))
     (not (common-words lower-entity))
     ;; Must be either:
     (or
      ;; A known company name (from dynamic registry)
      (known/known-entity? lower-normalized)
      (known/known-entity? lower-entity)
      ;; At least 2 words
      (>= word-count 2)
      ;; A ticker-like pattern (all caps 2-5 chars)
      (re-matches #"[A-Z]{2,5}" entity)
      ;; Contains company suffix
      (re-find #"(?i)\b(inc|corp|co|ltd|llc|plc|n\.v\.|s\.a\.|gmbh|ag)\b" entity)))))

(defn- resolve-to-sec-filer
  "Try to resolve an entity to a known SEC filer.
   Returns nil if not found, or the SEC filer info with original context and metadata."
  [entity-info]
  (let [{:keys [entity normalized relationship context section
                materiality-indicators monetary-values]} entity-info]
    ;; First check if this looks like an intentional company reference
    (when (looks-like-company-reference? entity normalized)
      ;; Try to find a match in the SEC registry
      (when-let [sec-match (filers/resolve-entity normalized)]
        {:entity entity
         :sec-name (:name sec-match)
         :cik (:cik sec-match)
         :ticker (:ticker sec-match)
         :relationship relationship
         :section section
         :materiality-indicators materiality-indicators
         :monetary-values monetary-values
         :context context}))))

(defn- aggregate-relationship-counts
  "Count mentions per relationship type."
  [rels]
  (->> rels
       (map :relationship)
       frequencies))

(defn- aggregate-sections
  "Collect unique sections where entity was mentioned."
  [rels]
  (->> rels
       (map :section)
       (remove nil?)
       distinct
       vec))

(defn- aggregate-materiality
  "Collect all materiality indicators across mentions."
  [rels]
  (->> rels
       (mapcat :materiality-indicators)
       (remove nil?)
       set))

(defn- aggregate-monetary-values
  "Collect all monetary values across mentions."
  [rels]
  (->> rels
       (mapcat :monetary-values)
       (remove nil?)
       distinct
       vec))

(defn analyze-filing
  "Analyze a filing and extract all entity relationships.
   Options:
     :sec-filers-only - if true, only include entities that are SEC filers (default: false)
   Returns a map with filing metadata and extracted entities."
  ([file-path] (analyze-filing file-path {}))
  ([file-path {:keys [sec-filers-only] :or {sec-filers-only false}}]
   (let [{:keys [cik name doc filing-date filing-type]} (extract-filing-metadata file-path)
         paragraphs (extract-prose-paragraphs doc)
         ;; Extract entities from each paragraph with section detection
         raw-entities (->> paragraphs
                           (mapcat (fn [para]
                                     (let [section (detect-section para)]
                                       (extract-entity-relationships para section))))
                           ;; Remove self-references (filer mentioning itself)
                           (remove #(str/includes? (str/lower-case (:normalized %))
                                                   (str/lower-case (or name "")))))
         ;; If sec-filers-only, resolve and filter to SEC filers
         processed-entities (if sec-filers-only
                              (->> raw-entities
                                   (map resolve-to-sec-filer)
                                   (remove nil?)
                                   ;; Group by CIK (canonical identifier)
                                   (group-by :cik)
                                   (map (fn [[entity-cik rels]]
                                          (let [first-rel (first rels)]
                                            {:entity (:entity first-rel)
                                             :sec-name (:sec-name first-rel)
                                             :cik entity-cik
                                             :ticker (:ticker first-rel)
                                             :relationships (distinct (map :relationship rels))
                                             :relationship-counts (aggregate-relationship-counts rels)
                                             :mention-count (count rels)
                                             :sections (aggregate-sections rels)
                                             :materiality-indicators (aggregate-materiality rels)
                                             :monetary-values (aggregate-monetary-values rels)
                                             ;; Collect ALL contexts, not just sample
                                             :contexts (->> rels
                                                            (map :context)
                                                            (remove nil?)
                                                            distinct
                                                            vec)
                                             ;; Keep sample-context for backwards compatibility
                                             :sample-context (:context first-rel)})))
                                   ;; Remove self-references by CIK
                                   (remove #(= (:cik %) cik))
                                   (sort-by :mention-count >))
                              ;; Original behavior - all entities
                              (->> raw-entities
                                   (group-by :key)
                                   (map (fn [[k rels]]
                                          (let [first-rel (first rels)]
                                            {:entity (:entity first-rel)
                                             :normalized (:normalized first-rel)
                                             :key k
                                             :relationships (distinct (map :relationship rels))
                                             :relationship-counts (aggregate-relationship-counts rels)
                                             :mention-count (count rels)
                                             :sections (aggregate-sections rels)
                                             :materiality-indicators (aggregate-materiality rels)
                                             :monetary-values (aggregate-monetary-values rels)
                                             ;; Collect ALL contexts, not just sample
                                             :contexts (->> rels
                                                            (map :context)
                                                            (remove nil?)
                                                            distinct
                                                            vec)
                                             ;; Keep sample-context for backwards compatibility
                                             :sample-context (:context first-rel)})))
                                   (sort-by :mention-count >)))]
     {:cik cik
      :filer-name name
      :file-path file-path
      :filing-date filing-date
      :filing-type filing-type
      :sec-filers-only sec-filers-only
      :entity-count (count processed-entities)
      :entities processed-entities})))

;; ============================================================================
;; Cross-Filing Matching
;; ============================================================================

(defn find-common-entities
  "Find entities that appear in multiple filings.
   Takes a sequence of analyzed filings.
   For SEC-filer-only analyses, uses CIK as the key for matching.
   Excludes self-references (a filer mentioning itself).
   Returns entities grouped by key with their appearances."
  [analyzed-filings]
  (let [sec-filers-only? (:sec-filers-only (first analyzed-filings))
        ;; Build index using appropriate key (CIK for SEC filers, name-key otherwise)
        ;; Exclude self-references where entity CIK matches filer CIK
        entity-index (reduce
                      (fn [idx {:keys [cik filer-name entities]}]
                        (reduce (fn [i entity-info]
                                  (let [;; Use CIK if available (SEC filer), otherwise use key
                                        entity-key (or (:cik entity-info)
                                                       (:key entity-info))
                                        ;; Normalize CIKs for comparison (strip leading zeros)
                                        filer-cik-normalized (when cik (str/replace cik #"^0+" ""))
                                        entity-cik-normalized (when (:cik entity-info)
                                                                (str/replace (str (:cik entity-info)) #"^0+" ""))]
                                    ;; Skip self-references
                                    (if (and entity-cik-normalized
                                             filer-cik-normalized
                                             (= entity-cik-normalized filer-cik-normalized))
                                      i
                                      (update i entity-key (fnil conj [])
                                              {:filer-cik cik
                                               :filer-name filer-name
                                               :entity-info entity-info}))))
                                idx
                                entities))
                      {}
                      analyzed-filings)]
    ;; Filter to entities appearing in multiple filings
    (->> entity-index
         (filter (fn [[_ appearances]] (> (count appearances) 1)))
         (sort-by (fn [[_ appearances]] (- (count appearances))))
         (map (fn [[entity-key appearances]]
                (let [first-entity (:entity-info (first appearances))]
                  {:entity-key entity-key
                   :entity-name (or (:sec-name first-entity)
                                    (:normalized first-entity))
                   :ticker (:ticker first-entity)
                   :appearance-count (count appearances)
                   :filings (map (fn [{:keys [filer-cik filer-name entity-info]}]
                                  {:cik filer-cik
                                   :filer filer-name
                                   :relationships (:relationships entity-info)
                                   :mentions (:mention-count entity-info)})
                                appearances)}))))))

(defn find-bidirectional-relationships
  "Find pairs of companies that mention each other in their filings.
   This identifies mutual disclosure relationships (e.g., Ford mentions Rivian,
   Rivian mentions Ford).
   Returns a sequence of relationship maps."
  [analyzed-filings]
  (let [;; Build a map of filer-CIK -> set of mentioned entity CIKs
        mentions-map (reduce
                      (fn [m {:keys [cik entities]}]
                        (let [filer-cik (str/replace (or cik "") #"^0+" "")
                              mentioned-ciks (->> entities
                                                  (map :cik)
                                                  (remove nil?)
                                                  (map #(str/replace (str %) #"^0+" ""))
                                                  ;; Exclude self
                                                  (remove #(= % filer-cik))
                                                  set)]
                          (assoc m filer-cik {:filer-name (:filer-name (first (filter #(= (:cik %) cik) analyzed-filings)))
                                              :mentions mentioned-ciks
                                              :entities entities})))
                      {}
                      analyzed-filings)
        ;; Find bidirectional relationships
        filer-ciks (keys mentions-map)]
    (->> (for [cik-a filer-ciks
               cik-b filer-ciks
               :when (and (not= cik-a cik-b)
                          (< (compare cik-a cik-b) 0)  ;; Avoid duplicates
                          (contains? (get-in mentions-map [cik-a :mentions]) cik-b)
                          (contains? (get-in mentions-map [cik-b :mentions]) cik-a))]
           (let [a-info (get mentions-map cik-a)
                 b-info (get mentions-map cik-b)
                 a-mentions-b (first (filter #(= (str/replace (str (:cik %)) #"^0+" "") cik-b)
                                             (:entities a-info)))
                 b-mentions-a (first (filter #(= (str/replace (str (:cik %)) #"^0+" "") cik-a)
                                             (:entities b-info)))]
             {:company-a {:cik cik-a
                          :name (get-in mentions-map [cik-a :filer-name])
                          :mentions-b (:mention-count a-mentions-b)
                          :relationships-to-b (:relationships a-mentions-b)
                          :relationship-counts (:relationship-counts a-mentions-b)
                          :sections (:sections a-mentions-b)
                          :materiality-indicators (:materiality-indicators a-mentions-b)
                          :monetary-values (:monetary-values a-mentions-b)
                          ;; Include ALL contexts
                          :contexts (:contexts a-mentions-b)
                          ;; Keep single context for backwards compatibility
                          :context (:sample-context a-mentions-b)}
              :company-b {:cik cik-b
                          :name (get-in mentions-map [cik-b :filer-name])
                          :mentions-a (:mention-count b-mentions-a)
                          :relationships-to-a (:relationships b-mentions-a)
                          :relationship-counts (:relationship-counts b-mentions-a)
                          :sections (:sections b-mentions-a)
                          :materiality-indicators (:materiality-indicators b-mentions-a)
                          :monetary-values (:monetary-values b-mentions-a)
                          ;; Include ALL contexts
                          :contexts (:contexts b-mentions-a)
                          ;; Keep single context for backwards compatibility
                          :context (:sample-context b-mentions-a)}}))
         (sort-by #(- (+ (get-in % [:company-a :mentions-b] 0)
                         (get-in % [:company-b :mentions-a] 0)))))))

(defn- wrap-text
  "Wrap text at specified width."
  [text width]
  (when text
    (->> (str/split text #"\s+")
         (reduce (fn [[lines current] word]
                   (if (> (+ (count current) (count word) 1) width)
                     [(conj lines current) word]
                     [lines (if (empty? current) word (str current " " word))]))
                 [[] ""])
         ((fn [[lines last]] (conj lines last)))
         (str/join "\n    "))))

(defn print-bidirectional-relationships
  "Print bidirectional relationships between companies.
   Options:
     :show-context - if true, display context snippets (default: true)
     :all-contexts - if true, show all contexts; if false, show only first (default: true)"
  [relationships & {:keys [show-context all-contexts] :or {show-context true all-contexts true}}]
  (println (str "\n" (apply str (repeat 70 "=")) "\n"))
  (println "Bidirectional Relationships (Companies That Mention Each Other)")
  (println (apply str (repeat 70 "-")))
  (println (str "Found " (count relationships) " mutual disclosure relationships\n"))
  (doseq [{:keys [company-a company-b]} relationships]
    (println (str "\n" (apply str (repeat 70 "-"))))
    (println (str (:name company-a) " <-> " (:name company-b)))
    (println (apply str (repeat 70 "-")))
    (println (format "\n  %s mentions %s: %d times as %s"
                     (:name company-a)
                     (:name company-b)
                     (:mentions-b company-a)
                     (str/join ", " (map name (:relationships-to-b company-a)))))
    (when show-context
      (let [contexts (if all-contexts
                       (:contexts company-a)
                       (when (:context company-a) [(:context company-a)]))]
        (when (seq contexts)
          (println (format "  Contexts (%d):" (count contexts)))
          (doseq [[idx ctx] (map-indexed vector contexts)]
            (println (format "\n    [%d] %s" (inc idx) (wrap-text ctx 66)))))))
    (println (format "\n  %s mentions %s: %d times as %s"
                     (:name company-b)
                     (:name company-a)
                     (:mentions-a company-b)
                     (str/join ", " (map name (:relationships-to-a company-b)))))
    (when show-context
      (let [contexts (if all-contexts
                       (:contexts company-b)
                       (when (:context company-b) [(:context company-b)]))]
        (when (seq contexts)
          (println (format "  Contexts (%d):" (count contexts)))
          (doseq [[idx ctx] (map-indexed vector contexts)]
            (println (format "\n    [%d] %s" (inc idx) (wrap-text ctx 66)))))))))

;; ============================================================================
;; REPL Convenience Functions
;; ============================================================================

(defn print-filing-summary
  "Print a summary of entities found in a filing."
  [{:keys [cik filer-name file-path entity-count entities sec-filers-only]} & {:keys [limit] :or {limit 20}}]
  (println (str "\n" (apply str (repeat 70 "=")) "\n"))
  (println "Filing Analysis Summary")
  (println (apply str (repeat 70 "-")))
  (println "CIK:        " cik)
  (println "Filer:      " filer-name)
  (println "File:       " file-path)
  (println "Entities:   " entity-count (if sec-filers-only "(SEC filers only)" ""))
  (println (apply str (repeat 70 "-")))
  (println "\nTop entities mentioned:\n")
  (doseq [entity (take limit entities)]
    (let [display-name (or (:sec-name entity) (:normalized entity))
          ticker (:ticker entity)
          entity-cik (:cik entity)
          mention-count (:mention-count entity)
          relationships (:relationships entity)]
      (if sec-filers-only
        (println (format "  %-30s %-6s CIK:%-10s [%d] %s"
                         (if (> (count display-name) 28)
                           (str (subs display-name 0 25) "...")
                           display-name)
                         (or ticker "")
                         (or entity-cik "")
                         mention-count
                         (str/join ", " (map name relationships))))
        (println (format "  %-40s [%d mentions] %s"
                         (if (> (count display-name) 38)
                           (str (subs display-name 0 35) "...")
                           display-name)
                         mention-count
                         (str/join ", " (map name relationships)))))))
  (when (> entity-count limit)
    (println (str "\n  ... and " (- entity-count limit) " more entities"))))

(defn print-common-entities
  "Print entities that appear across multiple filings."
  [common-entities & {:keys [limit] :or {limit 20}}]
  (println (str "\n" (apply str (repeat 70 "=")) "\n"))
  (println "Cross-Filing Entity Matches")
  (println (apply str (repeat 70 "-")))
  (println (str "Found " (count common-entities) " entities appearing in multiple filings\n"))
  (doseq [{:keys [entity-key entity-name ticker appearance-count filings]} (take limit common-entities)]
    (println (str "\n" entity-name
                  (when ticker (str " (" ticker ")"))
                  " [CIK: " entity-key "]"))
    (println (str "  Appears in " appearance-count " filings:"))
    (doseq [{:keys [cik filer relationships mentions]} filings]
      (println (format "    - %s (CIK %s): %d mentions as %s"
                       filer cik mentions
                       (str/join ", " (map name relationships)))))))

(defn analyze-filing-safe
  "Analyze a filing with error handling.
   Returns {:success true :result analysis} on success,
   or {:success false :error error-map :file file-path} on failure."
  [file-path options]
  (try
    {:success true
     :result (analyze-filing file-path options)
     :file file-path}
    (catch Exception e
      {:success false
       :file file-path
       :error {:type (type e)
               :message (.getMessage e)
               :cause (when-let [c (.getCause e)] (.getMessage c))}})))

(defn print-error-summary
  "Print a summary of files that failed to process."
  [errors]
  (when (seq errors)
    (println (str "\n" (apply str (repeat 70 "=")) "\n"))
    (println "ERRORS: Failed to process" (count errors) "file(s)")
    (println (apply str (repeat 70 "-")))
    (doseq [{:keys [file error]} errors]
      (println (str "\n  File: " file))
      (println (str "  Error: " (:message error)))
      (when (:cause error)
        (println (str "  Cause: " (:cause error)))))
    (println)))

(defn run-analysis
  "Analyze one or more filings and show results.
   Options:
     :files - sequence of file paths (default: all filings in resources/filings)
     :show-common - if true, show cross-filing matches (default: true)
     :show-bidirectional - if true, show bidirectional relationships (default: true)
     :sec-filers-only - if true, only show SEC filers (default: true)
     :continue-on-error - if true, continue processing after errors (default: true)
     :show-errors - if true, print error summary (default: true)
     :parallel - if true, process files in parallel (default: true for >2 files)"
  [& {:keys [files show-common show-bidirectional sec-filers-only continue-on-error show-errors parallel]
      :or {files ["resources/filings/f-20241231.html"
                  "resources/filings/gm-20241231.html"
                  "resources/filings/tsla-20241231.html"
                  "resources/filings/rivn-20241231.html"]
           show-common true
           show-bidirectional true
           sec-filers-only true
           continue-on-error true
           show-errors true
           parallel nil}}]
  (let [use-parallel (if (nil? parallel)
                       (> (count files) 2)  ; Auto-enable for >2 files
                       parallel)]
    (println "Analyzing" (count files) "filings..."
             (if sec-filers-only "(SEC filers only)" "(all entities)")
             (when use-parallel "(parallel)") "\n")
    (let [;; Analyze all files, capturing successes and failures
          ;; Use pmap for parallel processing when enabled
          map-fn (if use-parallel pmap mapv)
          results (vec (map-fn #(analyze-filing-safe % {:sec-filers-only sec-filers-only}) files))
          successes (filter :success results)
          failures (remove :success results)
          analyses (mapv :result successes)
          common (when (> (count analyses) 1) (find-common-entities analyses))
          bidirectional (when (> (count analyses) 1) (find-bidirectional-relationships analyses))]
      ;; Print individual summaries for successful analyses
      (doseq [analysis analyses]
        (print-filing-summary analysis))
      ;; Print error summary
      (when (and show-errors (seq failures))
        (print-error-summary failures))
      ;; Print cross-filing matches
      (when (and show-common (> (count analyses) 1))
        (print-common-entities common))
      ;; Print bidirectional relationships
      (when (and show-bidirectional (> (count analyses) 1) (seq bidirectional))
        (print-bidirectional-relationships bidirectional))
      ;; Return data for further processing
      {:filings analyses
       :common-entities common
       :bidirectional-relationships bidirectional
       :errors (vec failures)
       :stats {:total (count files)
               :successful (count successes)
               :failed (count failures)}})))

(defn export-error-log
  "Export errors to a log file for later review.
   Options:
     :output-file - path to output file (default: resources/contexts/errors.log)
     :append - if true, append to existing file (default: false)"
  [errors & {:keys [output-file append]
             :or {output-file "resources/contexts/errors.log"
                  append false}}]
  (when (seq errors)
    (let [timestamp (str (java.time.LocalDateTime/now))
          header (str "\n" (apply str (repeat 70 "=")) "\n"
                      "Error Log - " timestamp "\n"
                      (apply str (repeat 70 "=")) "\n\n")
          entries (for [{:keys [file error]} errors]
                    (str "File: " file "\n"
                         "Error Type: " (:type error) "\n"
                         "Message: " (:message error) "\n"
                         (when (:cause error)
                           (str "Cause: " (:cause error) "\n"))
                         "\n"))
          content (str header (str/join "\n" entries))]
      (if append
        (spit output-file content :append true)
        (spit output-file content))
      (println "Error log written to:" output-file)
      {:file output-file
       :error-count (count errors)})))

;; ============================================================================
;; Known Entity Management (delegated to scr.known-entities)
;; ============================================================================

(defn add-known-entity!
  "Add a company name to the known entities registry.
   The name will be recognized in future entity extraction."
  [name]
  (known/add! name))

(defn add-known-entities!
  "Add multiple company names to the known entities registry.
   Returns count of new entities added."
  [names]
  (known/add-all! names))

(defn save-known-entities!
  "Save the current known entities registry to disk.
   This persists any entities added during this session."
  []
  (let [count (known/save!)]
    (println "Saved" count "known entities to resources/sec/known_entities.edn")
    count))

(defn known-entities-stats
  "Get statistics about the known entities registry."
  []
  (known/stats))

(defn list-discovered-entities
  "List entities that were discovered during this session."
  []
  (known/list-discovered))

(defn reload-known-entities!
  "Reload the known entities registry from disk."
  []
  (known/reload!))

;; ============================================================================
;; Context Export to Files
;; ============================================================================

(def ^:private contexts-output-dir "resources/contexts")

(defn- sanitize-filename
  "Sanitize a string for use as a filename."
  [s]
  (let [cleaned (-> (or s "unknown")
                    str/lower-case
                    (str/replace #"[^a-z0-9]+" "-")
                    (str/replace #"^-|-$" ""))]
    (subs cleaned 0 (min 50 (count cleaned)))))

(defn- format-context-file
  "Format contexts for a single entity mention into file content."
  [filer-name filer-cik entity-name entity-cik relationships contexts]
  (let [header (str "=" (apply str (repeat 70 "=")) "\n"
                    "FILER: " filer-name " (CIK: " filer-cik ")\n"
                    "MENTIONS: " entity-name " (CIK: " entity-cik ")\n"
                    "RELATIONSHIPS: " (str/join ", " (map name relationships)) "\n"
                    "CONTEXT COUNT: " (count contexts) "\n"
                    "=" (apply str (repeat 70 "=")) "\n\n")]
    (str header
         (str/join "\n\n"
                   (map-indexed
                    (fn [idx ctx]
                      (str "--- Context " (inc idx) " ---\n\n" ctx))
                    contexts))
         "\n")))

(defn- extract-filing-name
  "Extract a short name from file path for use in filenames."
  [file-path]
  (-> file-path
      (str/replace #".*/" "")
      (str/replace #"\.html?$" "")))

(defn export-entity-contexts
  "Export all entity contexts from analyzed filings to text files.
   Creates one file per entity mention per filing.
   Files are named: {filing}-{entity}-{relationships}.txt
   Returns count of files written."
  [analyzed-filings & {:keys [output-dir] :or {output-dir contexts-output-dir}}]
  (let [_ (io/make-parents (str output-dir "/dummy.txt"))]
    (reduce
     (fn [count {:keys [cik filer-name file-path entities]}]
       (let [filing-name (extract-filing-name file-path)]
         (reduce
          (fn [c entity]
            (let [entity-name (or (:sec-name entity) (:normalized entity))
                  entity-cik (or (:cik entity) "unknown")
                  relationships (:relationships entity)
                  contexts (:contexts entity)]
              (if (seq contexts)
                (let [rel-str (str/join "-" (map name relationships))
                      filename (str output-dir "/"
                                    filing-name "_mentions_"
                                    (sanitize-filename entity-name) "_"
                                    (sanitize-filename rel-str) ".txt")
                      content (format-context-file filer-name cik
                                                   entity-name entity-cik
                                                   relationships contexts)]
                  (spit filename content)
                  (inc c))
                c)))
          count
          ;; Exclude self-references
          (remove #(= (str/replace (str (:cik %)) #"^0+" "")
                      (str/replace (str cik) #"^0+" ""))
                  entities))))
     0
     analyzed-filings)))

(defn export-bidirectional-contexts
  "Export contexts from bidirectional relationships to text files.
   Creates one file per direction of each bidirectional relationship.
   Files are named: {company-a}_mentions_{company-b}_{relationships}.txt
   Returns count of files written."
  [bidirectional-relationships & {:keys [output-dir] :or {output-dir contexts-output-dir}}]
  (let [_ (io/make-parents (str output-dir "/dummy.txt"))]
    (reduce
     (fn [count {:keys [company-a company-b]}]
       (let [;; File for A mentioning B
             a-filename (str output-dir "/"
                             (sanitize-filename (:name company-a)) "_mentions_"
                             (sanitize-filename (:name company-b)) "_"
                             (sanitize-filename (str/join "-" (map name (:relationships-to-b company-a)))) ".txt")
             a-content (format-context-file (:name company-a) (:cik company-a)
                                            (:name company-b) (:cik company-b)
                                            (:relationships-to-b company-a)
                                            (:contexts company-a))
             ;; File for B mentioning A
             b-filename (str output-dir "/"
                             (sanitize-filename (:name company-b)) "_mentions_"
                             (sanitize-filename (:name company-a)) "_"
                             (sanitize-filename (str/join "-" (map name (:relationships-to-a company-b)))) ".txt")
             b-content (format-context-file (:name company-b) (:cik company-b)
                                            (:name company-a) (:cik company-a)
                                            (:relationships-to-a company-b)
                                            (:contexts company-b))]
         (spit a-filename a-content)
         (spit b-filename b-content)
         (+ count 2)))
     0
     bidirectional-relationships)))

(defn- escape-csv-field
  "Escape a field for CSV output."
  [s]
  (let [field (str s)]
    (if (or (str/includes? field ",")
            (str/includes? field "\"")
            (str/includes? field "\n"))
      (str "\"" (str/replace field "\"" "\"\"") "\"")
      field)))

(defn- format-relationship-counts
  "Format relationship counts as a string like 'customer:5,supplier:2'."
  [rel-counts]
  (when (seq rel-counts)
    (->> rel-counts
         (map (fn [[k v]] (str (name k) ":" v)))
         (str/join ","))))

(defn- format-monetary-values
  "Format monetary values as a string."
  [monetary-values]
  (when (seq monetary-values)
    (->> monetary-values
         (map (fn [{:keys [amount unit raw]}]
                (or raw (str amount " " (name unit)))))
         (str/join "; "))))

(defn- build-index-entries
  "Build index entries for all entity contexts.
   Returns a sequence of maps with file metadata."
  [analyzed-filings output-dir]
  (for [{:keys [cik filer-name file-path filing-date filing-type entities]} analyzed-filings
        entity entities
        :let [entity-cik (or (:cik entity) "unknown")
              ;; Skip self-references
              filer-cik-norm (str/replace (str cik) #"^0+" "")
              entity-cik-norm (str/replace (str entity-cik) #"^0+" "")]
        :when (and (seq (:contexts entity))
                   (not= filer-cik-norm entity-cik-norm))]
    (let [entity-name (or (:sec-name entity) (:normalized entity))
          relationships (:relationships entity)
          filing-name (extract-filing-name file-path)
          rel-str (str/join "-" (map name relationships))
          filename (str filing-name "_mentions_"
                        (sanitize-filename entity-name) "_"
                        (sanitize-filename rel-str) ".txt")]
      {:filename filename
       :filer-name filer-name
       :filer-cik cik
       :filing-date (or filing-date "")
       :filing-type (or filing-type "")
       :entity-name entity-name
       :entity-cik entity-cik
       :entity-ticker (or (:ticker entity) "")
       :relationships (str/join "," (map name relationships))
       :relationship-counts (format-relationship-counts (:relationship-counts entity))
       :context-count (count (:contexts entity))
       :sections (str/join "," (map name (or (:sections entity) [])))
       :materiality-indicators (str/join "," (or (:materiality-indicators entity) []))
       :monetary-values (format-monetary-values (:monetary-values entity))
       :source-filing file-path
       :bidirectional false})))

(defn- build-bidirectional-index-entries
  "Build index entries for bidirectional relationship contexts.
   Returns a sequence of maps with file metadata."
  [bidirectional-relationships analyzed-filings output-dir]
  (for [{:keys [company-a company-b]} bidirectional-relationships
        [filer mentionee contexts rels rel-counts sections materiality monetary]
        [;; A mentions B
         [company-a company-b
          (:contexts company-a)
          (:relationships-to-b company-a)
          (:relationship-counts company-a)
          (:sections company-a)
          (:materiality-indicators company-a)
          (:monetary-values company-a)]
         ;; B mentions A
         [company-b company-a
          (:contexts company-b)
          (:relationships-to-a company-b)
          (:relationship-counts company-b)
          (:sections company-b)
          (:materiality-indicators company-b)
          (:monetary-values company-b)]]]
    (let [rel-str (str/join "-" (map name rels))
          filename (str (sanitize-filename (:name filer)) "_mentions_"
                        (sanitize-filename (:name mentionee)) "_"
                        (sanitize-filename rel-str) ".txt")
          ;; Find the filing for this filer to get date/type
          filer-cik-norm (str/replace (str (:cik filer)) #"^0+" "")
          filing-info (first (filter #(= (str/replace (str (:cik %)) #"^0+" "") filer-cik-norm)
                                     analyzed-filings))]
      {:filename filename
       :filer-name (:name filer)
       :filer-cik (:cik filer)
       :filing-date (or (:filing-date filing-info) "")
       :filing-type (or (:filing-type filing-info) "")
       :entity-name (:name mentionee)
       :entity-cik (:cik mentionee)
       :entity-ticker ""
       :relationships (str/join "," (map name rels))
       :relationship-counts (format-relationship-counts rel-counts)
       :context-count (count contexts)
       :sections (str/join "," (map name (or sections [])))
       :materiality-indicators (str/join "," (or materiality []))
       :monetary-values (format-monetary-values monetary)
       :source-filing (or (:file-path filing-info) "")
       :bidirectional true})))

(defn- write-index-csv
  "Write the index CSV file."
  [entries output-dir]
  (let [headers ["filename" "filer_name" "filer_cik" "filing_date" "filing_type"
                 "entity_name" "entity_cik" "entity_ticker"
                 "relationships" "relationship_counts" "context_count"
                 "sections" "materiality_indicators" "monetary_values"
                 "source_filing" "bidirectional"]
        csv-path (str output-dir "/index.csv")
        header-line (str/join "," headers)
        data-lines (for [e entries]
                     (str/join "," (map escape-csv-field
                                        [(:filename e)
                                         (:filer-name e)
                                         (:filer-cik e)
                                         (:filing-date e)
                                         (:filing-type e)
                                         (:entity-name e)
                                         (:entity-cik e)
                                         (:entity-ticker e)
                                         (:relationships e)
                                         (:relationship-counts e)
                                         (:context-count e)
                                         (:sections e)
                                         (:materiality-indicators e)
                                         (:monetary-values e)
                                         (:source-filing e)
                                         (:bidirectional e)])))]
    (spit csv-path (str header-line "\n" (str/join "\n" data-lines) "\n"))
    csv-path))

(defn export-all-contexts
  "Export all contexts from an analysis to text files.
   Options:
     :output-dir - directory for output files (default: resources/contexts)
     :bidirectional-only - if true, only export bidirectional relationships (default: false)
   Returns a map with counts of files written."
  [analysis-result & {:keys [output-dir bidirectional-only]
                      :or {output-dir contexts-output-dir
                           bidirectional-only false}}]
  (let [bidir-count (export-bidirectional-contexts
                     (:bidirectional-relationships analysis-result)
                     :output-dir output-dir)
        entity-count (if bidirectional-only
                       0
                       (export-entity-contexts
                        (:filings analysis-result)
                        :output-dir output-dir))
        ;; Build and write index
        bidir-entries (build-bidirectional-index-entries
                       (:bidirectional-relationships analysis-result)
                       (:filings analysis-result)
                       output-dir)
        entity-entries (if bidirectional-only
                         []
                         (build-index-entries (:filings analysis-result) output-dir))
        all-entries (concat bidir-entries entity-entries)
        index-path (write-index-csv all-entries output-dir)]
    (println (str "Exported " bidir-count " bidirectional context files to " output-dir))
    (when-not bidirectional-only
      (println (str "Exported " entity-count " entity context files to " output-dir)))
    (println (str "Created index: " index-path))
    {:bidirectional-files bidir-count
     :entity-files entity-count
     :total (+ bidir-count entity-count)
     :index-path index-path
     :output-dir output-dir}))
