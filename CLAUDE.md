# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

This is a Clojure project using deps.edn (Clojure CLI tools).

```bash
# Run all tests
clj -X:test

# Start REPL with nREPL + CIDER middleware
clj -M:repl

# Run REPL with test dependencies
clj -M:dev
```

## Project Overview

**scr** is an SEC filing analysis library focused on extracting and analyzing named entity relationships from EDGAR filings.

### Core Capabilities

1. **Text Extraction**: Extract plain text from HTML and SEC EDGAR filing formats
2. **Entity Extraction**: Identify and normalize company names mentioned in SEC filings
3. **Relationship Classification**: Classify entity mentions by relationship type (supplier, customer, competitor, partner, etc.)
4. **Cross-Filing Analysis**: Find bidirectional relationships where companies mention each other
5. **SEC Filer Matching**: Match extracted entities against the SEC filer registry for CIK resolution

### Dependencies
- **Jsoup** - HTML parsing and text extraction
- **Hickory** - HTML to Clojure data structure conversion
- **babashka/fs** - File system utilities

## Architecture

### Core Namespaces

#### `scr.core`
Basic text extraction utilities:
- `extract-text-from-html`, `extract-text-from-html-file` - HTML text extraction via Jsoup
- `process-text-file-lazily`, `reduce-text-file` - Lazy/streaming file processing
- `extract-text-from-file` - Extension-based dispatch for different file types

#### `scr.sec`
SEC EDGAR filing parser:
- `parse-edgar-file` - Parse EDGAR .txt files, extract HTML documents
- `extract-prose-paragraphs` - Extract readable prose from SEC filings
- Handles SGML wrapper format used by SEC

#### `scr.entity`
Named entity extraction and analysis:
- `analyze-filing` - Extract all entity mentions from a filing with relationship classification
- `find-bidirectional-relationships` - Find companies that mutually mention each other
- `export-entity-contexts` - Export mention contexts to text files
- `extract-filing-metadata` - Extract CIK, filer name, filing date, type

#### `scr.sec-filers`
SEC filer registry for entity resolution:
- `lookup-by-name` - Fuzzy match company names to SEC filers
- `lookup-by-cik` - Direct CIK lookup
- `lookup-by-ticker` - Ticker symbol lookup
- Optimized Levenshtein distance with O(min(n,m)) space complexity

#### `scr.known-entities`
Known entity registry for improving extraction accuracy:
- Loaded from `resources/sec/known_entities.edn`
- Contains common company name variants and aliases

## Key Data Structures

### Filing Analysis Result
```clojure
{:cik "0000002488"
 :filer-name "ADVANCED MICRO DEVICES INC"
 :filing-date "2024-12-28"
 :filing-type "10-K"
 :file-path "resources/filings_sample/..."
 :entities [{:sec-name "Intel Corporation"
             :cik "50863"
             :ticker "INTC"
             :relationships [:competitor :mentioned]
             :relationship-counts {:competitor 5 :mentioned 4}
             :contexts ["..." "..."]
             :sections [:risk-factors :business]
             :materiality-indicators #{"significant" "primary"}}]}
```

### Relationship Types
- `:supplier` - Supply chain dependency
- `:customer` - Customer/revenue relationship
- `:competitor` - Competitive relationship
- `:partner` - Business partnership
- `:joint-venture` - Joint venture participant
- `:affiliate` - Corporate affiliate
- `:investment` - Equity investment
- `:acquisition` - M&A target/acquirer
- `:mentioned` - General mention without specific relationship

## Usage Examples

### Analyze a Single Filing
```clojure
(require '[scr.entity :as e])

(def result (e/analyze-filing "path/to/filing.txt" {:sec-filers-only true}))
(println "Found" (count (:entities result)) "entity mentions")
```

### Analyze Multiple Filings and Find Bidirectional Relationships
```clojure
(require '[scr.entity :as e])
(require '[clojure.java.io :as io])

(def filings (->> (file-seq (io/file "resources/filings_sample"))
                  (filter #(.isFile %))
                  (map #(.getPath %))))

(def results (map #(e/analyze-filing % {:sec-filers-only true}) filings))
(def bidirectional (e/find-bidirectional-relationships results))

(println "Found" (count bidirectional) "bidirectional relationships")
```

### Export Entity Contexts
```clojure
(e/export-entity-contexts results :output-dir "resources/contexts")
```

## Data Files

### Input Data
- `resources/filings_sample/` - Downloaded 10-K filings (92 files, ~2.2GB)
- `resources/filings_8k/` - Downloaded 8-K filings (246 files, 422MB)
- `resources/sec/known_entities.edn` - Known entity name mappings

### Output Data
- `resources/contexts_sample/` - Exported 10-K entity mention contexts (261 files)
- `resources/contexts_sample/index.csv` - CSV index of all 10-K mentions
- `resources/contexts_sample/CONFLICTS.md` - Analysis results and findings
- `resources/contexts_8k/` - Exported 8-K entity mention contexts (18 files)
- `resources/contexts_8k/index.csv` - CSV index of all 8-K mentions

### Temporary/Working Files
- `/tmp/8k_filings.txt` - Extracted 8-K filing list from EDGAR index
- `/tmp/all_8k_urls.txt` - URLs for 8-K downloads
- `/tmp/sample_ciks.txt` - Sample CIK list for filtering

## Analysis Results Summary

### 10-K Analysis
- **92 filings** analyzed from 82 unique filers
- **261 cross-company mentions** identified
- **8 bidirectional relationship pairs** found (AMD-Intel, AMD-NVIDIA, Southwest-Boeing, Ford-GM, Delta-American, United-American, Uber-Lyft, Rivian-Amazon)
- Key finding: AMD-Intel narrative conflict (AMD says Intel is "dominant"; Intel says they've "lost market share")

### 8-K Analysis
- **240 filings** successfully parsed from 79 unique filers
- **18 cross-company mentions** identified
- **0 bidirectional relationships** (8-Ks are event-driven, not comprehensive)
- Primary mention types: Executive backgrounds (33%), Financial transactions (22%), Supplier relationships (17%)

### Key Insight
10-Ks have **38x more entity mentions per filing** than 8-Ks (2.84 vs 0.075), but 8-Ks uniquely reveal executive talent flows between companies.

## Performance Optimizations

The codebase includes several performance optimizations:
1. **Memoized regex patterns** - Compiled once, reused across extractions
2. **Cached HTML strings** - Stringify Jsoup documents once per filing
3. **Optimized Levenshtein** - O(min(n,m)) space complexity for fuzzy matching
4. **Parallel processing** - Optional `:parallel true` for multi-file analysis
5. **Raw content caching** - Avoid re-reading files during extraction
