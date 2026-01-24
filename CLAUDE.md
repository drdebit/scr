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
- `parse-edgar-content` - Parse EDGAR content from string (for zip processing)
- `extract-prose-paragraphs` - Extract readable prose from SEC filings
- Handles SGML wrapper format used by SEC

#### `scr.entity`
Named entity extraction and analysis:
- `analyze-filing` - Extract all entity mentions from a filing with relationship classification
- `analyze-filing-content` - Analyze filing content from string (for zip processing)
- `analyze-zip-archive` - Process entire zip archive of filings
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

#### `scr.zip`
Zip archive processing for large-scale analysis:
- `process-entries` - Stream process zip entries without full extraction
- `reduce-entries` - Reduce over zip entries with accumulator
- `is-10k-filing?` - Filter predicate for 10-K filing entries
- Enables processing of large archives (100GB+) with minimal disk usage

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

### Analyze a Zip Archive (Large-Scale Analysis)
```clojure
(require '[scr.entity :as e])
(require '[scr.zip :as z])

;; Filter for recent filings (2010-2014)
(def recent-filter #(and (z/is-10k-filing? %) (re-find #"-1[0-4]-" %)))

;; Analyze all filings in the zip archive
(def result (e/analyze-zip-archive "/path/to/10Ks.zip"
                                   :sec-filers-only true
                                   :filter-fn recent-filter
                                   :progress-interval 500))

;; Find bidirectional relationships
(def bidirectional (e/find-bidirectional-relationships (:results result)))
```

## Data Files

### Input Data
- `resources/filings_sample/` - Downloaded 10-K filings (92 files, ~2.2GB)
- `resources/filings_8k/` - Downloaded 8-K filings (246 files, 422MB)
- `resources/sec/known_entities.edn` - Known entity name mappings
- `/media/extra2/10Ks.zip` - Complete 10-K archive (164,663 files, 51GB compressed, ~434GB uncompressed)

### Output Data
- `resources/contexts_sample/` - Exported 10-K entity mention contexts (261 files)
- `resources/contexts_sample/index.csv` - CSV index of all 10-K mentions
- `resources/contexts_sample/CONFLICTS.md` - Analysis results and findings
- `resources/contexts_8k/` - Exported 8-K entity mention contexts (18 files)
- `resources/contexts_8k/index.csv` - CSV index of all 8-K mentions
- `resources/10k_analysis/` - Large-scale analysis results (39,910 filings)
  - `summary.edn` - Analysis statistics and bidirectional relationships
  - `bidirectional.edn` - Full relationship details with contexts
  - `entity_index.csv` - All 32,216 entity mentions

### Temporary/Working Files
- `/tmp/8k_filings.txt` - Extracted 8-K filing list from EDGAR index
- `/tmp/all_8k_urls.txt` - URLs for 8-K downloads
- `/tmp/sample_ciks.txt` - Sample CIK list for filtering

## Analysis Results Summary

### Large-Scale 10-K Analysis (2010-2014)
- **39,910 filings** analyzed from the complete 10-K archive
- **32,216 entity mentions** identified across **2,453 unique entities**
- **15,144 filings** (38%) contained at least one cross-company mention
- **19 bidirectional relationship pairs** found
- Processing time: 3 hours at 3.7 filings/second

### Bidirectional Relationships Discovered

| Company A | Company B | Mentions | Relationship Type |
|-----------|-----------|----------|-------------------|
| CVR Energy | CVR Partners LP | 5/38 | Energy/fertilizer affiliates |
| Intel | Micron Technology | 1/42 | IMFT NAND Flash joint venture |
| Vishay Intertechnology | Vishay Precision Group | 3/17 | Spun-off entities |
| Cheniere Energy Partners | Cheniere Energy Inc | 6/10 | LNG corporate family |
| Ameren Corp | Ameren Illinois | 10/4 | Utility holding company |
| NVIDIA | Intel | 10/4 | Competitors + $1.5B cross-licensing |
| Marathon Petroleum | MPLX LP | 5/7 | Refining/MLP relationship |
| OGE Energy | CenterPoint Energy | 1/5 | Enable Midstream partnership |
| Regions Financial | Raymond James Financial | 3/2 | Morgan Keegan acquisition |
| National Healthcare | National Health Investors | 3/2 | Healthcare REIT spin-off |
| FIS | Fidelity National Financial | 1/3 | Former parent/subsidiary |
| Edison International | Southern California Edison | 1/2 | Utility holding company |
| Bimini Capital | Orchid Island Capital | 1/2 | Mortgage REIT affiliates |
| TVA | American Electric Power | 2/1 | Power purchase agreements |
| PennantPark Investment | PennantPark Floating Rate | 1/1 | BDC family |
| Aspen Insurance | RenaissanceRe Holdings | 1/1 | Reinsurance industry |
| Kronos Worldwide | NL Industries | 1/1 | Chemical industry affiliates |
| Enterprise Products Partners | Plains All American Pipeline | 1/1 | Eagle Ford Pipeline JV |
| Caterpillar | Deere & Co | 1/1 | Industrial equipment competitors |

### Key Findings

1. **Corporate Affiliates Dominate**: Most bidirectional relationships are between related entities (parent/subsidiary, spin-offs, MLPs and sponsors)

2. **Joint Ventures Highly Visible**: Intel-Micron (IMFT), OGE-CenterPoint (Enable Midstream), and Enterprise-Plains (Eagle Ford) show how JV partners extensively document their relationships

3. **Cross-Licensing Creates Mutual Disclosure**: NVIDIA-Intel's patent cross-license resulted in both companies disclosing the $264M/year arrangement

4. **Competitor Relationships Rare**: Only Caterpillar-Deere shows pure competitor mutual mention - most competitor relationships are one-directional

5. **Entity Density**: 0.81 entity mentions per filing on average (32,216 mentions / 39,910 filings)

### 8-K Analysis (Smaller Sample)
- **240 filings** successfully parsed from 79 unique filers
- **18 cross-company mentions** identified
- **0 bidirectional relationships** (8-Ks are event-driven, not comprehensive)
- Primary mention types: Executive backgrounds (33%), Financial transactions (22%), Supplier relationships (17%)

### Comparative Insight
10-Ks have **~11x more entity mentions per filing** than 8-Ks (0.81 vs 0.075), consistent with 10-Ks being comprehensive annual disclosures vs. 8-Ks being event-driven filings.

## Performance Optimizations

The codebase includes several performance optimizations:
1. **Memoized regex patterns** - Compiled once, reused across extractions
2. **Cached HTML strings** - Stringify Jsoup documents once per filing
3. **Optimized Levenshtein** - O(min(n,m)) space complexity for fuzzy matching
4. **Parallel processing** - Optional `:parallel true` for multi-file analysis
5. **Raw content caching** - Avoid re-reading files during extraction
6. **Zip streaming** - Process archives without full extraction (handles 100GB+ archives with minimal disk usage)
