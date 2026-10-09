package com.tcm.ragassistant.dto;

import java.util.List;

public record RetrievalTrace(
        String strategy,
        int lexicalCandidateCount,
        int vectorCandidateCount,
        int fusedCandidateCount,
        int selectedCount,
        boolean sufficientEvidence,
        List<String> notes,
        String engine,
        String version,
        Double threshold,
        boolean rejected
) {
    public RetrievalTrace {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public RetrievalTrace(String strategy, int lexicalCandidateCount, int vectorCandidateCount,
                          int fusedCandidateCount, int selectedCount, boolean sufficientEvidence,
                          List<String> notes) {
        this(strategy, lexicalCandidateCount, vectorCandidateCount, fusedCandidateCount,
                selectedCount, sufficientEvidence, notes, "java-local", null, null,
                selectedCount == 0 && fusedCandidateCount > 0);
    }
}

