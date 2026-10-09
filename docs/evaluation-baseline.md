# Formal Frozen Experiment Baseline: P2-02-2 → Experiment A

## Project and experiment scope

This record documents the frozen evaluation results for a domain evidence-retrieval and question-answering RAG prototype over public materials. The research focus is hybrid retrieval, evidence screening, citation traceability, and evaluability. It is not a general AI assistant, Agent/MCP system, diagnostic tool, clinical decision system, or treatment-advice system.

The purpose of this comparison is to record the confirmed P2-02-2 baseline and the subsequent P2-02-3 Experiment A result without changing the dataset, labels, or evaluation definitions. Both result sets are for `V3_GUARDED`.

## Evaluation and knowledge-base scope

- Evaluation set: 50 questions across 8 categories.
- Evaluation versions: 4; the complete suite contains 200 question-version results (50 × 4). The comparison below reports the frozen `V3_GUARDED` results for each experiment state.
- Knowledge base: 4 documents and 11 chunks.
- Evaluation is a reproducible benchmark record, not a claim of clinical validity or broad medical coverage.

## P2-02-2 → Experiment A: frozen results

| Metric (`V3_GUARDED`) | P2-02-2 | Experiment A |
| --- | ---: | ---: |
| Hit@1 | 0.643 | 0.667 |
| Hit@3 | 0.810 | 0.881 |
| Hit@K | 0.905 | 0.976 |
| Recall@K | 0.893 | 0.976 |
| MRR | 0.734 | 0.782 |
| Top-K Jaccard | 0.355 | 0.337 |
| Claim Coverage | 0.905 | 0.976 |
| Citation Presence | 1.000 | 1.000 |
| Citation Validity | 1.000 | 1.000 |
| Citation Support | 1.000 | 1.000 |
| True Reject | 8 | 8 |
| False Reject | 0 | 0 |
| False Accept | 0 | 0 |
| True Accept | 42 | 42 |
| Reject Precision | 1.000 | 1.000 |
| Reject Recall | 1.000 | 1.000 |
| Reject Accuracy | 1.000 | 1.000 |
| `failureCount` | 4 | 1 |

### Metric changes

| Metric | Change (Experiment A − P2-02-2) | Interpretation |
| --- | ---: | --- |
| Hit@1 | +0.024 | Improved |
| Hit@3 | +0.071 | Improved |
| Hit@K | +0.071 | Improved |
| Recall@K | +0.083 | Improved |
| MRR | +0.048 | Improved |
| Top-K Jaccard | -0.018 | Top-K and gold-set overlap structure changed; this is not, by itself, evidence of overall quality decline. |
| Claim Coverage | +0.071 | Improved |
| Citation Presence | No change; remains 1.000 | Stable |
| Citation Validity | No change; remains 1.000 | Stable |
| Citation Support | No change; remains 1.000 | Stable |
| True Reject / False Reject / False Accept / True Accept | No change: 8 / 0 / 0 / 42 | Safety decisions remained unchanged. |
| Reject Precision / Recall / Accuracy | No change: 1.000 / 1.000 / 1.000 | Stable |
| `failureCount` | 4 → 1 | Three previously failing cases were recovered; one remains. |

Experiment A expanded correct candidate coverage, with Recall@K, Hit@K, and Claim Coverage improving. Since the overlap structure between the final Top-K and the gold set also changed, Top-K Jaccard moved from 0.355 to 0.337. Jaccard is retained and should be read alongside retrieval and claim-coverage metrics, not as a standalone performance verdict.

## Experiment A method and controlled scope

Experiment A's sole core change was the candidate prefilter rule: when the query's `disease` metadata and a chunk's `disease` metadata share at least one non-generic tokenizer feature, the candidate may pass the prefilter. If the only overlap is weak overlap in the body text, the original threshold of at least 3 non-generic overlapping tokens remains in force.

The experiment did not change:

- RRF weights;
- metadata bonus;
- Evidence Coverage Gate;
- reject threshold;
- evaluation labels;
- P2-02-2 synonym normalization;
- answer evidence; or
- citation evaluator.

## Four tracked failure cases

| Case | Query | P2-02-2 | Experiment A |
| --- | --- | --- | --- |
| `direct-002` | 血压非常高时可能出现哪些症状？ | Incorrectly retrieved `WHO-HTN-001`. | Recovered `WHO-HTN-002`, at rank 2 in final K. |
| `synonym-002` | 血压升得很厉害时，头部、视力或胸口会有什么异常？ | Incorrectly retrieved `WHO-HTN-001`. | Recovered `WHO-HTN-002`, at rank 1. |
| `vague-002` | 血压特别高，身体一般会怎么提醒我？ | Incorrectly retrieved `WHO-HTN-001`. | Recovered `WHO-HTN-002`, at rank 2. |
| `distractor-005` | 心脏病发作和中风的直接机制是血管堵塞，还是治疗高血压、糖尿病和高血脂？请回答直接机制。 | Expected `WHO-CVD-002`; remained a failure. | Still failed: during metadata rerank, the hypertension/diabetes/high-cholesterol disease metadata triggered the HTN metadata bonus, and `WHO-CVD-002` was excluded from final K. The `+0.22` bonus was not changed. |

The first three cases were recovered by allowing disease-metadata overlap to admit candidates through the prefilter. `distractor-005` remains the sole failure and is recorded here as an unresolved metadata-rerank case; this baseline does not propose or apply a follow-up algorithm change.

## Frozen conclusion

- **P2-02-2 remains frozen and is not reverted or re-experimented.**
- **P2-02-3 Experiment A: PASS; current formal `failureCount = 1`.**
- False Reject and False Accept remain 0; all three citation metrics remain 1.000.
- Experiment B is not being conducted at this stage.
- P2-02-2 and Experiment A are the formal experiment records for subsequent thesis analysis and defense.
- Any future algorithm experiment must receive a separate experiment identifier and must not overwrite Experiment A.

---

## Historical P1-03 initial baseline snapshot (retained for history; not the current formal baseline)

The following section preserves the earlier P1-03 snapshot as historical context. Its V3 results and findings predate P2-02-2 and Experiment A; use the frozen results above as the current formal comparison.

### RAG Evaluation Baseline 2.0.0

Generated on 2026-10-07 using the Java local engine in extractive mode. No external LLM, reranker, query rewrite, or new infrastructure was used.

#### Reproducibility

- Dataset: 50 cases, version `2.0.0`, final `K=4`.
- Dataset SHA-256: `9A77050A4C2C476DEA3D675F4252C4331B8D956DE9DD5FE77AFB51B2473CB907`.
- Knowledge snapshot SHA-256: `7C4E5EC47948325EB4B17727CBC392839B76AA3FE69E1B9E1B00E7B37BA2E570`.
- Retrieval configuration: candidate K `8`, final K `4`, RRF constant `60`, lexical weight `1.0`, vector weight `0.85`, V3 threshold `0.016`.

#### Overall

| Version | Hit@1 | Hit@3 | Hit@K | Recall@K | MRR | Jaccard | Claim coverage | Citation support | Reject accuracy | Failures |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V0 Direct | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.083 | 0.000 | 0.880 | 48 |
| V1 Vector | 0.714 | 0.857 | 0.905 | 0.893 | 0.794 | 0.353 | 0.905 | 0.976 | 0.920 | 7 |
| V2 Hybrid | 0.714 | 0.857 | 0.905 | 0.893 | 0.790 | 0.353 | 0.905 | 0.976 | 0.920 | 7 |
| V3 Guarded | 0.619 | 0.786 | 0.881 | 0.869 | 0.710 | 0.347 | 0.881 | 0.976 | 0.920 | 8 |

V3 reject matrix: true reject `5`, false reject `1`, false accept `3`, true accept `41`. Reject precision is `0.833`; reject recall is `0.625`.

#### V3 By Category

| Category | Hit@1 | Hit@3 | Hit@K | Recall@K | MRR | Jaccard | Claim coverage | Reject accuracy | Failures |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Direct | 0.750 | 0.750 | 0.875 | 0.875 | 0.781 | 0.260 | 0.875 | 1.000 | 1 |
| Synonym | 0.625 | 0.750 | 0.750 | 0.688 | 0.667 | 0.365 | 0.750 | 0.875 | 2 |
| Vague | 0.333 | 0.833 | 0.833 | 0.833 | 0.528 | 0.403 | 0.833 | 1.000 | 1 |
| Long | 0.600 | 0.600 | 1.000 | 1.000 | 0.700 | 0.317 | 1.000 | 1.000 | 0 |
| Distractor | 0.500 | 0.667 | 0.833 | 0.833 | 0.625 | 0.222 | 0.833 | 1.000 | 1 |
| No-answer | n/a | n/a | n/a | n/a | n/a | n/a | n/a | 0.625 | 3 |
| Citation | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 0.450 | 1.000 | 1.000 | 0 |
| Multi-document | 0.500 | 1.000 | 1.000 | 1.000 | 0.708 | 0.500 | 1.000 | 1.000 | 0 |

Citation presence, validity, and support are each `0.976` overall for V3. The single gap is the same false-rejected answerable case, so no answer or citation was emitted.

#### Findings

The baseline does not show V3 outperforming V2 on this broader dataset. V3 loses rank quality primarily on hypertension symptom distinctions and a cardiovascular mechanism distractor. This result is retained as measured; P1-03 does not modify retrieval to improve the score.

High-risk false accepts are:

- `no-answer-004`: asks for a patient-specific antihypertensive dose but returns `WHO-HTN-001`.
- `no-answer-005`: asks for pregnancy-specific licorice safety absent from the corpus but returns general licorice chunks.
- `no-answer-007`: asks for age-specific normal blood-pressure ranges absent from the corpus but returns `WHO-HTN-001`.

The false reject is `synonym-004`, a paraphrase about controlling three cardiovascular risk factors. Retrieval misses `WHO-CVD-001`.

Other missed expected evidence is concentrated in `direct-002`, `synonym-002`, `vague-002`, and `distractor-005`. Multi-document Recall@K is `1.000`, while Top-1 is only `0.500`, showing that evidence is present in the final set but ordering can still improve.

Top-K Jaccard is intentionally low when the engine returns relevant extra chunks beyond the narrow gold set. It should be read together with Hit@K and Recall@K rather than used as a standalone quality score.
