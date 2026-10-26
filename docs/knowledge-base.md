# 知识库统计与导入说明

由离线脚本确定性生成；不读取任何评测集。统计仅描述数据数量和输入标签，不等同于医学事实或临床证据认证。

## BASELINE

文档数 4；片段数 11；平均正文长度 147.91 字符。

|维度|分布|
|---|---|
|sourceTypes|健康资料: 4|
|years|2019: 1; 2021: 1; 2025: 2|
|evidenceLevels|未评级: 4|

已有构建产物未在此审计中额外过滤：过滤0条；来源清洗与许可状态由原来源台账维护。

## EXPANDED

文档数 100；片段数 4036；平均正文长度 612.29 字符。

|维度|分布|
|---|---|
|sourceTypes|research article: 100|
|years|2010: 1; 2011: 1; 2012: 5; 2013: 4; 2014: 1; 2015: 6; 2016: 4; 2017: 4; 2018: 5; 2019: 4; 2020: 6; 2021: 2; 2023: 4; 2024: 16; 2025: 21; 2026: 16|
|evidenceLevels|未评级: 100|

已有构建产物未在此审计中额外过滤：过滤0条；来源清洗与许可状态由原来源台账维护。

阶段 H 自动词典名称匹配覆盖：
- herbs：文档标题 0/100（0.00%）；片段 342/4036（8.47%）。
- formulas：文档标题 1/100（1.00%）；片段 111/4036（2.75%）。
- syndromes：文档标题 0/100（0.00%）；片段 41/4036（1.02%）。
Disease 使用来源台账 topicTags 并追加标题/片段中词典命中名称。命中结果只表示共享词典名称出现；metadataSource 标示来源台账和/或词典匹配；不是人工标注，可能漏标或误标，不推断疗效、剂量、诊断或医学关系。

## 使用与数据格式

CSV首行为字段名；JSONL每行一个对象；TXT以key: value元数据头、独立---行及正文组成。
必填：title/sourceType/publisher/publicationYear/licenseNote/sourceUrl/content。来源类型支持典籍、临床文献、指南、方药、病案，也保留现有资料类型。
可选：disease/syndromes/formulas/herbs/evidenceLevel/section/pageStart/pageEnd。标签CSV用|或;分隔。证据等级默认未评级，只保存来源声明，不自动评级。
病案/方药输入可含chiefComplaint/syndrome/composition/efficacy/history/treatment或对应中文字段；固定标签模板进入片段，原字段保存在structured-records.json侧车，不改变Java/Python既有片段schema。
--redact脱敏手机号、证件号、邮箱和带标签姓名/住址等；启发式脱敏不能保证识别全部隐私。--check只验证已有输出不写入。
未提供采集时间使用1970-01-01哨兵而非编造采集时间。重复报告不删除内容，导入正式运行库仍遵循Java去重规则。

```powershell
python tools/import_knowledge.py tools/fixtures/import.jsonl --output .tmp/import-preview --redact
python tools/import_knowledge.py tools/fixtures/import.jsonl --output .tmp/import-preview --redact --check
python tools/import_knowledge.py --audit --report docs/knowledge-base.md
python tools/import_knowledge.py --audit --report docs/knowledge-base.md --check
```
