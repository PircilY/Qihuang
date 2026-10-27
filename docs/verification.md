# 交付验收记录

日期：2026-09-30。范围：单机文献检索研究工具，非临床诊疗系统。

## 已验证

- Maven `test`：56 项 Java 测试通过；随后 `-DskipTests package` 成功生成可执行 JAR。
- Python pytest：22 项通过，保留一个 Starlette/httpx 弃用告警，不影响本轮测试。
- Java 实际测试运行时为 21.0.5，编译目标为 Java 17；没有声称本机测试使用 Java 17。CI 配置使用 Java 17。
- `node --check`、`git diff --check`、`docker compose config --quiet` 通过。
- 4 份来源文档、11 条摘录与来源清单一致，摘录及章节在下载的官网 HTML 中逐条核验通过。
- Chrome / Playwright：1440、1280、390、320 像素宽度的五个视图没有页面横向溢出，JavaScript 页面异常为零。表格及手机导航使用各自的滚动区域。
- 模型配置弹窗在上述四种宽度下无横向溢出，支持 Escape 关闭与焦点恢复。配置前后工作台网格的位置和尺寸偏差小于 1 像素，原有布局保持不变。
- 本地协议测试覆盖连接测试、保存、刷新恢复、密钥不回显及不进入浏览器存储、证据传递、急症不调用模型、鉴权失败与重试、清除配置。该测试使用模拟服务，只验证协议和交互，不作为真实模型效果证明。
- 使用用户授权的 DeepSeek 账号查询实际模型列表后接入 `deepseek-flash`。真实端到端问题「高血压患者没有症状也需要检查血压吗？」通过 Java、Python 检索与 DeepSeek 生成链路，返回回答及 3 条 WHO 来源记录；浏览器测试记录的接口延迟为 3086 毫秒。结果含 `generation=llm` 与实际模型 ID，桌面和手机页面均验证通过。此为单次功能测试，不代表性能基准或医学准确性评估。
- 实际 Java 到 Python 链路返回可追溯引用；问答、四版本对比、急症拒答、来源链接、关键词筛选、无结果、错误重试和 48 条样本级评估显示正常。
- 在独立端口与独立快照执行真实 NCCIH 原文导入，重复请求返回 409，重启后文档校验值和片段正文保持一致。测试数据没有写入交付实例。

截图及浏览器结果在本机 `.tmp/ui`、`.tmp/model-ui`、`.tmp/live-model`，Maven 报告在 `backend/target/surefire-reports`。模型密钥仅通过进程环境提供，未写入仓库；当前进程退出后需要重新配置。

## G–K stability and skipped-test audit (2026-10-26)

Default Java test run: 153 tests, 0 failures, 0 errors, 5 conditionally skipped tests. The five skips are intentional opt-in research runs:

|Test class|Condition|Reason|
|---|---|---|
|`EvaluationDatasetTest` holdout-only test|`runHoldoutDatasetTest=true`|Inspects the separately frozen holdout dataset; kept opt-in to prevent routine test runs from touching protected evaluation data. No holdout failure details were read in this review.|
|`EvaluationHoldoutReportTest`|`runHoldoutEvaluation=true`|Runs the frozen holdout evaluation. Not enabled in this review.|
|`ResearchBaselineReportTest`|`researchReport=true`|Explicit export/replay of the original BASELINE research report; opt-in because it performs a full report run.|
|`ResearchGenerationReportTest`|`generationReport` is set|Exports the generation proxy report; opt-in to keep model/judge experiments explicit.|
|`ResearchParameterGridTest`|`researchGrid=true`|Runs the optional parameter grid, which is slower than normal regression.|

No Python tests are marked skipped or conditionally skipped. No new feature test was made conditional in this review. `test_api.py` waits up to `RAG_TEST_INDEX_READY_TIMEOUT_SECONDS` (default 180 seconds) for `indexState=READY`; timeout fails with state and health diagnostics. It does not retry a test or accept a non-READY state.

The Python suite needs a writable temp root in this managed Windows environment. Default user Temp and the first workspace temp attempt returned `PermissionError`; with `TEMP`/`TMP` set to repository `.tmp`, three consecutive full runs each passed 107 tests. This is an environment restriction, not a skipped test.

## 复验

完成 README 中的构建与启动步骤后，在 PowerShell 7 运行：

```powershell
python tools/build_corpus.py --check
.\tools\smoke-import.ps1 -Port 8082
```

浏览器脚本依赖 Playwright，本次使用 1.62.1 和本机 Chrome。可将 Playwright 安装在临时目录后运行：

```powershell
npm install --prefix .tmp/ui-deps --no-save --no-package-lock playwright@1.62.1
$env:NODE_PATH = (Resolve-Path .tmp/ui-deps/node_modules).Path
$env:CHROME_PATH = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$env:APP_URL = "http://127.0.0.1:8081"
node tools/smoke-ui.cjs
node tools/smoke-model-ui.cjs
```

浏览器完整脚本要求启用 Python 引擎，实例保持 4 份种子文档。持久化测试脚本自动启动、重启和停止独立测试实例，快照保留于忽略目录 `.tmp`，不会删除用户文件。

真实模型复验要求服务进程已经配置可用的 `MODEL_API_BASE`、`MODEL_NAME`、`MODEL_API_KEY`，并启用 Python 引擎。在上述浏览器环境下运行：

```powershell
node tools/smoke-live-model.cjs
```

该脚本会向已配置的模型服务发送测试问题及命中片段，产生实际 API 调用，可能计费。它不向磁盘输出密钥。

## 未验证项

本轮没有重新构建或运行 Docker 镜像。Compose 只完成配置校验，不能视作镜像启动验收。未执行公网安全审计、负载测试、专家复核或临床验证。接入 DeepSeek 生成模型没有替换现有哈希向量检索，也不代表部署了经过医学验证的专用模型；引用编号校验不能证明所有生成结论均被原文支持。
