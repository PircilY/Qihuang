package com.tcm.ragassistant.domain;

import com.tcm.ragassistant.exception.BusinessException;

import java.util.Locale;

public enum CorpusId {
    BASELINE,
    EXPANDED,
    COMBINED;

    public static CorpusId from(String value) {
        if (value == null || value.isBlank()) {
            return BASELINE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("INVALID_CORPUS", "不支持的语料版本：" + value);
        }
    }
}
