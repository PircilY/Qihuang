package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.IterationVersion;
import com.tcm.ragassistant.dto.CitationResponse;
import com.tcm.ragassistant.service.retrieval.RetrievalOutput;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class AnswerGenerationService {

    private static final Pattern INDIVIDUAL_PRESCRIPTION_PATTERN = Pattern.compile(
            "(给我|帮我|直接|能不能|可以|该不该).*(处方|用药|吃|服|剂量)|"
                    + "(处方|剂量|每天吃|吃多少|怎么吃).*(吗|呢|合适|可以)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EMERGENCY_PATTERN = Pattern.compile(
            "(胸痛|胸闷|呼吸困难|晕厥|意识异常|大汗).*(持续|不缓解|加重|怎么办|急救|急诊|就医)|"
                    + "(立即|马上|急救|急诊).*(胸痛|胸闷|呼吸困难|晕厥|意识异常)",
            Pattern.CASE_INSENSITIVE
    );

    public GeneratedAnswer generate(
            String question,
            IterationVersion version,
            RetrievalOutput retrievalOutput
    ) {
        if (version == IterationVersion.V0_DIRECT) {
            return new GeneratedAnswer(
                    "当前版本没有检索知识库，不能为这个问题提供可追溯的专科证据。"
                            + "演示用途下，只能提示中医心血管诊疗需要结合四诊信息并由专业医师判断。",
                    false,
                    "",
                    0.35,
                    List.of()
            );
        }

        if (version == IterationVersion.V3_GUARDED
                && INDIVIDUAL_PRESCRIPTION_PATTERN.matcher(question).find()) {
            return refusal(
                    "涉及个体化处方或剂量判断，超出知识问答边界",
                    "不能根据单段文字直接为具体患者确定处方或剂量。"
                            + "系统仅展示一般性的文献依据，个体用药必须结合面诊、既往史、合并用药和医师判断。",
                    retrievalOutput,
                    2
            );
        }

        if (version == IterationVersion.V3_GUARDED
                && EMERGENCY_PATTERN.matcher(question).find()) {
            return refusal(
                    "问题可能涉及急症，超出线上问答边界",
                    "胸痛持续不缓解、呼吸困难、晕厥或意识异常需要立即就医或联系急救。"
                            + "本系统不进行急诊分诊，也不替代现场医疗处置。",
                    retrievalOutput,
                    1
            );
        }

        if (retrievalOutput.chunks().isEmpty()) {
            return new GeneratedAnswer(
                    "当前知识库中没有找到足以支持回答的证据。系统已停止生成具体诊疗建议。",
                    true,
                    "未检索到达到阈值的相关证据",
                    0.2,
                    List.of()
            );
        }

        List<ScoredChunk> chunks = retrievalOutput.chunks();
        List<String> statements = new ArrayList<>();
        int statementLimit = Math.min(3, chunks.size());
        for (int index = 0; index < statementLimit; index++) {
            String sentence = firstSentence(chunks.get(index).chunk().content());
            statements.add((index + 1) + ". " + sentence + " [" + (index + 1) + "]");
        }
        String answer = "依据已检索的证据，可以整理为：\n"
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
                0.72,
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
                        abbreviate(scored.chunk().content(), 120),
                        round(scored.score())
                ))
                .toList();
    }

    private String firstSentence(String value) {
        int firstStop = value.indexOf('。');
        if (firstStop < 0) {
            return value.trim();
        }
        return value.substring(0, firstStop + 1).trim();
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
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

