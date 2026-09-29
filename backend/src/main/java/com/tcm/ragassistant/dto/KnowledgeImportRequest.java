package com.tcm.ragassistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record KnowledgeImportRequest(
        @NotBlank(message = "文档标题不能为空")
        @Size(max = 160, message = "文档标题不能超过 160 个字符")
        String title,

        @NotBlank(message = "来源类型不能为空")
        String sourceType,

        @NotBlank(message = "发布机构不能为空")
        String publisher,

        @Min(value = 1900, message = "出版年份不正确")
        @Max(value = 2100, message = "出版年份不正确")
        int publicationYear,

        String authority,
        String evidenceLevel,
        String disease,
        List<String> syndromes,
        List<String> formulas,
        List<String> herbs,
        String section,
        String version,
        String licenseNote,
        List<String> keywords,

        @NotBlank(message = "正文不能为空")
        @Size(min = 30, max = 12000, message = "正文长度应在 30-12000 个字符之间")
        String content
) {
}
