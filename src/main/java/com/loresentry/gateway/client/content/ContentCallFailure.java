package com.loresentry.gateway.client.content;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ContentCallFailure extends RuntimeException {
    /**
     * 최신화 오류가 code·message 옆에 싣는 상세 값. code 마다 하나만 채워지고 나머지는 null 이다.
     * 이름은 Content 오류 본문의 키와 같다.
     *
     * @param current   REFRESH_DRAFT_CONFLICT: 먼저 저장된 작업본의 지금 모습
     * @param drafts    REFRESH_DRAFTS_UNMATCHED: 아직 두 쪽을 맞추지 않은 작업본 id
     * @param documents REFRESH_DOCUMENTS_CHANGED: 작업본을 뜬 뒤 바뀐 문서
     * @param problems  REFRESH_DRAFTS_INVALID: 그대로 반영할 수 없는 이유. code 와 문자열 값들이다
     */
    public record Details(ContentData.DraftConflict current, List<UUID> drafts,
                          List<ContentData.ChangedDocument> documents, List<Map<String, String>> problems) {}

    private final int status;
    private final String code;
    private final ContentData.Content current;
    private final ContentData.Snapshot base;
    private final Details details;

    /** DOCUMENT_CONFLICT 는 current·base, 그 밖의 오류는 둘 다 null 이다. */
    public ContentCallFailure(int status, String code, ContentData.Content current, ContentData.Snapshot base) {
        this(status, code, current, base, null);
    }
    /** 최신화 오류. 상세 값이 없는 code 면 details 는 null 이다. */
    public ContentCallFailure(int status, String code, Details details) {
        this(status, code, null, null, details);
    }
    private ContentCallFailure(int status, String code, ContentData.Content current, ContentData.Snapshot base, Details details) {
        super(code);
        this.status = status; this.code = code; this.current = current; this.base = base; this.details = details;
    }
    public int status() { return status; }
    public String code() { return code; }
    public ContentData.Content current() { return current; }
    public ContentData.Snapshot base() { return base; }
    public Details details() { return details; }
    public static ContentCallFailure invalid() { return new ContentCallFailure(502, "UPSTREAM_INVALID_RESPONSE", null, null); }
    public static ContentCallFailure unavailable() { return new ContentCallFailure(503, "CONTENT_UNAVAILABLE", null, null); }
}
