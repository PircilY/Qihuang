package com.tcm.ragassistant.service.remote;

import java.util.List;

public class KnowledgeOutOfSyncException extends RuntimeException {
    private final String remoteRevision;
    private final List<RemoteDocument> documents;

    public KnowledgeOutOfSyncException(String remoteRevision, List<RemoteDocument> documents, Throwable cause) {
        super("Remote knowledge revision does not match", cause);
        this.remoteRevision = remoteRevision;
        this.documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public String remoteRevision() { return remoteRevision; }
    public List<RemoteDocument> documents() { return documents; }

    public record RemoteDocument(String documentId, String checksum) {
    }
}
