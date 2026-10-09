# Current Reproducibility Snapshot

Snapshot date: 2026-10-08

> This is a snapshot of conditions and files that can be verified in the current working tree. It is **not** a claim that these conditions exactly reproduce the historical P2-02-2 or P2-02-3 Experiment A execution. The frozen Experiment A result remains PASS with `failureCount = 1`; this document does not recalculate or alter it.

## Purpose and experiment references

This record answers which evaluation data, knowledge files, configuration, engine paths, and Git state are currently present. The formal P2-02-2 → Experiment A results remain in [`evaluation-baseline.md`](evaluation-baseline.md). Both experiment states remain frozen; Experiment B has not been conducted.

## Evaluation dataset

- Runtime dataset file: `backend/src/main/resources/data/evaluation-cases.json`
- Current size: 18,392 bytes
- SHA-256: `9A77050A4C2C476DEA3D675F4252C4331B8D956DE9DD5FE77AFB51B2473CB907`
- Parsed cases: 50 unique question records.
- `EvaluationService` iterates all four `IterationVersion` values (`V0_DIRECT`, `V1_VECTOR`, `V2_HYBRID`, `V3_GUARDED`), producing 50 × 4 = 200 case-version results per complete run.
- The versioned dataset is `2.0.0` in `EvaluationService`.
- The current working-tree file has the 50-case dataset, while Git reports it modified relative to the current index/HEAD state; the committed `HEAD` is therefore not the source of the current 50-case bytes. Preserve the working-tree file and its hash when reproducing this current snapshot.

The dataset SHA-256 matches the hash recorded for the earlier P1-03 historical snapshot in `evaluation-baseline.md`. This identifies the current file bytes against that recorded hash; it does not by itself prove which data bytes were loaded for Experiment A.

## Knowledge base

The current seed knowledge base contains 4 documents and 11 chunks. The working-tree files and hashes are:

| Role | File | Size (bytes) | SHA-256 |
| --- | --- | ---: | --- |
| Runtime chunk records and metadata | `backend/src/main/resources/data/knowledge-base.json` | 10,558 | `7C4E5EC47948325EB4B17727CBC392839B76AA3FE69E1B9E1B00E7B37BA2E570` |
| Runtime document metadata | `backend/src/main/resources/data/knowledge-documents.json` | 3,762 | `68B448C9BE870D55F6B1B95F8FE2B7BA247B1A65A1BD1C35C9D6391AC58CAC67` |
| Canonical seed source/excerpts | `data/sources.json` | 6,669 | `DE3CBBBC42E64D8401991213A84708D9B1F096676280E30B0A37599FA3AD50D1` |

Document IDs: `WHO-HTN`, `WHO-CVD`, `NCCIH-LICORICE`, `NCCIH-TCM`.

Chunk IDs: `WHO-HTN-001`–`WHO-HTN-003`, `WHO-CVD-001`–`WHO-CVD-002`, `NCCIH-LICORICE-001`–`NCCIH-LICORICE-003`, and `NCCIH-TCM-001`–`NCCIH-TCM-003`.

The chunk JSON hash matches the knowledge snapshot hash in the historical P1-03 record. The checked-in source files provide a current reproducible seed corpus, but the Experiment A record itself does not identify a contemporaneous snapshot hash; equality with a historical hash is not proof that the same runtime snapshot was used during that later experiment.

## Evaluation configuration and engine

- Evaluation entry: `EvaluationService.run()` loads the dataset and calls `RagOrchestrationService.askExtractive()` for each case/version pair. Extractive mode avoids model-generation variation; it does not pin retrieval-engine selection.
- Maven test configuration: `backend/src/test/resources/application.yml` sets `app.rag-engine.mode: local`, so the default Maven evaluation path uses Java Local Engine, subject to external Spring/environment overrides.
- Normal application/API configuration: `backend/src/main/resources/application.yml` defaults `RAG_ENGINE_MODE` to `auto` and `RAG_FALLBACK_TO_LOCAL` to `true`. `auto` tries Python FastAPI/LlamaIndex and may fall back to Java Local Engine. A run can therefore use different paths if remote availability changes during execution.
- **Experiment A historical engine: unconfirmed.** The historical result record does not persist the actual engine mode or per-result engine. It is not valid to infer Java or Python from the current defaults or test profile.

## V3_GUARDED configuration

Current configuration in `backend/src/main/resources/application.yml`:

| Parameter | Current value |
| --- | ---: |
| Candidate K per retriever | 8 |
| Final K | 4 |
| Weighted RRF constant | 60 |
| Lexical weight | 1.0 |
| Vector weight | 0.85 |
| Minimum evidence score threshold | 0.016 |
| Disease metadata bonus | +0.22 |
| Syndrome metadata bonus | +0.20 |
| Formula metadata bonus | +0.18 |
| Herb metadata bonus | +0.10 |
| Evidence-level bonus | A +0.12; B +0.08; C +0.04; other/unrated +0.00 |

The V3 path includes candidate filtering, weighted rank fusion and chunk-ID deduplication, metadata reranking, a score threshold, and a separate Evidence Coverage Gate. The Gate checks whether selected evidence covers supported question attributes/relations; it is not a ranking bonus. Java and Python retain implementation-specific internals; the Retrieval Contract checks key retrieval behavior over 14 fixtures, not full 200-result equivalence.

## Git state at snapshot capture

- Branch: `main`
- HEAD: `af7689a3620cef5b45c71463e363c3509e896dfe`
- HEAD message: `岐黄脉证框架业务代码开发`
- Working tree before adding this snapshot: **not clean**; numerous tracked files were modified and new files were untracked, including retrieval/evaluation implementation, tests, the 50-case dataset, and documentation.
- Experiment A's implementation was not completely frozen in a Git commit. `HEAD` is the repository's current commit, not a complete source identifier for the frozen Experiment A result. This snapshot document is itself newly created and untracked until separately handled by the project owner.

No Git operation was used to clean, reset, stage, or commit the workspace.

## Reproducibility limitations

1. **Experiment A cannot be exactly reconstructed from its result record alone.** Its actual engine mode, per-case engine, and contemporary dataset/knowledge hashes were not recorded with the frozen metrics.
2. **Current API and Maven test execution differ by configuration.** The API defaults to `auto` with fallback; the Maven test resource defaults to `local`.
3. **The working tree is not clean.** The 50-question dataset and the code/configuration needed to run it are not all represented by the current `HEAD` commit.
4. **This is a current snapshot, not a retroactive experiment manifest.** The hashes above make the presently checked-in seed files identifiable, but do not assert they were byte-for-byte the inputs to Experiment A.

For the frozen metrics and case outcomes, use [`evaluation-baseline.md`](evaluation-baseline.md). This snapshot does not replace that record or change the frozen PASS conclusion.
