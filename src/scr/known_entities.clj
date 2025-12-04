(ns scr.known-entities
  "Dynamic registry of known company names for entity extraction.

   Loads initial entities from resources/sec/known_entities.edn and can
   dynamically discover and persist new entities during analysis."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ^:private entities-file "sec/known_entities.edn")
(def ^:private entities-file-path "resources/sec/known_entities.edn")

;; ============================================================================
;; Registry State
;; ============================================================================

;; Atom containing the set of known entity names (lowercase)
(defonce ^:private registry (atom #{}))

;; Atom containing newly discovered entities during this session
(defonce ^:private discovered (atom #{}))

;; ============================================================================
;; Loading and Saving
;; ============================================================================

(defn- load-from-resource
  "Load entities from the classpath resource."
  []
  (if-let [resource (io/resource entities-file)]
    (try
      (edn/read-string (slurp resource))
      (catch Exception e
        (println "Warning: Could not load known entities from resource:" (.getMessage e))
        #{}))
    (do
      (println "Warning: Known entities resource not found:" entities-file)
      #{})))

(defn- load-from-file
  "Load entities from the file system (for persistence updates)."
  []
  (let [f (io/file entities-file-path)]
    (if (.exists f)
      (try
        (edn/read-string (slurp f))
        (catch Exception e
          (println "Warning: Could not load known entities from file:" (.getMessage e))
          #{}))
      #{})))

(defn load!
  "Load known entities from the data file. Called automatically on first use."
  []
  (let [entities (load-from-resource)]
    (reset! registry entities)
    (reset! discovered #{})
    (count entities)))

(defn save!
  "Save the current registry (including discovered entities) to the data file."
  []
  (let [all-entities (into (sorted-set) @registry)
        content (with-out-str
                  (println ";; Known single-word company names for entity extraction")
                  (println ";; This file is loaded at startup and updated dynamically during analysis")
                  (println ";; Format: Set of lowercase company names that should be recognized as valid entity references")
                  (println ";;")
                  (println ";; Last updated:" (java.time.LocalDateTime/now))
                  (println)
                  (pprint/pprint all-entities))]
    (spit entities-file-path content)
    (count all-entities)))

;; ============================================================================
;; Registry Access
;; ============================================================================

(defn ensure-loaded!
  "Ensure the registry is loaded. Safe to call multiple times."
  []
  (when (empty? @registry)
    (load!)))

(defn known-entity?
  "Check if a name is a known entity (case-insensitive)."
  [name]
  (ensure-loaded!)
  (contains? @registry (str/lower-case (str name))))

(defn get-all
  "Get all known entities as a set of lowercase strings."
  []
  (ensure-loaded!)
  @registry)

(defn get-discovered
  "Get entities discovered during this session."
  []
  @discovered)

;; ============================================================================
;; Dynamic Discovery
;; ============================================================================

(defn add!
  "Add a new entity to the registry. Returns true if it was new."
  [name]
  (ensure-loaded!)
  (let [lower-name (str/lower-case (str name))]
    (when-not (contains? @registry lower-name)
      (swap! registry conj lower-name)
      (swap! discovered conj lower-name)
      true)))

(defn add-all!
  "Add multiple entities to the registry. Returns count of new entities added."
  [names]
  (count (filter identity (map add! names))))

(defn remove!
  "Remove an entity from the registry."
  [name]
  (ensure-loaded!)
  (let [lower-name (str/lower-case (str name))]
    (swap! registry disj lower-name)
    (swap! discovered disj lower-name)))

;; ============================================================================
;; Auto-Discovery from SEC Filers
;; ============================================================================

(defn discover-from-sec-filers!
  "Scan SEC filers registry and add well-known companies.
   Options:
     :min-name-length - minimum company name length to consider (default: 4)
     :exclude-patterns - regex patterns to exclude (default: common false positives)"
  [sec-filers-registry & {:keys [min-name-length exclude-patterns]
                          :or {min-name-length 4
                               exclude-patterns [#"(?i)^(the|new|first|one|two|three|four|five)$"
                                                 #"(?i)^(bank|trust|fund|capital|financial|credit)$"
                                                 #"(?i)^(energy|power|oil|gas|water|air)$"
                                                 #"(?i)^(health|medical|bio|life)$"
                                                 #"(?i)^(real|property|land|home)$"
                                                 #"(?i)^(gold|silver|iron|steel)$"
                                                 #"(?i)^(net|web|digital|data|cloud)$"]}}]
  (let [by-name (:by-name sec-filers-registry)
        ;; Extract single-word company names
        single-word-names (->> (keys by-name)
                               (filter #(and (not (str/includes? % " "))
                                             (>= (count %) min-name-length)))
                               (remove (fn [n] (some #(re-matches % n) exclude-patterns))))]
    (add-all! single-word-names)))

;; ============================================================================
;; REPL Convenience
;; ============================================================================

(defn stats
  "Return statistics about the known entities registry."
  []
  (ensure-loaded!)
  {:total (count @registry)
   :discovered-this-session (count @discovered)})

(defn list-discovered
  "List entities discovered during this session."
  []
  (into (sorted-set) @discovered))

(defn reset-discovered!
  "Clear the discovered entities list (but keep them in the registry)."
  []
  (reset! discovered #{}))

(defn reload!
  "Reload the registry from the data file, discarding any unsaved changes."
  []
  (load!))
