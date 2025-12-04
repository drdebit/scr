# SEC Filing Entity Extraction Analysis Results

## Overview

This document summarizes findings from entity extraction analysis across SEC filings, identifying cross-company mentions, conflicts, information asymmetries, and relationship patterns.

---

# Part 1: 10-K Filing Analysis

## Sample Summary

| Metric | Value |
|--------|-------|
| Filings analyzed | 92 |
| Successfully parsed | 92 |
| Unique filers | 82 |
| Total cross-company mentions | 261 |
| Bidirectional relationship pairs | 8 |
| Context files exported | 261 |

## Mention Categories

| Category | Count | Percentage |
|----------|-------|------------|
| Competitor Identification | ~65 | 25% |
| Supplier Dependencies | ~78 | 30% |
| Customer Relationships | ~39 | 15% |
| Investment/Equity Stakes | ~26 | 10% |
| Executive Backgrounds | ~21 | 8% |
| Joint Ventures/Partnerships | ~18 | 7% |
| Litigation/Legal | ~8 | 3% |
| Acquisition Activity | ~6 | 2% |

## Bidirectional Relationships Identified

Eight pairs of companies mutually mention each other in their 10-K filings:

1. **AMD ↔ Intel** - Mutual competitor acknowledgment (CONFLICT - see below)
2. **AMD ↔ NVIDIA** - GPU market competitors
3. **Southwest ↔ Boeing** - Airline/manufacturer dependency
4. **Ford ↔ GM** - Automotive competitors
5. **Delta ↔ American Airlines** - Airline competitors
6. **United Airlines ↔ American Airlines** - Airline competitors
7. **Uber ↔ Lyft** - Rideshare competitors
8. **Rivian ↔ Amazon** - Investment/supply relationship

---

## Conflict 1: AMD vs Intel - Conflicting Market Position Narratives

### Date Identified: 2024-12-04

### Source Filings:
- AMD: `0000002488-25-000012.txt` (10-K for fiscal year ending 2024-12-28)
- Intel: `0000050863-25-000009.txt` (10-K for fiscal year ending 2024-12-28)

### AMD's Characterization of Intel:

From AMD's 10-K, Intel is portrayed as a dominant incumbent:

1. **"Intel Corporation's dominance of the microprocessor market and its aggressive business practices may limit our ability to compete effectively on a level playing field"**

2. **"Intel's microprocessor market share position, significant financial resources, introduction of competitive new products, and existing relationships"**

3. **"Our primary competitor in the supply of CPUs and APUs is Intel"**

4. **"The ability to innovate beyond the x86 instruction set controlled by Intel depends partially on Microsoft"**

AMD mentions Intel 9 times, characterizing them as dominant with "aggressive business practices."

### Intel's Self-Characterization:

From Intel's 10-K, the company portrays itself as struggling to regain competitiveness:

1. **"We have lost market share in recent years as competitors have introduced highly competitive data center and client products"**

2. **"We aim to catch up with our leading third-party manufacturing competitor on process technology"**

3. **"We operate in a particularly competitive market"**

4. **"We expect this competitive environment to continue to intensify in 2025"**

Intel mentions AMD only once, neutrally listing them as a competitor.

### Analysis:

This represents a genuine narrative conflict:

| Dimension | AMD's View of Intel | Intel's View of Itself |
|-----------|--------------------|-----------------------|
| Market Position | "Dominance" | "Lost market share" |
| Competitive Stance | "Aggressive business practices" | Trying to "catch up" |
| Tone | Intel as threat/risk factor | Intel as underdog fighting back |

Both statements may be simultaneously true depending on:
- **Timeframe**: Intel historically dominated but has lost ground recently
- **Market segment**: Intel may still lead in some segments while trailing in others
- **Metric**: Unit share vs. revenue share vs. technology leadership

However, an investor reading only AMD's filing would perceive Intel very differently than one reading Intel's filing. AMD uses Intel's position as a risk factor; Intel acknowledges competitive struggles as challenges to overcome.

---

## Information Asymmetry 1: Rivian-Amazon Relationship

### Source Filings:
- Rivian: `0001874178-25-000006.txt` (10-K)
- Amazon: `0001018724-25-000004.txt` (10-K)

### Rivian's Disclosure:
- **100,000 electric delivery van order** from Amazon prominently disclosed
- **~$1 billion annual revenue** from Amazon mentioned
- Amazon characterized as "significant" customer and investor
- Detailed discussion of vehicle delivery milestones

### Amazon's Disclosure:
- **No mention of Rivian** in 10-K filing
- No mention of the 100,000 van order
- No disclosure of ~$1B annual payments to Rivian

### Analysis:
This is not a factual conflict but represents significant disclosure asymmetry. What is material to Rivian (their largest customer) is immaterial to Amazon (a small portion of their operations). Investors in Rivian see the Amazon relationship prominently; investors in Amazon would not know it exists from the 10-K alone.

---

## Information Asymmetry 2: Competitor Characterizations

Several companies reveal information about competitors that the competitors don't disclose about themselves:

### AMD on NVIDIA:
- Reveals NVIDIA's **"proprietary ecosystem"** and **"aggressive business practices"**
- Describes NVIDIA's **"lock-in"** strategies for customers
- This competitive intelligence is not found in NVIDIA's own 10-K

### Southwest on Boeing:
- Reveals downstream **operational impacts** of Boeing supply issues
- Discloses hiring changes, capacity re-planning, delivery delays
- Shows how Boeing's problems cascade to customers

### Cummins on Stellantis:
- Reveals **supply chain dependency** details
- Stellantis not in our sample, but Cummins provides visibility

### Goldman Sachs (via competitors):
- Other banks reveal Goldman's **consumer business exit strategy**
- Strategic pivots visible through competitor filings

---

## Non-Bidirectional Mentions Summary

Of 261 total mentions, 245 are non-bidirectional (one company mentions another that doesn't mention them back):

| Category | Count | Examples |
|----------|-------|----------|
| Competitive Landscape | ~60 | AMD→NVIDIA, Ford→Tesla |
| Technology Platform Dependencies | ~45 | Multiple companies→Microsoft, →AWS |
| Supplier/Vendor Relationships | ~40 | Airlines→Boeing, Retail→logistics providers |
| Financial Service Providers | ~35 | Various→Goldman Sachs, →JPMorgan |
| Customer Concentration | ~25 | Smaller firms→larger customers |
| Industry Benchmarking | ~20 | Companies citing industry leaders |
| Executive Prior Experience | ~15 | New hires from named companies |
| Regulatory/Litigation Context | ~5 | Companies named in legal matters |

---

# Part 2: 8-K Filing Analysis

## Sample Summary

| Metric | Value |
|--------|-------|
| Filings downloaded | 246 |
| Successfully parsed | 240 |
| Parse errors | 6 |
| Unique filers | 79 |
| Total cross-company mentions | 18 |
| Bidirectional relationship pairs | 0 |
| Total file size | 422 MB |

## Comparison: 8-K vs 10-K Mention Density

| Metric | 10-K | 8-K | Ratio |
|--------|------|-----|-------|
| Filings analyzed | 92 | 240 | 2.6x more 8-Ks |
| Cross-company mentions | 261 | 18 | 14.5x more in 10-Ks |
| Mentions per filing | 2.84 | 0.075 | **38x fewer in 8-Ks** |
| Bidirectional pairs | 8 | 0 | None in 8-Ks |

## 8-K Mention Categories

| Category | Count | Percentage | Description |
|----------|-------|------------|-------------|
| Executive Background | 6 | 33% | New hires' prior employers |
| Financial Transaction | 4 | 22% | Underwriters/counterparties |
| Supplier Relationship | 3 | 17% | All Southwest→Boeing |
| Board Appointment | 1 | 6% | External director's company |
| Other | 4 | 22% | Miscellaneous |

## 8-K Cross-Company Mentions Detail

### Executive Background (6 mentions)
Companies disclosing new executives' prior employers, revealing talent flows:

| Hiring Company | Executive's Prior Company | Role Context |
|----------------|--------------------------|--------------|
| Ford | Lucid Motors | New CFO previously CFO at Lucid |
| Honeywell | General Electric | New executive spent 20 years at GE |
| Salesforce | Gilead Sciences | New CFO was EVP/CFO at Gilead |
| Southwest Airlines | United Airlines | Operations executive from United |
| PayPal | Microsoft | Board member from Microsoft |
| Rivian | Tesla | New CAO spent 11 years at Tesla |

**Insight**: EV sector talent flows (Tesla→Rivian, Lucid→Ford) visible only through 8-K executive announcements.

### Financial Transactions (4 mentions)
Underwriters and counterparties in securities offerings:

| Issuer | Financial Institution | Transaction Type |
|--------|----------------------|------------------|
| Emerson Electric | Goldman Sachs | Underwriter |
| Johnson & Johnson | Goldman Sachs | Underwriter |
| T-Mobile | Morgan Stanley | Underwriter |
| General Motors | Barclays | ASR agreement |

### Supplier Relationships (3 mentions)
All involve Southwest Airlines mentioning Boeing:

1. Forward-looking statements about "dependence on Boeing"
2. Aircraft delivery dependencies and MAX 7 certification
3. Fleet monetization (selling Boeing 737-800s to BBAM)

**Insight**: Southwest's repeated Boeing mentions in 8-Ks reinforce the 10-K finding of material supplier dependency.

### Board Appointments (1 mention)
- UPS appointed Aptiv PLC's CEO to their board

---

## Key Findings: 8-K vs 10-K Comparison

### 1. Content Type Differences

| Content Type | 10-K Prevalence | 8-K Prevalence |
|--------------|-----------------|----------------|
| Competitive landscape analysis | High (25%) | None (0%) |
| Supplier dependencies | High (30%) | Low (17%) |
| Customer relationships | Moderate (15%) | None (0%) |
| Executive backgrounds | Low (8%) | High (33%) |
| Financial counterparties | Low (5%) | High (22%) |
| Investment/equity stakes | Moderate (10%) | None (0%) |

### 2. Why 8-Ks Have Fewer Entity Mentions

- **10-Ks are comprehensive**: Include full business descriptions, risk factors, competitive analysis
- **8-Ks are event-driven**: Focus on specific material events (earnings, officer changes, agreements)
- **Self-referential content**: Most 8-K entity mentions are the filer mentioning itself in press releases
- **Boilerplate language**: Forward-looking statement disclaimers don't name competitors

### 3. Unique Value of 8-K Analysis

Despite fewer mentions, 8-Ks reveal information not visible in 10-Ks:

1. **Real-time talent flows**: Executive moves between companies
2. **Transaction counterparties**: Who companies do business with for specific deals
3. **Operational updates**: More current supplier/customer developments
4. **Board composition changes**: Cross-company governance relationships

---

## Implications for Research

### For 10-K Analysis:
1. Cross-referencing competitor characterizations reveals strategic framing differences
2. Smaller companies provide visibility into larger companies' operations
3. Bidirectional relationships highlight mutual dependencies worth investigating
4. Risk factor language may not reflect current competitive reality

### For 8-K Analysis:
1. Executive background sections reveal human capital relationships
2. Talent flows between companies may signal competitive dynamics
3. Repeated supplier mentions (like Southwest→Boeing) reinforce materiality
4. Financial transaction 8-Ks show banking/advisory relationships

### Combined Analysis Value:
1. 10-Ks provide structural relationship mapping
2. 8-Ks provide temporal updates and executive mobility data
3. Together, they offer complementary views of inter-company relationships

---

## Follow-up Research Questions

1. What is the actual x86 market share split between AMD and Intel?
2. Has Intel's "aggressive business practices" language appeared in antitrust proceedings?
3. How do third-party analysts characterize the AMD-Intel competitive dynamic?
4. Does Tesla mention Rivian in their 10-K (talent drain)?
5. Are there patterns in executive mobility predicting competitive moves?
6. How do 8-K mentions correlate with subsequent 10-K risk factor updates?

---

## Data Files

### 10-K Analysis:
- Context files: `resources/contexts_sample/*.txt` (261 files)
- Index: `resources/contexts_sample/index.csv`
- Filings: `resources/filings_sample/` (92 files, ~2.2GB)

### 8-K Analysis:
- Context files: `resources/contexts_8k/*.txt` (18 files)
- Index: `resources/contexts_8k/index.csv`
- Filings: `resources/filings_8k/` (246 files, 422MB)
