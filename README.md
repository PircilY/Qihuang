# 岐黄脉证：中医药与心血管公开资料 RAG 研究原型

> 以中医药与心血管相关公开资料为实验领域。

本项目面向公开资料检索、证据组织和研究型问答，重点研究 BM25 与向量混合检索、候选预过滤、Weighted RRF、metadata rerank、Evidence Coverage Gate、引用追溯与可评测性。它是毕业设计研究原型，不用于疾病诊断、治疗方案制定、处方建议或临床决策；系统输出应结合原始资料进行人工核验。

系统采用 Java 业务层与 Python RAG Service 两种执行路径：Java 负责业务编排、知识登记、答案组织和前端接口；Python/FastAPI 基于 LlamaIndex 提供远程检索服务，Java 本地引擎可作为另一条检索路径。两条路径用于原型运行与检索契约验证；Retrieval Contract 对关键行为进行交叉验证，不代表两个引擎的所有实现细节完全一致。

## 架构

```text
浏览器
  |
  v
Spring Boot 3.3 / Java 17  :8080
  |-- 业务编排、版本管理、知识登记、评估接口
  |-- auto：优先调用远程 RAG，失败后回退 Java 本地引擎
  |
  v HTTP
FastAPI + LlamaIndex       :8000
  |-- TextNode 知识片段
  |-- Query 预处理与 Tokenization
  |-- 全库统计缓存 BM25 + 确定性哈希特征向量混合召回
  |-- 候选预过滤 → Weighted RRF 与 chunkId 去重
  |-- metadata rerank
  |-- Evidence Coverage Gate / Reject
  |-- 最终证据与引用
  |
  +--> Java 组织回答（原文摘录或可选模型生成）并返回 Citation
       Evaluation 对检索、证据覆盖、引用与拒答进行量化检查
```

## 技术栈

- Java 17
- Spring Boot 3.3
- Maven
- Spring MVC + Bean Validation
- Python 3.11+
- FastAPI
- LlamaIndex
- 原生 HTML、CSS、JavaScript
- JSON 知识底座与内存索引

## 当前能力

- 四个可切换的迭代版本
- 同一问题的跨版本对比
- BM25、向量式相似度、RRF 融合和证据重排
- 来源引用、检索参考分、拒答和检索轨迹
- 内置评测集与自动指标计算
- Java 本地 RAG Engine 与 Python FastAPI RAG Service 两种执行路径，并通过 Retrieval Contract 交叉验证关键检索行为
- 知识库来源台账、片段详情与运行时文本导入
- 页面工作台串联提问、检索、证据引用与版本评估
- Java 本地引擎与 Python RAG Service 可配置切换、自动回退和运行时知识同步
- EXPANDED 语料词典名称元数据、可选中英查询桥接与固定种子自监督检索代理评测

当前版本不依赖数据库、外部向量库或大模型 API Key 即可运行。默认使用确定性哈希向量、本地 BM25 与原文摘录。RAG 工作台支持页面内配置兼容 Chat Completions 协议的模型，或从 Java 进程环境加载默认模型；启用后先检索再由模型基于命中证据生成回答。两种模式都会返回检索轨迹与引用。当前检索不是生产级语义检索，接入生成模型不会自动替换 Embedding 算法。

## 大模型接入

RAG 工作台标题旁的配置按钮打开弹窗，主体页面布局不变。配置接口基础地址、实际模型 ID 和 API Key，测试连接后选择「RAG 智能问答」，确认向该接口发送问题及命中文献并保存。API 地址不包含 `/chat/completions`，客户端会自动追加该路径。

- 默认允许 `https://api.deepseek.com/v1`、`https://api.deepseek.com`、`https://dashscope.aliyuncs.com/compatible-mode/v1`、`https://api.openai.com/v1`，以及本机 `http://127.0.0.1:端口/v1`、`http://localhost:端口/v1`。接口须支持 system/user 消息、非流式响应、`max_tokens` 与 `finish_reason=stop`。
- 其他 HTTPS 网关必须由管理员加入 `MODEL_ALLOWED_BASE_URLS`（逗号分隔的完整基础地址）；不接收带用户信息、查询参数或片段的地址，不跟随重定向。允许列表不是对所有兼容厂商的实测声明。
- 会话配置仅在服务端内存保留，闲置 30 分钟失效。API Key 不回显、不写入浏览器存储、日志或知识库。更改接口地址后不继承旧密钥。清除配置仅影响当前会话。
- 需要服务端默认模型时，在启动 Java 前设置 `MODEL_API_BASE`、`MODEL_NAME`、`MODEL_API_KEY`；这些配置面向能访问该实例的所有用户，必须限制访问。关闭进程后需从安全的凭据来源重新提供，代码不自动保存密钥。
- `MODEL_TIMEOUT` 默认 60 秒，覆盖完整模型响应读取；并发模型请求上限为 4，响应正文上限 256 KiB。不自动重试付费请求。
- V1/V2/V3 的模型模式只向模型发送本次问题及命中证据，不发送全库或 API Key。V0 保持无检索基线；四版本对比与评估不使用页面会话模型配置。
- 急症、个体处方和证据不足会在模型调用前拒答。模型超时、鉴权失败、输出截断、缺少引用或引用越界时明确报错，不把摘录伪装成模型输出。引用校验只检查结构与编号，不能证明每项医学结论均被原文支持，仍需人工复核。

模型 API：`GET/PUT/DELETE /api/v1/rag/model`；测试连接：`POST /api/v1/rag/model/test`；问答请求增加可选 `useModel: true`。省略该字段保持原文摘录。页面会话通过 HttpOnly / SameSite=Strict Cookie 隔离；模型接口拒绝跨来源浏览器请求。公网或多人部署前仍须配置 HTTPS、身份认证、权限与出站网络策略。

协议依据：[DeepSeek 官方 Chat Completions 文档](https://api-docs.deepseek.com/api/create-chat-completion)，核对日期 2026-09-30。模型 ID 应从账号实际可用列表选取，不在页面预造模型清单。设置图标来自 Lucide 0.468.0，许可证随 `static/icons/LICENSE` 保留。

知识库当前包含 4 份公开资料、11 个文本片段，不接入患者信息。资料全部标为「未评级」；机构权威性不等于临床证据等级。本项目仅用于公开资料检索、证据组织与研究型问答实验，不用于疾病诊断、治疗方案制定、处方建议或临床决策。系统输出应结合原始资料进行人工核验。

EXPANDED 扩展语料包含 100 篇公开论文、4036 个片段。查询桥接默认关闭（`RAG_QUERY_BRIDGE=false`），需要时可同时为 Java 与 Python 设置为 `true`；桥接仅用名称词典追加低权重跨语言检索词。EXPANDED 元数据来自词典名称自动匹配，并非人工标注，可能漏标或误标。自监督代理评测及其局限见 [EXPANDED 评测](docs/expanded-evaluation.md)，不得视为医学相关性或正确性认证。

## 数据来源

| 来源 | 标注日期 | 内容 |
| --- | --- | --- |
| [WHO 高血压](https://www.who.int/zh/news-room/fact-sheets/detail/hypertension) | 2025-09-25 | 血压检查、症状、生活方式 |
| [WHO 心血管疾病](https://www.who.int/zh/news-room/fact-sheets/detail/cardiovascular-diseases-(cvds)) | 2021-06-11 | 风险控制、急性事件机制 |
| [NIH/NCCIH Licorice Root](https://www.nccih.nih.gov/health/licorice-root) | 2025-04 | 甘草不良反应和相互作用 |
| [NIH/NCCIH Traditional Chinese Medicine](https://www.nccih.nih.gov/health/traditional-chinese-medicine-what-you-need-to-know) | 2019-04 | 研究局限、中草药安全 |

采集时间为 2026-09-30。原文、章节、链接、采集时间和使用范围均登记在 `data/sources.json`。WHO 心血管页面包含后续更新内容，年份沿用页面标注，未收录日期有歧义的流行病学统计。NCCIH 保留英文原文，中文关键词只是人工索引标签，不冒充译文；网页不编造页码。

NCCIH 页面声明出版物属公有领域，保留署名；WHO 仅收录署名短摘录，不代表取得全文或商业再分发许可。商业交付须另行审核授权。详细核验方式见 [数据来源说明](data/README.md)。

## 运行

### Docker Compose 一键启动

已提供 `docker-compose.yml`，适合在答辩或部署环境中同时启动 Java 网关和 Python 检索服务：

```powershell
docker compose up --build
```

启动后访问 `http://localhost:8080`；Python 服务健康地址为
`http://localhost:8000/health`。也可以复制 `.env.example` 作为本地配置模板。

Compose 默认仅发布到 `127.0.0.1`，导入快照保存在 `knowledge-data` 命名卷。`docker compose down` 保留数据；不要使用 `down -v` 删除需要保留的数据。端口被占用时，在 `.env` 配置 `SERVER_PORT` 和 `RAG_PORT`。`.env` 由 Compose 读取，直接运行 Java/Python 时须在启动终端设置环境变量。

如果构建时出现 `failed to fetch oauth token`、`dial tcp ... auth.docker.io`，说明 Docker
无法访问 Docker Hub，通常是网络、代理或 IPv6/DNS 问题，不是项目代码错误。可以先单独验证：

```powershell
docker pull maven:3.9.9-eclipse-temurin-17
docker pull eclipse-temurin:17-jre
docker pull python:3.12-slim
```

也可以在 `.env` 中将 `MAVEN_IMAGE`、`JAVA_IMAGE`、`PYTHON_IMAGE` 指向你所在网络可访问的镜像仓库，
再执行 `docker compose build --no-cache`。如果 Docker Desktop 使用了错误代理，请在 Settings → Resources → Proxies
中修正或关闭代理后重启 Docker Desktop。

先启动 LlamaIndex 服务：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r rag-service\requirements-dev.txt
python -m uvicorn app.main:app --app-dir rag-service --host 127.0.0.1 --port 8000
```

如果中文路径导致 `venv` 创建失败，可改用：

```powershell
python -m pip install --target .rag-deps -r rag-service\requirements-dev.txt
$env:PYTHONPATH = ".rag-deps"
python -m uvicorn app.main:app --app-dir rag-service --host 127.0.0.1 --port 8000
```

再构建并启动 Java 业务服务：

```powershell
cd backend
mvn clean package
java -jar target\tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar
```

打开 `http://localhost:8080`。

Windows 下也可在完成构建和依赖安装后，用 PowerShell 7 从仓库根目录执行：

```powershell
.\tools\start-local.ps1 -Port 8081 -RagPort 8001 -WithPython
```

不带 `-WithPython` 时只启动 Java 本地引擎。脚本拒绝占用已有端口，后台运行、不弹出窗口；日志和 PID 位于 `runtime`。先结束对应 PID 的进程，再重新构建和启动。脚本不安装依赖。

不建议在当前中文路径工作区使用 `mvn spring-boot:run`。Windows 下个别 Spring Boot Maven 插件版本可能因类路径编码问题找不到主类，打包后通过 `java -jar` 启动不受该问题影响。

## 引擎模式

Java 通过环境变量控制 RAG 引擎，默认值适合本地复核：

| 模式 | 行为 |
| --- | --- |
| `auto` | 优先调用 LlamaIndex；远程异常时回退 Java 本地检索，默认每 10 秒重试 |
| `llama-index` | 使用 LlamaIndex；`RAG_FALLBACK_TO_LOCAL=true` 时仍允许回退 |
| `local` | 只使用 Java 本地检索，不访问 Python 服务 |

严格校验远程链路时：

```powershell
$env:RAG_ENGINE_MODE = "llama-index"
$env:RAG_FALLBACK_TO_LOCAL = "false"
java -jar backend\target\tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar
```

常用环境变量：

- `RAG_SERVICE_URL`：Python 服务地址，默认 `http://127.0.0.1:8000`
- `RAG_CONNECT_TIMEOUT`：连接超时，默认 `500ms`
- `RAG_READ_TIMEOUT`：读取超时，默认 `30s`
- `RAG_RETRY_INTERVAL`：远程失败后的重试间隔，默认 `10s`
- `MODEL_API_BASE`、`MODEL_API_KEY`、`MODEL_NAME`：Java 工作台默认生成模型
- 旧 Python `LLM_*` 配置已移除；生成统一使用 Java `MODEL_*`。
- `SERVER_ADDRESS`、`SERVER_PORT`：默认绑定 `127.0.0.1:8080`
- `KNOWLEDGE_STORE_PATH`：导入快照路径，默认相对启动目录的 `runtime/knowledge-imports.json`；部署时建议指定固定绝对路径

导入在完整快照落盘后才返回成功，重启自动恢复，重复正文返回 HTTP 409。`LOCAL_READY` 表示仅本地可用，`READY` 表示已同步 Python，`SYNC_PENDING` 表示本地已保存但远程暂不可用；后续远程问答前会幂等同步本地导入记录。用户登记的来源和授权属于声明，系统不会将其标为已独立核验。

## 测试

```powershell
cd rag-service
$env:PYTHONPATH = "..\.rag-deps"
python -m pytest -q -p no:cacheprovider

cd ..\backend
mvn test
```

仓库还提供 GitHub Actions 工作流，在 push 和 pull request 时分别执行 Python 与 Java 测试。

当前正式评测集包含 50 个问题、4 个版本，共 200 条评测结果，覆盖 8 类检索与拒答场景；这是基于本项目知识库构建的回归评测，不是外部临床测评集。P2-02-2 → P2-02-3 Experiment A 已冻结，Experiment A = PASS，`failureCount` 从 4 降至 1；Hit@K / Recall@K / Claim Coverage 为 0.976 / 0.976 / 0.976，False Reject / False Accept 均为 0，Citation Presence / Validity / Support 均为 1.000。指标定义、完整对比及剩余案例见[正式实验基线](docs/evaluation-baseline.md)。检索参考分是启发式分数，不是医学正确率或校准概率；测试夹具不进入知识库语料。

或只运行 Java 测试：

```powershell
cd backend
mvn test
```

## API

- `GET /api/v1/versions`：获取迭代版本
- `POST /api/v1/rag/chat`：运行指定版本
- `POST /api/v1/rag/compare`：运行全部版本
- `POST /api/v1/evaluations/run`：运行内置评测集
- `GET /api/v1/knowledge/overview`：获取知识库统计概览
- `GET /api/v1/knowledge/documents`：获取来源台账
- `GET /api/v1/knowledge/pipeline`：获取数据加工阶段
- `POST /api/v1/knowledge/import`：持久化登记文档、建立索引并尝试远程同步
- `GET /api/v1/knowledge/chunks`：查看知识片段
- `GET /actuator/health`：健康检查

Python 服务另提供：

- `GET http://127.0.0.1:8000/health`
- `GET http://127.0.0.1:8000/v1/engine/info`
- `POST http://127.0.0.1:8000/v1/rag/chat`
- `POST http://127.0.0.1:8000/v1/knowledge/import`

## 工程约定

- 控制器只负责协议转换和参数校验
- 业务编排位于 `service` 层
- 检索策略通过统一接口注册
- 外部配置通过 `@ConfigurationProperties` 注入
- 所有异常通过全局异常处理器转换为统一响应
- DTO 与领域模型分离
- 知识库数据通过仓储接口访问

## 当前边界

持久化快照只支持单进程、单写入者，不支持多个 Java 实例共享同一路径。备份应在停止写入后复制完整快照；恢复时停止服务、替换快照再启动，损坏快照会阻止启动而不是静默丢数据。Python 索引是可重建缓存，导入应通过 Java 接口完成。

未实现用户登录、租户隔离、专家审核或临床认证；不要直接暴露公网，不要输入患者隐私。规则式急症与剂量识别只能覆盖部分表达，不能承担临床分诊。启用外部 LLM 会将问题和检索片段发送给配置的服务方，需事先审核隐私和授权。公网或临床部署前必须补齐访问控制、审计、授权复核、独立安全评估及真实语义模型，并重新验证。


## 5 分钟演示（默认无模型、无联网调用）

前提：Java 17+、Maven、Python 3.11+、PowerShell 7 已安装且依赖已有缓存。首次依赖准备是可选联网步骤：`mvn -f backend/pom.xml package` 与 `python -m pip install -r rag-service/requirements-dev.txt`。离线默认测试不下载模型、不读取密钥、不访问外部模型；依赖本身需事先安装或缓存。

```powershell
# 仅Java本地：自动使用Maven离线缓存构建缺失jar
.\start.cmd
# Java + Python，两端均就绪后返回URL；日志/PID仅在runtime
.\start.cmd -WithPython -Port 8080 -RagPort 8000
# 关闭此脚本启动的服务，校验PID创建时间避免误关其他进程
.\stop.cmd -Port 8080
```

1. 打开 http://127.0.0.1:8080，概览查看 BASELINE 的4个来源/11个片段，再在知识库切换 EXPANDED 查看100个来源/4036个片段。健康资料不代表临床分级。
2. 工作台选择 BASELINE/V3，提问“高血压没有症状也需要检查血压吗？”，查看来源、原文、引擎、版本、语料、嵌入后端与耗时。
3. 点击版本对比，V0–V3并排展示回答、指标和命中证据；V0保持无检索。对比沿用所选语料。
4. 提问原安全回归问题“胸痛持续不缓解，怎么办？”，查看急症边界提示。系统只用于资料研究。
5. 模型弹窗可选择原文摘录；未配置模型时请求生成会提示。已有兼容模型时才显式启用。

自动演示验收：`python tools/smoke_e2e.py` 自行启动隔离端口和临时导入库，验证提问、版本对比、真实公开原文导入后检索、模型未配置、远程中断本地回退、Python重启后自动补同步及安全规则哈希。全部进程由测试关闭。

## 已验证依赖与默认模型边界

2026-10-23 本机完整测试组合：llama-index-core **0.14.25**、llama-index-retrievers-bm25 **0.6.5**、bm25s **0.3.11**、FastAPI **0.141.1**、Pydantic **2.13.5**、Uvicorn **0.54.0**、pytest **9.1.1**、httpx **0.28.1**。requirements.txt 固定直接依赖；requirements-lock.txt 固定运行时依赖闭包（Linux/Python3.12 marker）；requirements-dev.txt 复用锁并固定测试依赖。安装到全新 Linux 环境及容器仍需单独验证，不能把本机已装环境等同于镜像验证。

当前词法打分使用自有 LexicalIndex，BM25 retriever 包保留可导入兼容组合；向量索引使用 LlamaIndex VectorStoreIndex。这避免旧 BM25 检索器对预过滤子集统计和逐次重建的开销。默认 hash 保持离线；`requirements-embedding.txt` 与 `EMBEDDING_BACKEND=sentence-transformers` 为独立可选安装。`EMBEDDING_ALLOW_DOWNLOAD=false` 默认禁止模型下载；详情见 docs/embedding-experiment.md。

Python不再读取旧 LLM_*，不直连生成模型；所有生成与密钥统一通过 Java MODEL_*。Python健康中的llmConfigured固定false表示其不负责生成，不表示Java未配置模型。

## 新增配置摘要

|配置|默认|作用|
|---|---|---|
|RAG_NORMALIZED_FUSION|true|归一化 weighted RRF；false恢复旧量纲，需要同时旧阈值|
|RAG_METADATA_WEIGHT|0.0|只用原50题标定的比例权重|
|RAG_MINIMUM_EVIDENCE_SCORE|0.9|对应归一化分数的证据阈值|
|KNOWLEDGE_MAX_IMPORT_DOCUMENTS / CHARACTERS|1000 / 10000000|运行时导入总量上限|
|IMPORT_REQUESTS_PER_MINUTE / EVALUATION_REQUESTS_PER_MINUTE|30 / 2|研究接口限流|
|GENERATION_EXPERIMENT_ENABLED|false|显式启用A/B/C生成对照接口|
|GENERATION_EXPERIMENT_MAX_CASES|50|实验问题上限|
|JUDGE_ENABLED|false|独立配置评审；JUDGE_API_BASE/MODEL/API_KEY/TEMPERATURE|
|EMBEDDING_BACKEND|hash|Python hash或显式sentence-transformers|
|EMBEDDING_ALLOW_DOWNLOAD|false|显式允许下载；默认只读取本地模型|
|EMBEDDING_CACHE_PATH|runtime/embedding-cache|EXPANDED向量缓存|

健康接口返回Python indexState BUILDING/READY/FAILED及embeddingBackend/Model/build耗时，BUILDING查询503；Java允许回退时回答明确包含“已使用本地引擎”。安全规则健康字段两端应等于仓库safety-rules.json的SHA256。

## 容器与交付

```powershell
# 可选联网镜像构建；PIP_INDEX_URL默认官方源，可自行设置镜像
 docker compose build
 docker compose up -d --wait
 docker compose exec rag-service python -c "from app.safety_rules import RULES_SHA256; print(RULES_SHA256)"
 curl.exe http://127.0.0.1:8080/actuator/health
 docker compose down
# 源码干净交付包与逐文件SHA256清单
 python tools/package_delivery.py
```

两个镜像均使用UID10001非root，HEALTHCHECK，Java MaxRAMPercentage=75；运行时知识卷允许UID10001写入。默认容器仅映射127.0.0.1。Docker未在本机完成构建/启动：已安装CLI和compose，但Docker引擎管道不存在，实际compose build失败；完整记录在docs/deployment-validation.md。

交付包只取Git跟踪源码，不包含依赖、模型、target、日志、缓存、运行时导入数据、冻结留出集和用户无关文件；文件不删除，打包在读之前按路径排除。归档runtime/delivery/qihuang-rag.zip附delivery-manifest.json，需依赖预装环境才能离线构建。

## 故障排查

- 端口占用：start-local明确拒绝占用端口，换`-Port/-RagPort`；stop-local只关闭其记录且创建时间一致的PID。
- Python导入失败：使用同一Python安装锁定依赖，或配置PYTHONPATH指向已有.rag-deps；不要把依赖目录提交。
- BUILDING：首次EXPANDED缓存构建可能较慢，健康仍响应；启动脚本等待READY。FAILED查看runtime日志、模型路径和embedding配置；默认hash无需模型。
- 远程不可用：默认auto回退Java并显示提示；恢复后等待RAG_RETRY_INTERVAL，下一次请求自动同步运行时文档。EXPANDED不参与运行时修订号。
- 409持续：检查Java知识快照与追加日志是否对应同一实例；同步会先清理Python额外导入再批量补发；评测在同步未确认时拒绝运行。
- 429：按Retry-After等待，或在受控研究环境配置限额；不绕过保护循环重试。
- 模型未配置/鉴权/截断/引用越界：页面明确提示。默认摘录不依赖模型。实验每种模式保留失败行，见docs/generation-experiment.md。
- 离线Maven缺缓存：可选联网准备依赖一次；测试`mvn -o -f backend/pom.xml -DrunHoldoutEvaluation=false test`，Python`python -m pytest rag-service/tests -q`。

阶段结果与局限见 docs/improvement-report.md、docs/retrieval-quality.md、docs/performance.md、docs/knowledge-base.md、docs/decisions.md。冻结留出集本次未读取、未运行、不用于调参。

## 复核与加固（L–O）

EXPANDED 评测按标题、词典目标术语（循环风险，仅验证扩展设计）和不依赖词典的中文原文术语三组独立报告。服务探测记录真实 Java/Python hash 检索结果；语料覆盖但前三条未命中的问题会使探测失败。自动词典双语标题核验与固定种子元数据抽样见 `docs/terminology-check.md` 和 `docs/knowledge-base.md`。这些字符串命中与代理指标不等于人工医学相关性判断。

复现：`python tools/build_expanded_eval.py --service-eval`、`python tools/probe_expanded.py`、`python tools/check_terminology_consistency.py`、`python tools/audit_terminology_metadata.py`。真实服务报告见 `docs/service-probe.md`；Docker、BGE-M3、本地模型权重及真实生成模型/judge 尚未验证，参见 `docs/improvement-report.md`。
