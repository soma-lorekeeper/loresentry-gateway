package com.loresentry.gateway.client.content;

public class ContentCallFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final ContentData.Content current;
    private final ContentData.Snapshot base;

    public ContentCallFailure(int status, String code, ContentData.Content current, ContentData.Snapshot base) {
        super(code);
        this.status = status; this.code = code; this.current = current; this.base = base;
    }
    public int status() { return status; }
    public String code() { return code; }
    public ContentData.Content current() { return current; }
    public ContentData.Snapshot base() { return base; }
    public static ContentCallFailure invalid() { return new ContentCallFailure(502, "UPSTREAM_INVALID_RESPONSE", null, null); }
    public static ContentCallFailure unavailable() { return new ContentCallFailure(503, "CONTENT_UNAVAILABLE", null, null); }
}
