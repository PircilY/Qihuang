package com.tcm.ragassistant.dto;

import java.util.List;

public record RetrievalTrace(
        String strategy,
        int lexicalCandidateCount,
        int vectorCandidateCount,
        int fusedCandidateCount,
        int selectedCount,
        boolean sufficientEvidence,
        List<String> notes
) {
    public RetrievalTrace {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}

