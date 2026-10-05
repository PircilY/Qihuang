package com.tcm.ragassistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record KnowledgeImportRequest(
        @NotBlank(message = "文档标题不能为空")
        @Size(max = 160, message = "文档标题不能超过 160 个字符")
        String title,

        @NotBlank(message = "来源类型不能为空")
        @Size(max = 40)
        String sourceType,

        @NotBlank(message = "发布机构不能为空")
        @Size(max = 160)
        String publisher,

        @Min(value = 1900, message = "出版年份不正确")
        @Max(value = 2100, message = "出版年份不正确")
        int publicationYear,

        @Size(max = 200) String authority,
        @Pattern(regexp = "未评级|A|B|C|古籍|规则", message = "证据等级不正确")
        String evidenceLevel,
        @Size(max = 80) String disease,
        @Size(max = 30) List<@NotBlank @Size(max = 80) String> syndromes,
        @Size(max = 30) List<@NotBlank @Size(max = 80) String> formulas,
        @Size(max = 30) List<@NotBlank @Size(max = 80) String> herbs,
        @Size(max = 160) String section,
        @Size(max = 40) String version,
        @NotBlank(message = "授权说明不能为空")
        @Size(max = 500)
        String licenseNote,
        @Size(max = 30) List<@NotBlank @Size(max = 80) String> keywords,

        @NotBlank(message = "正文不能为空")
        @Size(min = 30, max = 12000, message = "正文长度应在 30-12000 个字符之间")
        String content,
        @NotBlank(message = "原文链接不能为空")
        @Size(max = 2048)
        String sourceUrl
) {
}
