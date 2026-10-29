# EXPANDED 真实服务端到端探测

SHA-256 hash 检索；7 个固定问题；`RAG_QUERY_BRIDGE=external (not verified)`。
按接口 `documentTitle` / `quote` 核验前三条引用。覆盖数为全库标题/正文含任一所列语言术语的片段数。有覆盖但前三条未命中、HTTP/响应异常或实际引擎/版本不符均使探测失败；无覆盖不冒充检索退化。
词面命中仅是回归代理，不证明医学相关性、疗效或回答正确性。拒答原因和检索轨迹保留在同名 JSON 报告中。

G 之前代码对照：`delivery-c9652d3` 同环境服务对照未执行；只展示当前服务结果，不估算旧版结果。

|问题|覆盖片段数|前三条命中|状态|引擎|版本|前三条引用标题 / 错误|
|---|---:|---|---|---|---|---|
|黄芪治疗慢性心力衰竭的临床疗效|385|True|命中|llama-index|V3_GUARDED|温阳化饮养心方对慢性心力衰竭合并利尿剂抵抗患者中医证候的影响及疗效观察<br>参蛤散通过调控LOXL2/TGF-β1/IL-11信号通路抑制心肌梗死后心力衰竭大鼠心肌纤维化<br>温阳化饮养心方对慢性心力衰竭合并利尿剂抵抗患者中医证候的影响及疗效观察|
|丹参对冠心病心绞痛有什么作用|386|False|有覆盖但未命中|llama-index|V3_GUARDED|高效液相色谱联合2,2'-二硫二吡啶衍生反应测定人血清不同类型游离巯基及其与冠心病的关系分析<br>心脏康复对心肺运动阳性的高原非阻塞性冠状动脉疾病患者症状和生活质量的影响<br>患者门户系统在老年冠心病慢病远程管理中的应用及效果|
|中医药治疗高血压的研究进展|1292|True|命中|llama-index|V3_GUARDED|经典型骨髓增殖性肿瘤伴高血压的临床研究进展<br>高原相关高血压研究现状与展望<br>经典型骨髓增殖性肿瘤伴高血压的临床研究进展|
|气虚血瘀证心衰的辨证论治|1832|False|有覆盖但未命中|llama-index|V3_GUARDED||
|温阳化饮养心方对心衰利尿剂抵抗的影响|36|True|命中|llama-index|V3_GUARDED|温阳化饮养心方对慢性心力衰竭合并利尿剂抵抗患者中医证候的影响及疗效观察<br>温阳化饮养心方对慢性心力衰竭合并利尿剂抵抗患者中医证候的影响及疗效观察<br>温阳化饮养心方对慢性心力衰竭合并利尿剂抵抗患者中医证候的影响及疗效观察|
|astragalus chronic heart failure efficacy|385|True|命中|llama-index|V3_GUARDED|The clinical efficacy of Astragalus-containing Chinese patent medicines in the effective treatment of heart failure: A systematic review and network meta-analysis<br>The clinical efficacy of Astragalus-containing Chinese patent medicines in the effective treatment of heart failure: A systematic review and network meta-analysis<br>The clinical efficacy of Astragalus-containing Chinese patent medicines in the effective treatment of heart failure: A systematic review and network meta-analysis|
|Danshen coronary heart disease angina|386|False|有覆盖但未命中|llama-index|V3_GUARDED||

验收结果：4/7 项通过，3 项失败。
