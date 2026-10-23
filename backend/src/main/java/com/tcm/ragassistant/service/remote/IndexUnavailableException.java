package com.tcm.ragassistant.service.remote;

public class IndexUnavailableException extends RuntimeException {
    private final String state;
    public IndexUnavailableException(String state) { super("Remote index " + state); this.state = state; }
    public String state() { return state; }
}
