# LlamaIndex RAG 服务

该服务把知识片段构建为 LlamaIndex `TextNode`，同时建立本地哈希向量索引和 BM25 索引，并使用 RRF 融合结果。BM25 支持中文字符和英文词项。所有版本均执行急症、处方和剂量规则拒答，V1-V3 过滤停用片段与无关主题，V3 额外执行证据阈值判断。默认不依赖外部模型或 API Key，回答由检索证据抽取生成；配置 OpenAI 兼容模型后会调用大模型生成回答。

种子数据为 4 份 WHO / NIH-NCCIH 公开资料的 11 条核验摘录，详见仓库根目录 `data/sources.json`。哈希向量不等于经过训练的语义嵌入；参考分不是医学正确率。

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
- `POST /v1/knowledge/reload`：从种子 JSON 重新加载索引，会清除 Python 内存中的运行时导入；后续 Java 远程问答前自动重同步

Python 索引只保存在内存，持久化由 Java 负责。导入通过 Java `/api/v1/knowledge/import` 完成，避免只写 Python 后重启丢失。重复的同步批次不会重建索引；查询、导入和重建串行保护，因此单实例长耗时生成会阻塞同实例查询。服务仅供本机或受控服务间访问。

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

未配置模型时，服务仍可用于检索、引用、版本对比和证据约束复核；配置模型后才启用大模型生成。

Docker 构建默认不包含可选模型依赖。启用时在 `.env` 中设置 `INSTALL_LLM=true` 及三个 `LLM_*` 变量后重新构建。`/health` 的 `llmConfigured` 反映实际加载状态；依赖缺失或模型调用失败时日志会记录抽取式回退。引用编号检查不能代替医学事实核验。

## 测试

```powershell
cd rag-service
$env:PYTHONPATH = "..\.rag-deps"
python -m pytest -q -p no:cacheprovider
```
