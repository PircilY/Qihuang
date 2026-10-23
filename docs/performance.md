# 查询性能

## B 改动前实测

2026-10-23，Java 本地 V3；预热一次、连续 20 次；问题使用原 50 题语料范围内的“高血压患者没有症状也需要检查血压吗？”。计时涵盖检索与抽取答案；中位数为排序样本第 11 项，p95 为第 19 项。同机结果不能视为服务 SLA。

|语料|切片数|中位数 ms|p95 ms|
|---|---:|---:|---:|
|BASELINE|11|3.1929|4.5962|
|EXPANDED|4036|1940.2184|2330.7206|

复现：`cd backend; mvn -o test -DresearchReport=true -DresearchOutput=../.tmp/research-before.json -DrunHoldoutEvaluation=false`。无需网络或模型密钥。
