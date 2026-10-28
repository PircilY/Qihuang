"""Report measured original-50 tuning experiments; never load the holdout dataset."""
import argparse
import json
from pathlib import Path


def report(before_path, after_path, grid_path, output):
    before = json.loads(before_path.read_text(encoding="utf-8"))
    after = json.loads(after_path.read_text(encoding="utf-8"))
    grid = json.loads(grid_path.read_text(encoding="utf-8"))
    if before["evaluation"]["totalCases"] != 50 or after["evaluation"]["totalCases"] != 50:
        raise ValueError("Only the original 50 tuning cases are supported")
    old_cases = {(row["version"], row["caseId"]): row for row in before["evaluation"]["caseResults"]}
    new_cases = {(row["version"], row["caseId"]): row for row in after["evaluation"]["caseResults"]}
    if old_cases.keys() != new_cases.keys():
        raise ValueError("Before/after case identities differ")
    lines = ["# 原 50 题检索质量与参数标定", "", "本报告来自实际 Java 本地运行；冻结留出集未加载。"
             "前后显式固定 K=4、候选8、RRF常数60和通道权重1/.85。"
             "早期测试配置覆盖主配置的中间报告不用于此比较。", "", "## 四版本前后指标", "",
             "|版本/时期|Hit@1|Hit@3|Hit@K|MRR|Recall@K|拒答准确率|TR/FR/FA/TA|",
             "|---|---:|---:|---:|---:|---:|---:|---|"]
    metrics = ["hitRateAt1", "hitRateAt3", "hitRateAtK", "meanReciprocalRank", "recallAtK", "refusalAccuracy"]
    for label, data in [("改前", before), ("改后", after)]:
        for row in data["evaluation"]["summaries"]:
            cells = [str(row[key]) for key in metrics]
            confusion = "/".join(str(row[key]) for key in ("trueReject", "falseReject", "falseAccept", "trueAccept"))
            lines.append("|" + "/".join([row["version"], label]) + "|" + "|".join(cells) + "|" + confusion + "|")
    lines += ["", "TR=应拒且拒；FR=应答却拒；FA=应拒却答；TA=应答且答。"
              "检索指标分母是42个可回答问题；拒答准确率分母是全部50题。", "", "## 候选网格", "",
              "选择顺序：先要求拒答准确率1，再最大化Hit@1、MRR；并列选择最高阈值，再选择较小元数据权重。",
              f"最终 metadataWeight={grid['selectedMetadataWeight']}，threshold={grid['selectedThreshold']}。", "",
              "|元数据权重|阈值|Hit@1|MRR|拒答准确率|FR|FA|", "|---:|---:|---:|---:|---:|---:|---:|"]
    for candidate in grid["grid"]:
        row = candidate["summary"]
        lines.append("|" + "|".join(str(value) for value in [candidate["metadataWeight"], candidate["threshold"],
            row["hitRateAt1"], row["meanReciprocalRank"], row["refusalAccuracy"], row["falseReject"], row["falseAccept"]]) + "|")
    lines += ["", "所有阈值在此小型调参集上的拒答相同，因此阈值不是概率校准；"
              "选择更高阈值只是并列保守默认。元数据非零权重没有改善排名，保留实现供有评级来源时复现。", "",
              "## 每项排名变化", "", "|版本/题号|原命中排名→新排名|原证据→新证据|原因|", "|---|---|---|---|"]
    changes = 0
    for key, new in new_cases.items():
        old = old_cases[key]
        if old["retrievedChunkIds"] == new["retrievedChunkIds"] and old["hitRank"] == new["hitRank"]:
            continue
        changes += 1
        explanation = ("词频/全库IDF替代去重词和相关子集统计；SHA-256桶向量替代旧稀疏向量；"
                       "带权RRF由两通道排名决定。" if key[0] != "V0_DIRECT" else "统一安全拒答支持度。")
        if key[0] == "V3_GUARDED":
            explanation += "融合归一化；网格选择元数据权重0，消除元数据量纲压过召回排名。"
        if new["hitRank"] == 0 or (old["hitRank"] and new["hitRank"] > old["hitRank"]):
            explanation += "此题发生下降，不能归为质量提升；哈希碰撞/词频权重改变或阈值/覆盖门影响候选。"
        lines.append(f"|{key[0]}/{key[1]}|{old['hitRank']}→{new['hitRank']}|"
                     + ", ".join(old["retrievedChunkIds"]) + " → " + ", ".join(new["retrievedChunkIds"])
                     + "|" + explanation + "|")
    lines += ["", f"共 {changes} 个版本/问题组合出现证据排序或命中排名变化；没有添加针对单题的检索规则。", "",
              "## 自动指标的限制", "", "Hit/MRR使用冻结调参标签衡量检索命中；它们不等于医学事实正确率。"
              "本次桶哈希统一可能使V1部分指标下降；需要真实语义模型的实验在C单独列示，不能估算替代。", "",
              "复现：在A提交白名单源码运行旧配置；当前运行 `mvn -o test -DresearchReport=true "
              "-DresearchGrid=true -DresearchOutput=../.tmp/research-b-configured.json -DrunHoldoutEvaluation=false`。"
              "再用 `python tools/report_retrieval.py --before .tmp/research-before-production.json "
              "--after .tmp/research-b-configured.json --grid .tmp/research-grid.json`。", ""]
    output.write_text("\n".join(lines), encoding="utf-8")
    performance = ["# 查询性能", "", "同机Java本地V3，每组预热1次、20个实测样本；"
                   "问题来自原50题语料范围。中位数为排序第11项，p95为第19项。"
                   "采样时存在其他测试进程，数据用于原型比较，不能视作独立负载下的SLA。", "",
                   "|语料|切片数|时期|中位数ms|p95ms|", "|---|---:|---|---:|---:|"]
    for label, data in [("改前", before), ("改后", after)]:
        for corpus, metrics in data["latency"].items():
            count = metrics.get("chunkCount", "未记录")
            performance.append(f"|{corpus}|{count}|{label}|{metrics['medianMs']:.4f}|{metrics['p95Ms']:.4f}|")
    performance += ["", "收益来自变更时缓存分词、全库统计和哈希向量；查询仅分词问题、筛选预存特征、打分候选。"
                    "导入仍需发布新的统计快照；向量查询仍是线性扫描，规模更大时需ANN索引。", "",
                    "复现报告命令与原始参数见 retrieval-quality.md；JSON测量文件保存在忽略目录.tmp。", ""]
    output.with_name("performance.md").write_text("\n".join(performance), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--grid", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("docs/retrieval-quality.md"))
    args = parser.parse_args()
    report(args.before, args.after, args.grid, args.output)
