# LlamaIndex RAG 服务

该服务把知识片段构建为 LlamaIndex `TextNode`，同时建立本地哈希向量索引和 BM25 索引，并使用 RRF 融合结果。BM25 面向短篇中文医学文本配置字符级切分，V3 版本还会执行证据阈值判断和安全拒答。默认不依赖外部模型或 API Key，回答由检索证据抽取生成；配置 OpenAI 兼容模型后会调用大模型生成回答。

## 本地启动

在仓库根目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r rag-service\requirements-dev.txt
uvicorn app.main:app --app-dir rag-service --host 127.0.0.1 --port 8000
```

打开 `http://127.0.0.1:8000/docs` 查看接口文档。

如果当前工程路径包含中文且 Python `venv` 无法正常创建，可以把依赖安装到纯 ASCII 路径并显式设置 `PYTHONPATH`：

```powershell
python -m pip install --target .rag-deps -r rag-service\requirements-dev.txt
$env:PYTHONPATH = ".rag-deps"
python -m uvicorn app.main:app --app-dir rag-service --host 127.0.0.1 --port 8000
```

## 主要接口

- `GET /health`：服务、文档数、片段数和模型配置状态
- `GET /v1/engine/info`：检索器、融合方式和当前阈值
- `POST /v1/rag/chat`：执行 V0-V3 问答
- `POST /v1/knowledge/import`：接收 Java 业务层同步的文档和知识片段
- `POST /v1/knowledge/reload`：从 JSON 数据文件重新加载索引

常用配置：

- `RAG_CANDIDATE_LIMIT`：单路召回候选数
- `RAG_FINAL_LIMIT`：最终入选片段数
- `RAG_MINIMUM_EVIDENCE_SCORE`：V3 证据阈值，默认 `0.016`

## 接入大模型

安装可选依赖并设置环境变量：

```powershell
pip install "llama-index-llms-openai-like>=0.3,<0.7"
$env:LLM_API_BASE = "https://your-openai-compatible-endpoint/v1"
$env:LLM_API_KEY = "your-key"
$env:LLM_MODEL = "your-model"
```

未配置模型时，服务仍可用于检索、引用、版本对比和证据约束演示。

## 测试

```powershell
cd rag-service
$env:PYTHONPATH = "..\.rag-deps"
python -m pytest -q -p no:cacheprovider
```
