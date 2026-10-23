# Retrieval Contract

This contract defines guarded retrieval (`V3_GUARDED`) for the Java local fallback and the Python LlamaIndex service. The engines may use different tokenization and vector/BM25 implementations, but they must expose the same filtering, fusion, ranking, rejection, and citation semantics.

## Input and configuration

- `query`: the trimmed user question.
- `candidateK`: the existing `candidateLimit` / `RAG_CANDIDATE_LIMIT`; default `8` per retriever.
- `finalK`: the existing `finalLimit` / `RAG_FINAL_LIMIT`; default `4`.
- `threshold`: `minimumEvidenceScore` / `RAG_MINIMUM_EVIDENCE_SCORE`; default `0.9`.
- `retrievalVersion`: `V3_GUARDED`.
- Existing disease, source type, and evidence-level filters are applied before candidate ranking.
- Query tokenization may append the following finite, corpus-backed aliases as retrieval features while retaining all original query features: `三高` → `高血压` / `糖尿病` / `高血脂`; `心梗` → `心脏病发作`; `脑卒中` → `中风`; `血压偏高` → `高血压`. The Java and Python tokenizers apply the same alias map. This is candidate-recall normalization only: it does not rewrite the displayed question/evidence, metadata-match text, RRF weights, score bonuses, threshold, or evidence-coverage rules. Unlisted terms are not expanded.

## V3 pipeline

1. Retrieve up to `candidateK` active, filter-matching candidates independently from vector and BM25/lexical retrieval.
2. Normalize both result lists to stable `chunkId` identity. Fuse ranks using weighted Reciprocal Rank Fusion with constant `60`, lexical weight `1.0`, and vector weight `0.85`:
   `rrf = lexicalWeight / (60 + lexicalRank) + vectorWeight / (60 + vectorRank)`; ranks start at `1` and a missing-channel contribution is `0`.
3. Deduplicate by `chunkId`. If an ID appears more than once in one channel, keep its best (first) rank. Do not deduplicate by text or title. `documentId` remains citation provenance and is not a substitute for chunk identity.
4. Normalize RRF by `(lexicalWeight + vectorWeight)/(rankConstant + 1)` and multiply by `(1 + metadataWeight * bonus)`, with calibrated default `metadataWeight=0`. The available category bonuses are: exact normalized query mention of disease `+0.22`, syndrome `+0.20`, formula `+0.18`, herb `+0.10`; evidence level A `+0.12`, B `+0.08`, C `+0.04`, other/unrated `+0.00`. Each category contributes at most once. Metadata bonuses affect ranking and the relevance gate; source type and arbitrary keywords do not receive ranking bonuses.
5. Sort by the resulting `rankScore` descending, with `chunkId` ascending as a deterministic tie-breaker.
6. Apply the relevance gate to the highest `rankScore`. A candidate is sufficient when `rankScore >= threshold`. If there are no candidates or the highest score is below threshold, return `REJECT` with no citations. Otherwise form the `finalK` ranked evidence set.
7. Apply the question-evidence coverage check to that returned evidence set. Individualized medication dose adjustment requests are rejected as unsafe to personalize from general retrieval evidence. For supported attribute checks (currently pregnancy-specific safety, blood-pressure normal ranges including age-stratified ranges, heart/kidney-condition-specific licorice safety, and the stated licorice/corticosteroid interaction), the requested attribute/relation must be present together within one selected chunk. A topic match alone is insufficient. If the required attribute is absent, return `REJECT` with no citations; otherwise return the selected evidence unchanged. This check does not modify RRF, metadata bonuses, ranking, or the configured threshold.

The score is a normalized rank-fusion score with proportional metadata adjustments, not a calibrated probability. The threshold has the same meaning in both engines: it is applied after metadata ranking. Raw vector and BM25 scores are implementation-specific and are not compared across engines. Normalized scores express rank agreement, not a probability; legacy addition is available only with the explicit compatibility switch.

## Citation and trace

Every returned citation must identify the exact source chunk with both `documentId` and `chunkId`. Citation order is the final ranked order; the displayed quote and metadata must come from that chunk. Scores may differ slightly across implementations, but must represent the post-metadata `rankScore` and must not change the citation identity contract.

The existing trace counts retain their meanings: lexical and vector candidate counts are pre-fusion list sizes, fused candidate count is the deduplicated union before the final K, and selected count is the number returned (zero on rejection). Trace strategy and notes describe engine-specific stages; the structured `rejected` / `threshold` fields identify the common gate decision and configured threshold. The coverage decision is described in notes and does not add or change API fields.

## Consistency acceptance

For the same snapshot, query, filters, and configuration, the contract tests require 100% reject agreement, at least 0.90 top-1 document agreement, and mean per-case top-K Jaccard overlap of at least 0.75. Exact raw scores and internal retrieval traces are intentionally not required to match. Lower agreement is reported as a contract failure for investigation; thresholds must not be weakened to conceal it.

Changes to either V3 engine must update this contract and the shared cross-engine fixtures/tests in the same change.

## Shared token and score implementation

Both engines use contiguous Han bigrams and ASCII terms of at least two characters, retaining frequency. BM25 uses k1=1.4, b=.72, whole-corpus IDF and mean length, cached at corpus change. Hash vectors use 512 SHA-256 signed buckets and identical term weights. V2 and V3 share weighted RRF; source filters and relatedness do not rebuild corpus statistics.
