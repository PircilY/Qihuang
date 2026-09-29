# 中医药心血管疾病辅助诊疗 RAG 原型

该项目是一个 Java 优先、可本地运行的 RAG 迭代演示原型，用于展示从直接问答到证据约束生成的演进过程。

## 技术栈

- Java 17
- Spring Boot 3.3
- Maven
- Spring MVC + Bean Validation
- 原生 HTML、CSS、JavaScript
- 内存知识库与确定性检索算法

## 当前能力

- 四个可切换的迭代版本
- 同一问题的跨版本对比
- BM25、向量式相似度、RRF 融合和证据重排
- 来源引用、置信度、拒答和检索轨迹
- 内置评测集与自动指标计算
- 知识库来源台账、片段详情与运行时文本导入
- 页面内置“一键演示”，串联检索、版本对比和量化评估

当前版本不依赖数据库、向量库或外部大模型，便于在没有 API Key 的环境中演示。后续可在现有接口不变的前提下替换为 PostgreSQL/pgvector、OpenSearch、Neo4j 或兼容 OpenAI 的模型服务。

内置数据仅用于功能演示，不是经过临床审核的诊疗知识库，也不接入真实患者信息。正式使用前必须完成数据授权、来源核验、专家复核、脱敏和安全性评估。

## 运行

推荐先构建可执行 JAR，再启动服务：

```powershell
cd backend
mvn clean package
java -jar target\tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar
```

打开 `http://localhost:8080`。

不建议在当前中文路径工作区使用 `mvn spring-boot:run`。Windows 下个别 Spring Boot Maven 插件版本可能因类路径编码问题找不到主类，打包后通过 `java -jar` 启动不受该问题影响。

## 测试

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

## 工程约定

- 控制器只负责协议转换和参数校验
- 业务编排位于 `service` 层
- 检索策略通过统一接口注册
- 外部配置通过 `@ConfigurationProperties` 注入
- 所有异常通过全局异常处理器转换为统一响应
- DTO 与领域模型分离
- 知识库数据通过仓储接口访问

