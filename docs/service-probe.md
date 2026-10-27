# EXPANDED 真实服务端到端探测

SHA-256 hash 检索；固定7个问题；引用正文/标题核心词面匹配。语料覆盖数为全库含任一语言术语的片段数。检索异常仅在语料覆盖大于0且前三条未命中时作为回归失败。

G 之前代码对照：`delivery-c9652d3` 当前代码同环境对照未执行；报告只展示当前服务结果，不估算旧版结果。

|问题|覆盖片段数|前三条命中|状态|引擎|版本|前三条引用标题|
|---|---:|---|---|---|---|---|
|黄芪治疗慢性心力衰竭的临床疗效|86|False|有覆蓋但未命中|llama-index|V3_GUARDED|[Shenge powder inhibits myocardial fibrosis in rats with post-myocardial infarction heart failure through LOXL2/TGF-β1/IL-11 signaling pathway].<br>[Effects and Efficacy of Wenyang Huayin Yangxin Prescription on the Traditional Chinese Medicine Syndromes of Patients With Chronic Heart Failure Combined With Diuretic Resistance].<br>[Calcium Dibutyryl Adenosine Cyclophosphate Enhances the Effect of Metoprolol in Treating Older Adults With Heart Failure Combined With Arrhythmia].|
|丹参对冠心病心绞痛有什么作用|96|False|有覆蓋但未命中|llama-index|V3_GUARDED||
|中医药治疗高血压的研究进展|894|True|命中|llama-index|V3_GUARDED|经典型骨髓增殖性肿瘤伴高血压的临床研究进展 Clinical research progress on classic myeloproliferative neoplasms with hypertension<br>经典型骨髓增殖性肿瘤伴高血压的临床研究进展 Clinical research progress on classic myeloproliferative neoplasms with hypertension<br>经典型骨髓增殖性肿瘤伴高血压的临床研究进展 Clinical research progress on classic myeloproliferative neoplasms with hypertension|
|气虚血瘀证心衰的辨证论治|921|False|有覆蓋但未命中|llama-index|V3_GUARDED||
|温阳化饮养心方对心衰利尿剂抵抗的影响|14|False|有覆蓋但未命中|llama-index|V3_GUARDED|[Effects and Efficacy of Wenyang Huayin Yangxin Prescription on the Traditional Chinese Medicine Syndromes of Patients With Chronic Heart Failure Combined With Diuretic Resistance].<br>[Effects and Efficacy of Wenyang Huayin Yangxin Prescription on the Traditional Chinese Medicine Syndromes of Patients With Chronic Heart Failure Combined With Diuretic Resistance].<br>[Effects and Efficacy of Wenyang Huayin Yangxin Prescription on the Traditional Chinese Medicine Syndromes of Patients With Chronic Heart Failure Combined With Diuretic Resistance].|
|astragalus chronic heart failure efficacy|86|False|有覆蓋但未命中|llama-index|V3_GUARDED|The traditional Chinese medicines treat chronic heart failure and their main bioactive constituents and mechanisms.<br>The traditional Chinese medicines treat chronic heart failure and their main bioactive constituents and mechanisms.<br>The traditional Chinese medicines treat chronic heart failure and their main bioactive constituents and mechanisms.|
|Danshen coronary heart disease angina|96|False|有覆蓋但未命中|llama-index|V3_GUARDED||
