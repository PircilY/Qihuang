# 中医药心血管疾病辅助诊疗 RAG 原型

该项目采用 Java 业务层与 Python RAG 服务分离的双栈结构，用于展示从直接问答到检索增强、混合召回、证据约束和量化评估的演进过程。Java 负责业务编排、知识登记和前端接口；Python/FastAPI 以 LlamaIndex 为检索底座，支持 BM25、向量检索和 RRF 融合。

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
  |-- 本地确定性哈希向量索引
  |-- CJK BM25
  |-- QueryFusionRetriever / RRF
  |-- 引用、证据阈值、安全拒答
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
- 来源引用、置信度、拒答和检索轨迹
- 内置评测集与自动指标计算
- 知识库来源台账、片段详情与运行时文本导入
- 页面内置“一键演示”，串联检索、版本对比和量化评估
- Java 与 LlamaIndex 双引擎切换、自动回退和运行时知识同步

当前版本不依赖数据库、外部向量库或大模型 API Key，便于离线演示。Python 服务使用确定性哈希向量和本地 BM25，因此结果可复现，但不等同于生产级语义检索。后续可在现有接口不变的前提下替换为真实 Embedding 模型、PostgreSQL/pgvector、OpenSearch、Neo4j 或兼容 OpenAI 的模型服务。

内置数据仅用于功能演示，不是经过临床审核的诊疗知识库，也不接入真实患者信息。正式使用前必须完成数据授权、来源核验、专家复核、脱敏和安全性评估。

## 运行

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

不建议在当前中文路径工作区使用 `mvn spring-boot:run`。Windows 下个别 Spring Boot Maven 插件版本可能因类路径编码问题找不到主类，打包后通过 `java -jar` 启动不受该问题影响。

## 引擎模式

Java 通过环境变量控制 RAG 引擎，默认值适合演示：

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
- `LLM_API_BASE`、`LLM_API_KEY`、`LLM_MODEL`：可选的 OpenAI 兼容生成模型

## 测试

```powershell
cd rag-service
$env:PYTHONPATH = "..\.rag-deps"
python -m pytest -q -p no:cacheprovider

cd ..\backend
mvn test
```

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
- `POST /api/v1/knowledge/import`：登记文档并建立内存索引
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

