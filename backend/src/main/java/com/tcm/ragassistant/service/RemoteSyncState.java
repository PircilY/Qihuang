package com.tcm.ragassistant.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class RemoteSyncState {
    public enum Status { IN_SYNC, PENDING, UNKNOWN }

    private final AtomicReference<Status> status = new AtomicReference<>(Status.UNKNOWN);
    private volatile String revision;

    public Status status() {
        return status.get();
    }

    public String revision() {
        return revision;
    }

    public void markInSync(String revision) {
        this.revision = revision;
        status.set(Status.IN_SYNC);
    }

    public void markPending() {
        status.set(Status.PENDING);
    }

    public void markUnknown() {
        status.set(Status.UNKNOWN);
    }
}
