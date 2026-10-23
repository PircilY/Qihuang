package com.tcm.ragassistant.service;

import com.tcm.ragassistant.domain.KnowledgeDocument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

public final class KnowledgeRevision {
    private KnowledgeRevision() {
    }

    public static String calculate(List<KnowledgeDocument> documents) {
        String canonical = documents.stream()
                .map(document -> new Entry(document.id(), document.checksum()))
                .sorted(Comparator.comparing(Entry::documentId).thenComparing(Entry::checksum))
                .map(entry -> entry.documentId() + "\t" + entry.checksum())
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Entry(String documentId, String checksum) {
        private Entry {
            documentId = documentId == null ? "" : documentId;
            checksum = checksum == null ? "" : checksum;
        }
    }
}
