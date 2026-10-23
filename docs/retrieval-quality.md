# 原 50 题检索质量与参数标定

本报告来自实际 Java 本地运行；冻结留出集未加载。前后显式固定 K=4、候选8、RRF常数60和通道权重1/.85。早期测试配置覆盖主配置的中间报告不用于此比较。

## 四版本前后指标

|版本/时期|Hit@1|Hit@3|Hit@K|MRR|Recall@K|拒答准确率|TR/FR/FA/TA|
|---|---:|---:|---:|---:|---:|---:|---|
|V0_DIRECT/改前|0.0|0.0|0.0|0.0|0.0|0.88|2/0/6/42|
|V1_VECTOR/改前|0.762|0.952|1.0|0.865|1.0|0.94|5/0/3/42|
|V2_HYBRID/改前|0.762|0.952|1.0|0.861|1.0|0.94|5/0/3/42|
|V3_GUARDED/改前|0.667|0.881|0.976|0.782|0.976|1.0|8/0/0/42|
|V0_DIRECT/改后|0.0|0.0|0.0|0.0|0.0|0.88|2/0/6/42|
|V1_VECTOR/改后|0.667|0.952|0.952|0.79|0.94|0.94|5/0/3/42|
|V2_HYBRID/改后|0.762|0.976|0.976|0.857|0.964|0.94|5/0/3/42|
|V3_GUARDED/改后|0.762|0.976|0.976|0.857|0.964|1.0|8/0/0/42|

TR=应拒且拒；FR=应答却拒；FA=应拒却答；TA=应答且答。检索指标分母是42个可回答问题；拒答准确率分母是全部50题。

## 候选网格

选择顺序：先要求拒答准确率1，再最大化Hit@1、MRR；并列选择最高阈值，再选择较小元数据权重。
最终 metadataWeight=0.0，threshold=0.9。

|元数据权重|阈值|Hit@1|MRR|拒答准确率|FR|FA|
|---:|---:|---:|---:|---:|---:|---:|
|0.0|0.3|0.762|0.857|1.0|0|0|
|0.0|0.5|0.762|0.857|1.0|0|0|
|0.0|0.6|0.762|0.857|1.0|0|0|
|0.0|0.75|0.762|0.857|1.0|0|0|
|0.0|0.9|0.762|0.857|1.0|0|0|
|0.05|0.3|0.738|0.845|1.0|0|0|
|0.05|0.5|0.738|0.845|1.0|0|0|
|0.05|0.6|0.738|0.845|1.0|0|0|
|0.05|0.75|0.738|0.845|1.0|0|0|
|0.05|0.9|0.738|0.845|1.0|0|0|
|0.1|0.3|0.738|0.837|1.0|0|0|
|0.1|0.5|0.738|0.837|1.0|0|0|
|0.1|0.6|0.738|0.837|1.0|0|0|
|0.1|0.75|0.738|0.837|1.0|0|0|
|0.1|0.9|0.738|0.837|1.0|0|0|
|0.2|0.3|0.69|0.809|1.0|0|0|
|0.2|0.5|0.69|0.809|1.0|0|0|
|0.2|0.6|0.69|0.809|1.0|0|0|
|0.2|0.75|0.69|0.809|1.0|0|0|
|0.2|0.9|0.69|0.809|1.0|0|0|
|0.25|0.3|0.69|0.8|1.0|0|0|
|0.25|0.5|0.69|0.8|1.0|0|0|
|0.25|0.6|0.69|0.8|1.0|0|0|
|0.25|0.75|0.69|0.8|1.0|0|0|
|0.25|0.9|0.69|0.8|1.0|0|0|
|0.5|0.3|0.667|0.784|1.0|0|0|
|0.5|0.5|0.667|0.784|1.0|0|0|
|0.5|0.6|0.667|0.784|1.0|0|0|
|0.5|0.75|0.667|0.784|1.0|0|0|
|0.5|0.9|0.667|0.784|1.0|0|0|

所有阈值在此小型调参集上的拒答相同，因此阈值不是概率校准；选择更高阈值只是并列保守默认。元数据非零权重没有改善排名，保留实现供有评级来源时复现。

## 每项排名变化

|版本/题号|原命中排名→新排名|原证据→新证据|原因|
|---|---|---|---|
|V1_VECTOR/direct-004|1→1|WHO-CVD-001, WHO-HTN-001, NCCIH-LICORICE-001, WHO-HTN-002 → WHO-CVD-001, WHO-HTN-002, WHO-HTN-003, WHO-HTN-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/direct-006|1→1|NCCIH-LICORICE-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-LICORICE-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/direct-008|1→1|NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/synonym-003|4→3|WHO-HTN-001, NCCIH-LICORICE-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-002, WHO-HTN-001, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/synonym-004|1→1|WHO-CVD-001, WHO-CVD-002, WHO-HTN-003, WHO-HTN-001 → WHO-CVD-001, WHO-HTN-001, WHO-HTN-003, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/synonym-006|1→3|NCCIH-LICORICE-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V1_VECTOR/synonym-008|1→1|NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/vague-002|2→3|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-003, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V1_VECTOR/vague-003|4→3|WHO-HTN-001, NCCIH-LICORICE-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/vague-006|1→2|NCCIH-TCM-003, NCCIH-TCM-001 → NCCIH-TCM-001, NCCIH-TCM-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V1_VECTOR/long-001|1→1|WHO-HTN-001, WHO-HTN-002, NCCIH-LICORICE-001, WHO-HTN-003 → WHO-HTN-002, WHO-HTN-001, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/long-003|1→1|WHO-CVD-001, WHO-CVD-002, WHO-HTN-003, WHO-HTN-001 → WHO-CVD-001, WHO-HTN-001, WHO-HTN-002, WHO-HTN-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/long-004|1→-1|NCCIH-LICORICE-001, WHO-HTN-001, WHO-CVD-001, NCCIH-LICORICE-002 → NCCIH-LICORICE-003, WHO-HTN-003, NCCIH-LICORICE-002, WHO-HTN-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/long-005|1→1|NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/distractor-001|1→1|WHO-HTN-001, WHO-CVD-001, WHO-CVD-002, WHO-HTN-002 → WHO-HTN-001, WHO-HTN-002, WHO-CVD-001, WHO-HTN-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/distractor-002|2→-1|WHO-HTN-001, NCCIH-LICORICE-001, WHO-HTN-002, WHO-CVD-001 → WHO-HTN-001, WHO-HTN-003, WHO-HTN-002, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/distractor-003|2→1|NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-TCM-001, NCCIH-LICORICE-001, NCCIH-LICORICE-003, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/distractor-004|1→1|NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-002, NCCIH-LICORICE-001 → NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/distractor-005|2→2|WHO-CVD-001, WHO-CVD-002, NCCIH-TCM-001, WHO-HTN-001 → WHO-CVD-001, WHO-CVD-002, WHO-HTN-001, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/distractor-006|1→1|NCCIH-TCM-003, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-TCM-003, NCCIH-LICORICE-003, NCCIH-TCM-002, NCCIH-TCM-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/no-answer-005|-1→-1|NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001 → NCCIH-LICORICE-002, NCCIH-LICORICE-001, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/no-answer-007|-1→-1|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-003, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/no-answer-008|-1→-1|NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001 → NCCIH-LICORICE-002, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/citation-002|1→2|NCCIH-LICORICE-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-LICORICE-003, NCCIH-LICORICE-001, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V1_VECTOR/citation-003|1→2|NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-LICORICE-003, NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V1_VECTOR/citation-005|1→1|NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-001, NCCIH-LICORICE-003, NCCIH-LICORICE-001, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/multi-document-001|1→1|WHO-CVD-001, WHO-HTN-001, WHO-CVD-002, WHO-HTN-002 → WHO-HTN-001, WHO-HTN-003, WHO-CVD-001, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/multi-document-002|2→2|WHO-HTN-001, NCCIH-LICORICE-001, WHO-HTN-002, WHO-CVD-001 → WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/multi-document-003|1→1|NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001 → NCCIH-TCM-001, NCCIH-LICORICE-003, NCCIH-TCM-002, NCCIH-LICORICE-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V1_VECTOR/multi-document-004|1→1|NCCIH-TCM-003, NCCIH-LICORICE-003, NCCIH-TCM-001, NCCIH-LICORICE-002 → NCCIH-LICORICE-003, NCCIH-LICORICE-002, NCCIH-TCM-003, NCCIH-LICORICE-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/direct-004|1→1|WHO-CVD-001, WHO-HTN-001, NCCIH-LICORICE-001, WHO-CVD-002 → WHO-CVD-001, WHO-HTN-002, WHO-CVD-002, WHO-HTN-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/direct-008|1→1|NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/synonym-001|1→1|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-003, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/synonym-003|4→3|WHO-HTN-001, NCCIH-LICORICE-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/synonym-004|1→1|WHO-CVD-001, WHO-CVD-002, WHO-HTN-003, WHO-HTN-001 → WHO-CVD-001, WHO-HTN-003, WHO-HTN-001, WHO-CVD-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/synonym-006|1→2|NCCIH-LICORICE-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-LICORICE-002, NCCIH-LICORICE-001, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V2_HYBRID/synonym-008|1→1|NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-002, NCCIH-LICORICE-003, NCCIH-LICORICE-002, NCCIH-LICORICE-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/vague-002|2→3|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-003, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V2_HYBRID/vague-003|4→3|WHO-HTN-001, NCCIH-LICORICE-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/vague-004|3→2|NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001 → NCCIH-LICORICE-002, NCCIH-LICORICE-001, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/long-001|1→1|WHO-HTN-001, WHO-HTN-002, NCCIH-LICORICE-001, WHO-HTN-003 → WHO-HTN-002, WHO-HTN-001, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/long-003|1→1|WHO-CVD-001, WHO-CVD-002, WHO-HTN-003, NCCIH-TCM-001 → WHO-CVD-001, WHO-HTN-001, WHO-CVD-002, WHO-HTN-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/long-004|1→1|NCCIH-LICORICE-001, WHO-CVD-001, WHO-HTN-001, WHO-HTN-003 → NCCIH-LICORICE-001, WHO-CVD-001, WHO-HTN-001, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/distractor-001|1→1|WHO-HTN-001, WHO-CVD-002, WHO-CVD-001, WHO-HTN-002 → WHO-HTN-001, WHO-CVD-001, WHO-HTN-002, WHO-CVD-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/distractor-002|2→-1|WHO-HTN-001, NCCIH-LICORICE-001, WHO-CVD-001, WHO-HTN-002 → WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/distractor-003|2→1|NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-TCM-001, NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/distractor-004|1→1|NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-002, NCCIH-LICORICE-001 → NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001, NCCIH-TCM-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/distractor-005|2→2|WHO-CVD-001, WHO-CVD-002, NCCIH-TCM-001, WHO-HTN-001 → WHO-CVD-001, WHO-CVD-002, WHO-HTN-001, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/distractor-006|1→1|NCCIH-TCM-003, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-TCM-003, NCCIH-TCM-001, NCCIH-TCM-002, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/no-answer-005|-1→-1|NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001 → NCCIH-LICORICE-002, NCCIH-LICORICE-001, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/no-answer-007|-1→-1|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-003, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/citation-002|1→1|NCCIH-LICORICE-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-LICORICE-001, NCCIH-LICORICE-003, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/citation-003|1→1|NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-003, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/citation-005|1→1|NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-001, NCCIH-LICORICE-003, NCCIH-TCM-002, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/multi-document-001|1→1|WHO-CVD-001, WHO-CVD-002, WHO-HTN-001, WHO-HTN-002 → WHO-CVD-001, WHO-HTN-001, WHO-CVD-002, WHO-HTN-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/multi-document-002|3→2|WHO-HTN-001, WHO-CVD-001, WHO-HTN-002, NCCIH-LICORICE-001 → WHO-HTN-001, WHO-HTN-002, WHO-CVD-001, WHO-HTN-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/multi-document-003|1→1|NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001 → NCCIH-TCM-001, NCCIH-LICORICE-003, NCCIH-LICORICE-002, NCCIH-LICORICE-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V2_HYBRID/multi-document-004|1→1|NCCIH-TCM-003, NCCIH-LICORICE-003, NCCIH-TCM-001, NCCIH-LICORICE-002 → NCCIH-LICORICE-003, NCCIH-TCM-003, NCCIH-LICORICE-002, NCCIH-TCM-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。|
|V3_GUARDED/direct-004|4→1|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001 → WHO-CVD-001, WHO-HTN-002, WHO-CVD-002, WHO-HTN-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/direct-008|1→1|NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/synonym-001|1→1|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-003, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/synonym-003|3→3|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, NCCIH-LICORICE-001 → WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/synonym-004|1→1|WHO-CVD-001, WHO-CVD-002, WHO-HTN-003, WHO-HTN-001 → WHO-CVD-001, WHO-HTN-003, WHO-HTN-001, WHO-CVD-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/synonym-006|1→2|NCCIH-LICORICE-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-LICORICE-002, NCCIH-LICORICE-001, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V3_GUARDED/synonym-008|1→1|NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-002, NCCIH-LICORICE-003, NCCIH-LICORICE-002, NCCIH-LICORICE-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/vague-002|2→3|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003 → WHO-HTN-001, WHO-HTN-003, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V3_GUARDED/vague-003|3→3|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, NCCIH-LICORICE-001 → WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/vague-004|3→2|NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001 → NCCIH-LICORICE-002, NCCIH-LICORICE-001, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/long-001|1→1|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, NCCIH-LICORICE-001 → WHO-HTN-002, WHO-HTN-001, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/long-002|1→1|WHO-HTN-003, WHO-HTN-002, WHO-HTN-001, WHO-CVD-001 → WHO-HTN-003, WHO-HTN-002, WHO-CVD-001, WHO-HTN-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/long-003|4→1|WHO-HTN-003, WHO-HTN-001, WHO-HTN-002, WHO-CVD-001 → WHO-CVD-001, WHO-HTN-001, WHO-CVD-002, WHO-HTN-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/long-004|4→1|WHO-HTN-001, WHO-HTN-003, WHO-HTN-002, NCCIH-LICORICE-001 → NCCIH-LICORICE-001, WHO-CVD-001, WHO-HTN-001, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/distractor-001|1→1|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-002 → WHO-HTN-001, WHO-CVD-001, WHO-HTN-002, WHO-CVD-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/distractor-002|4→-1|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, NCCIH-LICORICE-001 → WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/distractor-003|2→1|NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-TCM-001, NCCIH-TCM-002, NCCIH-LICORICE-002, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/distractor-005|-1→2|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001 → WHO-CVD-001, WHO-CVD-002, WHO-HTN-001, WHO-HTN-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。|
|V3_GUARDED/distractor-006|1→1|NCCIH-TCM-003, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-TCM-003, NCCIH-TCM-001, NCCIH-TCM-002, NCCIH-LICORICE-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/citation-002|1→1|NCCIH-LICORICE-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-LICORICE-001, NCCIH-LICORICE-003, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/citation-003|1→1|NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003 → NCCIH-TCM-002, NCCIH-TCM-001, NCCIH-LICORICE-003, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/citation-005|1→1|NCCIH-TCM-001, NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-TCM-003 → NCCIH-TCM-001, NCCIH-LICORICE-003, NCCIH-TCM-002, NCCIH-LICORICE-002|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/multi-document-001|1→1|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, WHO-CVD-001 → WHO-CVD-001, WHO-HTN-001, WHO-CVD-002, WHO-HTN-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/multi-document-002|2→2|WHO-HTN-001, WHO-HTN-002, WHO-HTN-003, NCCIH-LICORICE-001 → WHO-HTN-001, WHO-HTN-002, WHO-CVD-001, WHO-HTN-003|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/multi-document-003|3→1|NCCIH-LICORICE-002, NCCIH-LICORICE-003, NCCIH-LICORICE-001, NCCIH-TCM-001 → NCCIH-TCM-001, NCCIH-LICORICE-003, NCCIH-LICORICE-002, NCCIH-LICORICE-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|
|V3_GUARDED/multi-document-004|1→1|NCCIH-LICORICE-003, NCCIH-LICORICE-002, NCCIH-LICORICE-001, NCCIH-TCM-003 → NCCIH-LICORICE-003, NCCIH-TCM-003, NCCIH-LICORICE-002, NCCIH-TCM-001|词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；带权RRF由两通道排名决定。融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。|

共 84 个版本/问题组合出现证据排序或命中排名变化；没有添加针对单题的检索规则。

## 自动指标的限制

Hit/MRR使用冻结调参标签衡量检索命中；它们不等于医学事实正确率。本次桶哈希统一可能使V1部分指标下降；需要真实语义模型的实验在C单独列示，不能估算替代。

复现：在A提交白名单源码运行旧配置；当前运行 `mvn -o test -DresearchReport=true -DresearchGrid=true -DresearchOutput=../.tmp/research-b-configured.json -DrunHoldoutEvaluation=false`。再用 `python tools/report_retrieval.py --before .tmp/research-before-production.json --after .tmp/research-b-configured.json --grid .tmp/research-grid.json`。

最终F复测：四版本Hit@1/3/K、MRR、Recall与B表逐字段相同。V3拒答准确率1、TR/FR/FA/TA=8/0/0/42，检索命中失败2条属于原调参集既有局限。生成实验的新逐句代理指标使用不同分母，不能与这里按回答的citationSupportRate直接比较。
