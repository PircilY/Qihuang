# 生成对照实验

2026-10-23：默认关闭实验路由；完整 mock 验证可在无密钥、无网络环境运行。真实生成模型和独立评审模型未配置，不能据此宣称大模型事实准确率提高。

## 已执行：原 50 题中的 42 个可回答问题

固定种子42、temperature=0、BASELINE、本地引擎。126行全部保留，A/B 模型未配置的失败逐行导出到 runtime/generation-experiment/results.jsonl 与 CSV，C 实际执行 V3 抽取式。以下是本次测量，时延受本机其他测试负载影响。

|模式|指标|均值|95% bootstrap CI|n|
|---|---|---:|---|---:|
|A|sentenceSupport|0.0000|[0.0000, 0.0000]|42|
|A|uncitedSentences|0.0000|[0.0000, 0.0000]|42|
|A|validReferences|0.0000|[0.0000, 0.0000]|42|
|A|bigramOverlap|0.0000|[0.0000, 0.0000]|42|
|A|refusalRate|0.0000|[0.0000, 0.0000]|42|
|A|answerLength|0.0000|[0.0000, 0.0000]|42|
|A|latencyMs|1.4690|[0.7249, 2.5877]|42|
|A|failureRate|1.0000|[1.0000, 1.0000]|42|
|B|sentenceSupport|0.0000|[0.0000, 0.0000]|42|
|B|uncitedSentences|0.0000|[0.0000, 0.0000]|42|
|B|validReferences|0.0000|[0.0000, 0.0000]|42|
|B|bigramOverlap|0.0000|[0.0000, 0.0000]|42|
|B|refusalRate|0.0000|[0.0000, 0.0000]|42|
|B|answerLength|0.0000|[0.0000, 0.0000]|42|
|B|latencyMs|5.2838|[3.8309, 7.1985]|42|
|B|failureRate|1.0000|[1.0000, 1.0000]|42|
|C|sentenceSupport|0.7710|[0.7520, 0.7861]|42|
|C|uncitedSentences|0.2290|[0.2139, 0.2480]|42|
|C|validReferences|1.0000|[1.0000, 1.0000]|42|
|C|bigramOverlap|0.7745|[0.7452, 0.7999]|42|
|C|refusalRate|0.0000|[0.0000, 0.0000]|42|
|C|answerLength|355.7381|[292.3095, 418.9524]|42|
|C|latencyMs|5.9181|[4.5358, 7.7686]|42|
|C|failureRate|0.0000|[0.0000, 0.0000]|42|

## 指标与边界

- sentenceSupport：剔除固定免责声明后的非空句子中，该句全部引用有效且分别由原文支持的比例。规则检查数值、极性、至少两个有效文本特征；不是自然语言蕴含证明。
- uncitedSentences：没有 [数字] 引用的句子比例。validReferences：所有引用出现次数中对应到本行有效文档/片段的比例。缺少引用时有效比例记0。
- bigramOverlap：回答唯一相邻字母/数字二元组在证据并集中的覆盖率，不跨标点，不解释为事实正确率。
- refusalRate：拒答行比例；failureRate：运行失败行比例，失败不当作拒答。A 使用拒答短语启发式；B/C 使用服务拒答标志。answerLength 按 Unicode code point；latencyMs 记录模式整体运行，评审耗时另不计入。
- 每模式按问题行有放回 bootstrap 1000次，百分位2.5%/97.5%区间。空回答内容指标为0；模型失败、评审失败均显式保留。judge关闭时 judgeSupport=0为占位。
- A 不接收证据；B接收与C同配置的V3检索证据。A/B同模型、同temperature、同max_tokens=2048。急症和个体医疗请求保留前置拦截。C不调用模型。抽取式报告结构也参与分句，标题等可能降低句支持率；编号与空格后的引用保持归属原句。

## Mock 验收

可编程模型对A返回无引用占位回答、对B返回带[1]占位回答。测试确认A无引用句比例1，B逐句支持率1、有效引用比例1，C二元组覆盖1；超长引用、无依据句、bootstrap可复现、模型缺失、评审配置缺失、默认路由关闭与同源拒绝均有断言。夹具结果仅存 tools/fixtures/generation，不是医学结果。

## 运行方法

```powershell
# 离线重现原可回答问题，A/B明确记未配置模型，不加载留出集
mvn -o -f backend/pom.xml -Dtest=ResearchGenerationReportTest -DgenerationReport=../.tmp/generation-original.json -DrunHoldoutEvaluation=false test

# 有条件时启用真实生成实验（配置密钥来自进程环境，不写仓库）
$env:GENERATION_EXPERIMENT_ENABLED="true"
$env:MODEL_API_BASE="http://127.0.0.1:9000/v1"
$env:MODEL_NAME="your-installed-model"
# 启动 Java 后：
python tools/generation_experiment.py --limit 42 --seed 42 --temperature 0 --report runtime/generation-experiment/report.md
# 自定义 JSON 或 JSONL: 每项 {"id":"...","question":"..."}；工具拒绝冻结文件名
python tools/generation_experiment.py --questions tools/fixtures/generation-questions.jsonl --limit 1
```

POST /api/v1/experiments/generation 接收 questions（可省略）、limit、seed、temperature、corpus；返回metadata/rows/summary。客户端工具只读取用户选择的问题文件，记录文件 SHA256，导出完整 JSONL/CSV 与报告。CSV 对潜在公式前缀加文本保护。

独立评审使用 JUDGE_ENABLED=true、JUDGE_API_BASE、JUDGE_MODEL、JUDGE_API_KEY、JUDGE_TEMPERATURE（默认0）；远程评审地址也需在 MODEL_ALLOWED_BASE_URLS 中。评审不继承生成凭据，报告记录提示全文、温度、模型是否同名及逐句结果/失败。

完整提示词：A见 GenerationExperimentService.DIRECT_PROMPT；B见 ModelAnswerService.SYSTEM_PROMPT；judge见 LlmCitationJudge.PROMPT，运行导出的metadata也包含原文。真实模型比较与语义评审属可选后续增强；当前没有真实模型结果。
