# Independent retrieval audit

24 positive source-nominated questions and 6 negative questions were frozen before retrieval.
This is a small, non-random retrieval check, not clinical correctness or a representative benchmark.
22 positive questions are English; 2 are Chinese. No original50 or frozen holdout labels were used.

Labels SHA-256: `7f12d40d06eeeb9a7875ba665ca726624b8376065804eec0afcbf59d074919c3`.

A valid Top3 hit requires the nominated document AND both nominated anchors in the cited body.
Citation support requires document identity and an exact quote from the indexed source.
Empty citation lists do not count as supported cited responses.

| Phase | Documents / chunks | Valid Top3 | Negative refusal | Supported cited responses | Median ms | P95 ms |
|---|---:|---:|---:|---:|---:|---:|
| before-fix | 100 / 4036 | 2/24 | 4/6 | 21/21 | 905.4 | 1312.1 |
| after-fix | 100 / 4036 | 2/24 | 4/6 | 21/21 | 853.0 | 1315.6 |
| after-safety-fix | 100 / 4036 | 2/24 | 6/6 | 19/19 | 824.6 | 1251.1 |
| after-corpus | 141 / 6810 | 16/24 | 6/6 | 24/24 | 1464.9 | 2141.5 |

## Interpretation

The safety fix removes two missed refusals: stopping antihypertensives in favor of Danshen, and requesting a personal herbal prescription.
The metadata-only intermediate run is retained; it did not fix these safety cases.
Corpus growth changes valid Top3 from 2/24 to 16/24 under the same final code.
All phases use hash embeddings, bridge=false and evidence threshold=0.9. No paid model or semantic model download.
Citation source support does not prove that a source is clinically correct or that every generated claim is entailed.
Timings were measured alongside other tests and are descriptive only. Expansion increased median latency; no performance improvement is claimed.

## Remaining Positive Misses

| Case | Question | Refused | Retrieved document IDs |
|---|---|---|---|
| P04 | Danshen compounds coronary heart disease biochemical indices study | False | EN-PMC4913020 |
| P08 | 中医高血压治疗趋势与病机的文献有哪些？ | False | ZH-PMC10440626, ZH-PMC12709063, ZH-PMC11839362, ZH-PMC12439656 |
| P13 | Chinese medicine syndrome evolution heart failure rats LAD ligation plasma metabonomics | False | EN-PMC4227006 |
| P14 | Traditional Chinese medicine heart failure symptom scale questionnaire validation | False | EN-PMC5010704, EN-PMC10251330 |
| P19 | Qiliqiangxin doxorubicin induced heart failure cardiac remodelling rats | False | EN-PMC7301709 |
| P21 | Gualou Xiebai Banxia Decoction atherosclerosis microbiota bile acid signatures | False | EN-PMC12908498 |
| P22 | Taohong Siwu Decoction coronary heart disease non-targeted metabolomic profiling | False | EN-PMC7227603 |
| P24 | 参芪复方在糖尿病心血管高危人群中的试验方案是什么？ | False | ZH-PMC11839373, ZH-PMC13305824, ZH-PMC11839350, ZH-PMC6817436 |

## Reproduction

Do not rerun `--freeze` or replace the existing labels.
Evaluate current code with `python tools/audit_relevance.py --output runtime/audit-relevance-current.json`.
The before-code phase used the whitelisted app source snapshot from c2da130 and its original expanded corpus.
The original seven-question probe and its historical report remain unchanged; the new run is in `audit-service-probe.md`.
That probe uses any listed term, so a generic hypertension hit alone is not evidence of dual-topic relevance.
