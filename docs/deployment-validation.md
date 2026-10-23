# 部署验证

2026-10-23：Docker CLI可执行，Compose v5.5.1；docker info、实际docker compose build及docker compose up -d --no-build都失败：Docker Desktop Linux Engine named pipe不存在。未启动容器、未实测镜像非root文件权限/HEALTHCHECK与镜像内哈希，因此容器项标记未验证。docker compose config --quiet通过。

复核命令（可选有Docker引擎环境）：

```powershell
docker compose build
docker compose up -d --wait
docker compose exec backend id
docker compose exec rag-service id
docker compose exec rag-service python -c "from app.safety_rules import RULES_SHA256; print(RULES_SHA256)"
curl.exe http://127.0.0.1:8080/actuator/health
Get-FileHash safety-rules.json -Algorithm SHA256
docker compose down
```

Python镜像默认官方PyPI、68项固定运行依赖，直接COPY两个检索知识片段文件与共享安全规则；Java构建resources复制共享规则，jar排除冻结集。非root UID/GID10001，runtime目录所有者与卷初始化一致。实际Linux初次安装、ARM/其他Python版本、镜像基础标签更新仍需复核。

本地自动HTTP冒烟与UI检查结果由最终改进报告记录。生成模型、语义嵌入模型和judge缺少依赖/本地权重/密钥时不下载、不调用；相应真实性能比较为可选后续增强。
