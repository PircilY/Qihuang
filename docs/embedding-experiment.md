# 可选语义嵌入实验

默认 `EMBEDDING_BACKEND=hash`、`EMBEDDING_MODEL=hash-sha256-bigram-v1`。Java 本地始终使用哈希向量；C 不重新调参，沿用 B 的归一化融合、权重0和阈值.9。

本机实查：未安装 sentence_transformers，默认 BGE 模型缓存不存在。真实语义模型实验跳过，不能将 mock 测试当作模型质量结果，也没有估算 hash 与 BGE 的比较指标。可选依赖和模型不影响默认构建。

本地模型条件可用时复现（无密钥）：

```powershell
pip install -r rag-service/requirements-embedding.txt
$env:EMBEDDING_BACKEND='sentence-transformers'
$env:EMBEDDING_MODEL='D:/models/bge-small-zh-v1.5'
$env:EMBEDDING_ALLOW_DOWNLOAD='false'
python -m uvicorn app.main:app --app-dir rag-service --host 127.0.0.1 --port 8000
python tools/compare_embeddings.py --model D:/models/bge-small-zh-v1.5
```

只在明确允许下载时使用 `--allow-download` 或 `EMBEDDING_ALLOW_DOWNLOAD=true`。脚本严格读取原50题，不访问留出集；输出实际Hit@1/K、MRR、Recall@K、拒答准确率、索引构建耗时和逐题结果到忽略目录`.tmp`。失败原因记录，不静默跳过。

健康接口返回`indexState=BUILDING/READY/FAILED`、后端、模型名和每个语料的构建耗时。后台预热期间健康可立即返回；问答返回503，Java允许回退时返回本地答案并在trace显示`remote-index=BUILDING`和“已使用本地引擎”。可选适配器、缓存复用/失效、模型切换和预热失败均使用非医学占位mock测试。

EXPANDED向量缓存位置由`EMBEDDING_CACHE_PATH`指定，默认`runtime/embedding-cache`，内容、模型、后端和分词版本共同决定键。缓存不可写时继续内存检索；损坏记录重建。索引仍是精确向量扫描，CPU模型预热和大库查询成本需在目标环境实测。

本机实际执行 `compare_embeddings.py` 的 hash 结果：Hit@1=0.7619047619、Hit@K=0.9761904762、MRR=0.8571428571、Recall@K=0.9642857143、拒答准确率=1.0；索引构建 BASELINE=209.360ms、EXPANDED=60554.559ms（首次缓存生成、同时运行测试，非独立负载性能结论）。语义结果为空，失败原因是缺少可选依赖。
