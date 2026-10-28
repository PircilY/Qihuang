# EXPANDED 真实服务端到端探测

SHA-256 hash 检索；7 个固定问题；`RAG_QUERY_BRIDGE=false`。
按接口 `documentTitle` / `quote` 核验前三条引用。覆盖数为全库标题/正文含任一所列语言术语的片段数。有覆盖但前三条未命中、HTTP/响应异常或实际引擎/版本不符均使探测失败；无覆盖不冒充检索退化。
词面命中仅是回归代理，不证明医学相关性、疗效或回答正确性。拒答原因和检索轨迹保留在同名 JSON 报告中。

G 之前代码对照：`delivery-c9652d3` 同环境服务对照未执行；只展示当前服务结果，不估算旧版结果。

|问题|覆盖片段数|前三条命中|状态|引擎|版本|前三条引用标题 / 错误|
|---|---:|---|---|---|---|---|
|黄芪治疗慢性心力衰竭的临床疗效|86|False|有覆盖但未命中|llama-index|V3_GUARDED|[Shenge powder inhibits myocardial fibrosis in rats with post-myocardial infarction heart failure through LOXL2/TGF-β1/IL-11 signaling pathway].<br>[Effects and Efficacy of Wenyang Huayin Yangxin Prescription on the Traditional Chinese Medicine Syndromes of Patients With Chronic Heart Failure Combined With Diuretic Resistance].<br>[Calcium Dibutyryl Adenosine Cyclophosphate Enhances the Effect of Metoprolol in Treating Older Adults With Heart Failure Combined With Arrhythmia].|
|丹参对冠心病心绞痛有什么作用|96|False|有覆盖但未命中|llama-index|V3_GUARDED||
|中医药治疗高血压的研究进展|894|True|命中|llama-index|V3_GUARDED|经典型骨髓增殖性肿瘤伴高血压的临床研究进展 Clinical research progress on classic myeloproliferative neoplasms with hypertension<br>经典型骨髓增殖性肿瘤伴高血压的临床研究进展 Clinical research progress on classic myeloproliferative neoplasms with hypertension<br>经典型骨髓增殖性肿瘤伴高血压的临床研究进展 Clinical research progress on classic myeloproliferative neoplasms with hypertension|
|气虚血瘀证心衰的辨证论治|921|False|有覆盖但未命中|llama-index|V3_GUARDED||
|温阳化饮养心方对心衰利尿剂抵抗的影响|14|True|命中|llama-index|V3_GUARDED|[Effects and Efficacy of Wenyang Huayin Yangxin Prescription on the Traditional Chinese Medicine Syndromes of Patients With Chronic Heart Failure Combined With Diuretic Resistance].<br>[Effects and Efficacy of Wenyang Huayin Yangxin Prescription on the Traditional Chinese Medicine Syndromes of Patients With Chronic Heart Failure Combined With Diuretic Resistance].<br>[Effects and Efficacy of Wenyang Huayin Yangxin Prescription on the Traditional Chinese Medicine Syndromes of Patients With Chronic Heart Failure Combined With Diuretic Resistance].|
|astragalus chronic heart failure efficacy|86|True|命中|llama-index|V3_GUARDED|The traditional Chinese medicines treat chronic heart failure and their main bioactive constituents and mechanisms.<br>The traditional Chinese medicines treat chronic heart failure and their main bioactive constituents and mechanisms.<br>The traditional Chinese medicines treat chronic heart failure and their main bioactive constituents and mechanisms.|
|Danshen coronary heart disease angina|96|False|有覆盖但未命中|llama-index|V3_GUARDED||

验收结果：3/7 项通过，4 项失败。
