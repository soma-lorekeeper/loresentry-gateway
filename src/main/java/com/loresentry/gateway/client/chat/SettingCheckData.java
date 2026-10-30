package com.loresentry.gateway.client.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SettingCheckData {
    private SettingCheckData() {}
    public record Read(Integer manuscripts, @JsonProperty("from_ordinal") Integer fromOrdinal, @JsonProperty("to_ordinal") Integer toOrdinal,
                       Integer settings, @JsonProperty("core_facts") Integer coreFacts) {}
    public record Counts(Integer open, Integer intended, @JsonProperty("not_conflict") Integer notConflict) {}
    public record Source(@JsonProperty("document_id") UUID documentId, String title, @JsonProperty("doc_type") String docType, Integer ordinal, String quote,
                         @JsonProperty("quote_focus") String quoteFocus) {}
    public record Finding(UUID id, String kind, Boolean core, String confidence, String explanation, String quote,
                          @JsonProperty("quote_focus") String quoteFocus, Source source, String verdict, String reason) {}
    public record Run(UUID id, @JsonProperty("project_id") UUID projectId, @JsonProperty("document_id") UUID documentId,
                      @JsonProperty("document_title") String documentTitle, @JsonProperty("revision_no") Long revisionNo, String status, String stage,
                      @JsonProperty("created_at") OffsetDateTime createdAt, @JsonProperty("finished_at") OffsetDateTime finishedAt,
                      Read read, Counts counts, @JsonProperty("failure_code") String failureCode, List<Finding> findings) {}
    public record Chapter(@JsonProperty("document_id") UUID documentId, Run latest) {}
    public record Summary(Run running, List<Chapter> chapters) {}
    public record Runs(List<Run> runs) {}
    public record Decision(@JsonProperty("document_id") UUID documentId, @JsonProperty("document_title") String documentTitle,
                           @JsonProperty("decided_at") OffsetDateTime decidedAt, Finding finding) {}
    public record Decisions(List<Decision> verdicts) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Start(@JsonProperty("document_id") UUID documentId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Verdict(String verdict, String reason) {
        @Override public String toString() { return "Verdict[redacted]"; }
    }
}
