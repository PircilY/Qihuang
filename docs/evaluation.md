# RAG Evaluation

This document defines the reproducible evaluation workflow for the current RAG prototype. The evaluation dataset is independent from the knowledge corpus and is used without an LLM judge.

## Dataset

The versioned dataset is `backend/src/main/resources/data/evaluation-cases.json`. Version `2.0.0` contains 50 user-style questions:

| Category | Count | Purpose |
| --- | ---: | --- |
| `direct` | 8 | Explicit answer retrieval |
| `synonym` | 8 | Paraphrase and synonym robustness |
| `vague` | 6 | Underspecified user language |
| `long` | 5 | Long-query stability |
| `distractor` | 6 | Ranking against plausible distractors |
| `no-answer` | 8 | Safe rejection when evidence is absent |
| `citation` | 5 | Citation identity and support |
| `multi-document` | 4 | Evidence recall across documents |

The current formal suite runs each of the 50 questions against all 4 versions (`V0_DIRECT`, `V1_VECTOR`, `V2_HYBRID`, and `V3_GUARDED`), for **50 questions × 4 versions = 200 evaluation results**. The current seed knowledge base contains 4 documents and 11 chunks. The 50 questions are a project regression benchmark, not an external clinical test set.

Each case contains:

```json
{
  "id": "direct-001",
  "category": "direct",
  "question": "...",
  "expectedDocumentIds": ["WHO-HTN"],
  "expectedChunkIds": ["WHO-HTN-001"],
  "expectedClaims": ["..."],
  "shouldRefuse": false
}
```

Answerable cases provide expected document IDs, chunk IDs, and claims; reject cases (`shouldRefuse=true`) declare no expected evidence or claims. Dataset tests enforce 50 unique IDs and questions, the category distribution, valid corpus references, multi-document labels, and support of each expected claim by at least one labelled chunk.

### Safety rule regression set

The safety rule regression set is maintained separately in [`tests/safety-rule-cases.json`](../tests/safety-rule-cases.json). Its original 60 cases are retained unchanged (30 refusals and 30 accepts); intent, normalization, negation/history, English, and whitelist-conflict cases have been appended. Java and Python load the same file and assert identical decisions. This rule regression set is separate from the original 50-question retrieval evaluation, but is an allowed calibration/regression set under Protocol v3. It is never part of the frozen holdout.

Safety intent is distinct from symptom-topic mention. A symptom by itself does not imply a current emergency: “胸痛的原因有哪些？” is a knowledge question. A symptom combined with current/personal, sudden, severe, or help-seeking intent is escalated: “我现在胸痛” is an emergency. Explicit nearby negation (“没有胸痛”) and historical phrasing (“以前胸痛过”) suppress an emergency match for that mention; a separate active current symptom remains eligible. Emergency triage takes precedence over individualized refusal, which takes precedence over a knowledge whitelist. A whitelist phrase never cancels a complete individualized pattern.

### Safety blind set

[`tests/safety-blind-cases.json`](../tests/safety-blind-cases.json) is an intentionally empty JSON array template. Test code reads and validates cases from it when user-authored cases are added; Codex has not supplied any blind examples. Keep this set separate from both the 50-question retrieval benchmark and the rule regression set, and do not use it for rule tuning.

## Evaluation flow

```text
evaluation-cases.json (50 cases)
        ↓
each case × each IterationVersion (4 versions)
        ↓
RagOrchestrationService.askExtractive(...)
        ↓
retrieved evidence / answer / citations / refusal decision
        ↓
EvaluationService per-case evaluator
        ↓
retrieval + claim coverage + citation + reject metrics
        ↓
overall and category aggregates (200 case-version results)
```

The evaluation uses extractive-only calls so configured LLM generation does not introduce variation. Retrieval engine selection still follows the active engine configuration; see [Engine mode and reproducibility](#engine-mode-and-reproducibility).

## V3_GUARDED

`V3_GUARDED` is the guarded hybrid-retrieval version, not merely a Top-K retriever or a reranker. In broad terms, it retrieves lexical and vector candidates, applies candidate relevance filtering, fuses ranks with weighted RRF and chunk-ID deduplication, then applies metadata reranking. A score threshold checks whether the top-ranked evidence is sufficient; the separate **Evidence Coverage Gate** then checks whether the selected evidence covers specified question attributes or relations. If a required coverage condition is absent, it rejects the answer rather than treating a topic match as sufficient evidence. Accepted final evidence is used for answer assembly and citations, which are then evaluated.

The two engines have implementation-specific details, so this description does not assert identical internal ordering. In the Java local path, candidate filtering is applied within lexical/vector candidate retrieval before RRF; the Python guarded path filters retrieved channel candidates before weighted fusion. The gate is an evidence-coverage and safety constraint, not a metadata rerank operation. See [`retrieval-contract.md`](retrieval-contract.md) for the shared V3 contract.

## Execution contract

`EvaluationService` extends the existing evaluation path and calls every iteration through extractive mode. This prevents configured language models from introducing nondeterminism while retrieval, rejection, answer assembly, and citation mapping remain under test.

The HTTP entry point remains:

```text
POST /api/v1/evaluations/run
```

The complete automated regression entry is:

```powershell
mvn -q -f backend/pom.xml test
```

For a focused dataset and baseline check:

```powershell
mvn -q -f backend/pom.xml '-Dtest=EvaluationDatasetTest,EvaluationMetricsTest,EvaluationServiceTest,CitationSupportEvaluatorTest' test
```

The response contains overall metrics per iteration, the same metrics grouped by category, and per-case expected/retrieved IDs and decisions. `retrievalK` records the configured final K used for the run.

The current retrieval configuration uses candidate K `8`, final K `4`, RRF constant `60`, lexical weight `1.0`, vector weight `0.85`, and V3 minimum evidence score `0.016` (see `backend/src/main/resources/application.yml`). The final K is also returned as `retrievalK`; if configuration changes, record the effective value with the result.

## Metrics

Retrieval, expected-claim coverage, and citation aggregates use answerable cases (`shouldRefuse=false`); reject metrics use all cases. The evaluator prefers `expectedChunkIds` for matching; if none are present, it uses document IDs. For a reject case, retrieval rank is not evaluated.

- `Hit@1`, `Hit@3`, and `Hit@K`: fraction of answerable cases whose first expected ID occurs at rank 1, within rank 3, or within configured final K, respectively.
- `Recall@K`: per-case intersection of expected and retrieved IDs divided by the number of expected IDs, averaged over answerable cases. This captures multi-document completeness.
- `MRR`: mean reciprocal rank of the first expected ID; a miss contributes zero.
- `Top-K Jaccard`: per-case expected/retrieved ID intersection divided by their union, averaged over answerable cases. If both sets are empty, the implementation returns 1.0.
- `Claim Coverage` (`Expected Claim Coverage` in the API): fraction of expected claims supported by either the answer text or at least one returned citation quote, using the rule-based citation support evaluator. It is not an LLM-judge score.

Reject is the positive class:

- `True Reject`: no-answer case rejected.
- `False Reject`: answerable case rejected.
- `False Accept`: no-answer case answered. This is the highest-risk error in this domain.
- `True Accept`: answerable case answered.
- Precision is `TR / (TR + FR)`, recall is `TR / (TR + FA)`, and accuracy is `(TR + TA) / all cases`.

Citation metrics are calculated over answerable cases:

- Presence: the answer contains at least one `[n]` marker.
- Validity: every marker resolves to a returned citation with non-empty `documentId` and `chunkId`.
- Support: the claim adjacent to `[n]` is supported by that citation's quote.

Support compares the text segment associated with each citation marker to that citation's quote. It uses tokenizer features (including Chinese character bigrams and English terms), numeric equality, and rule-based sentence polarity. Negated evidence cannot support an affirmative claim; uncertain evidence does not support an overconfident affirmative claim. This is a deterministic heuristic, not a semantic substitute for expert review.

`failureCount` counts each case once if its refusal decision is wrong, or—on an answerable case—if it misses Hit@K, lacks citation presence, validity, or support, or has expected Claim Coverage below 1.0. It is a case-failure count, not a separate metric threshold for declaring an experiment PASS.

## PASS / FAIL and current frozen baseline

The implementation calculates metrics and `failureCount`; it does not define a universal numeric PASS threshold for all experiments. Experiment A's PASS/FAIL conclusion follows the pre-established acceptance criteria for that experiment and is recorded as **PASS** in [`evaluation-baseline.md`](evaluation-baseline.md). Do not infer a new threshold from the general-purpose assertions in automated tests.

The formal experiment states P2-02-2 and P2-02-3 Experiment A are both frozen. Experiment A has `failureCount = 1` (down from 4); its False Reject and False Accept are both 0, and Citation Presence / Validity / Support are each 1.000. Experiment B has not been conducted and is deferred. The complete frozen metric comparison and remaining `distractor-005` case are recorded in [`evaluation-baseline.md`](evaluation-baseline.md); this method document does not duplicate its results table.

## Engine mode and reproducibility

`EvaluationService` loads the versioned dataset, iterates the four `IterationVersion` values, and calls `RagOrchestrationService.askExtractive`. The extractive-only flag disables LLM generation, but it does not by itself pin the retrieval engine.

- The normal application configuration (`backend/src/main/resources/application.yml`) defaults `RAG_ENGINE_MODE` to `auto` and `RAG_FALLBACK_TO_LOCAL` to `true`. In `auto`, the orchestration service attempts the Python FastAPI/LlamaIndex service and falls back to the Java local engine if the remote call fails. An environment override can select `local` or `llama-index` instead.
- The Spring test resource (`backend/src/test/resources/application.yml`) sets `app.rag-engine.mode: local`; therefore the documented Maven regression-test path uses the Java Local Engine by default (unless an external environment override changes the property).
- The ordinary HTTP evaluation endpoint uses the application's active configuration, not the test resource. With its default `auto` setting, the endpoint has a Python-first/fallback path and is not forced to one engine.
- `EvaluationRunResponse` records case results and metrics but does not persist the actual engine used per result. Under `auto`, remote failure and the retry interval can also mean different cases in one run use different paths. The current frozen P2-02-2 / Experiment A summary does not identify an engine mode either. Thus the repository confirms Java Local for the default test configuration and `auto` for an unoverridden application run, but it cannot independently prove which engine produced the already-frozen P2-02-2 / Experiment A numbers. The earlier P1-03 historical snapshot explicitly says Java local; that historical provenance should not be silently assumed for later experiment records.

For reproducibility, retain the dataset version and file, knowledge snapshot, V3 configuration, active engine mode, and actual engine for the run. The current knowledge scope is 4 documents / 11 chunks. The formal frozen P2-02-2 / Experiment A table records the metrics and scope but does not provide current dataset and knowledge-snapshot hashes or per-result engine provenance; the hashes in the historical P1-03 section of the baseline document belong to that historical snapshot, not automatically to Experiment A.

Java local and Python FastAPI are both retained. The Retrieval Contract cross-validates key retrieval behavior on 14 fixture cases; it does not assert code-level identity or full 200-result agreement between engines. See [`retrieval-contract.md`](retrieval-contract.md).

## Change policy

Keep the dataset unchanged when comparing retrieval, embedding, reranking, chunking, query rewriting, or prompt variants. If labels or corpus contents change, increment the dataset version and record both dataset and knowledge snapshot hashes in the new baseline report. Do not weaken assertions or rewrite questions to conceal a regression.
