package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.CitationResponse;
import com.tcm.ragassistant.service.retrieval.RetrievalOutput;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AnswerGenerationService {

    public GeneratedAnswer generate(
            String question,
            IterationVersion version,
            RetrievalOutput retrievalOutput
    ) {
        if (SafetyRules.isEmergency(question)) {
            return refusal("问题可能涉及急症，超出线上问答边界",
                    "问题可能涉及急症，请立即拨打 120 / 前往急诊。",
                    retrievalOutput, 0);
        }
        if (SafetyRules.isIndividualizedRequest(question)) {
            return refusal(
                    "现有公开文献无法支持个体化用药剂量调整建议（涉及个体化处方判断）。",
                    "不能根据在线资料调整当前用药剂量，请联系开药医生并由医师判断。",
                    retrievalOutput, 0
            );
        }
        if (version == IterationVersion.V0_DIRECT) {
            return new GeneratedAnswer(
                    "当前版本没有检索知识库，不能为这个问题提供可追溯的专科证据。"
                            + "作为基线，仅提示中医心血管诊疗需要结合四诊信息并由专业医师判断。",
                    false,
                    "",
                    0.35,
                    List.of()
            );
        }

        if (retrievalOutput.chunks().isEmpty()) {
            EvidenceCoverageGate.Assessment coverage = EvidenceCoverageGate.assess(question, List.of());
            String reason = coverage.covered()
                    ? "未检索到达到阈值的相关证据"
                    : coverage.refusalReason();
            String answer = coverage.covered()
                    ? "当前知识库中没有找到足以支持回答的证据。系统已停止生成具体诊疗建议。"
                    : coverage.refusalReason();
            return new GeneratedAnswer(
                    answer,
                    true,
                    reason,
                    0.2,
                    List.of()
            );
        }

        List<ScoredChunk> chunks = retrievalOutput.chunks();
        List<String> statements = new ArrayList<>();
        int statementLimit = chunks.size();
        for (int index = 0; index < statementLimit; index++) {
            String sentence = firstSentence(chunks.get(index).chunk().content());
            statements.add((index + 1) + ". " + sentence + " [" + (index + 1) + "]");
        }
        String answer = "相关文献摘录：\n"
                + String.join("\n", statements)
                + "\n\n以上内容用于专科文献检索参考，不替代医生诊断和处方。";
        double topScore = chunks.get(0).score();
        double confidence = version == IterationVersion.V3_GUARDED
                ? Math.min(0.96, 0.65 + topScore * 1.4)
                : Math.min(0.88, 0.48 + topScore * 1.2);
        return new GeneratedAnswer(
                answer,
                false,
                "",
                confidence,
                toCitations(retrievalOutput, statementLimit)
        );
    }

    private GeneratedAnswer refusal(
            String reason,
            String answer,
            RetrievalOutput retrievalOutput,
            int citationLimit
    ) {
        return new GeneratedAnswer(
                answer,
                true,
                reason,
                0.2,
                toCitations(retrievalOutput, citationLimit)
        );
    }

    private List<CitationResponse> toCitations(RetrievalOutput retrievalOutput, int limit) {
        return retrievalOutput.chunks().stream()
                .limit(limit)
                .map(scored -> new CitationResponse(
                        scored.chunk().id(),
                        scored.chunk().documentId(),
                        scored.chunk().title(),
                        scored.chunk().sourceType(),
                        scored.chunk().publisher(),
                        scored.chunk().publicationYear(),
                        scored.chunk().disease(),
                        scored.chunk().section(),
                        scored.chunk().pageLabel(),
                        scored.chunk().evidenceLevel(),
                        scored.chunk().content(),
                        round(scored.score())
                ))
                .toList();
    }

    static String firstSentence(String value) {
        String text = value == null ? "" : value.trim();
        var stop = java.util.regex.Pattern.compile("[。！？.!?](?:\\s|$)|[。！？]|[\\r\\n]").matcher(text);
        int end = stop.find() ? stop.start() + 1 : text.length();
        String excerpt = text.substring(0, Math.min(end, 400)).trim();
        return end > 400 ? excerpt + "…" : excerpt;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record GeneratedAnswer(
            String answer,
            boolean refused,
            String refusalReason,
            double confidence,
            List<CitationResponse> citations
    ) {
        public GeneratedAnswer {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }
}

