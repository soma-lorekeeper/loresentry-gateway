package com.loresentry.gateway.client.chat;

public class ChatCallFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final String nextAction;

    public ChatCallFailure(int status, String code, String nextAction) {
        super(code);
        this.status = status; this.code = code; this.nextAction = nextAction;
    }
    public int status() { return status; }
    public String code() { return code; }
    public String nextAction() { return nextAction; }
    public static ChatCallFailure invalid() { return new ChatCallFailure(502, "UPSTREAM_INVALID_RESPONSE", "NONE"); }
    public static ChatCallFailure unavailable() { return new ChatCallFailure(503, "AI_CHAT_UNAVAILABLE", "RETRY_LATER"); }
}
