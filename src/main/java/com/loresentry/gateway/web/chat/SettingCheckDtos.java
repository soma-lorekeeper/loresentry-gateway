package com.loresentry.gateway.web.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.gateway.client.chat.SettingCheckData;

public final class SettingCheckDtos {
    private SettingCheckDtos() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Read(int manuscripts, @JsonProperty("from_ordinal") Integer fromOrdinal, @JsonProperty("to_ordinal") Integer toOrdinal,
                       int settings, @JsonProperty("core_facts") int coreFacts) {
        public static Read from(SettingCheckData.Read value) {
            return new Read(value.manuscripts(), value.fromOrdinal(), value.toOrdinal(), value.settings(), value.coreFacts());
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Counts(int open, int intended, @JsonProperty("not_conflict") int notConflict) {
        public static Counts from(SettingCheckData.Counts value) { return new Counts(value.open(), value.intended(), value.notConflict()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Source(@JsonProperty("document_id") UUID documentId, String title, @JsonProperty("doc_type") String docType, Integer ordinal, String quote,
                         @JsonProperty("quote_focus") String quoteFocus) {
        public static Source from(SettingCheckData.Source value) {
            return new Source(value.documentId(), value.title(), value.docType(), value.ordinal(), value.quote(), value.quoteFocus());
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Finding(UUID id, String kind, boolean core, String confidence, String summary, String direction, String quote,
                          @JsonProperty("quote_focus") String quoteFocus, Source source, String verdict, String reason) {
        public static Finding from(SettingCheckData.Finding value) {
            return new Finding(value.id(), value.kind(), value.core(), value.confidence(), value.summary(), value.direction(), value.quote(),
                    value.quoteFocus(), Source.from(value.source()), value.verdict(), value.reason());
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Run(UUID id, @JsonProperty("project_id") UUID projectId, @JsonProperty("document_id") UUID documentId,
                      @JsonProperty("document_title") String documentTitle, @JsonProperty("revision_no") long revisionNo, String status, String stage,
                      @JsonProperty("created_at") OffsetDateTime createdAt, @JsonProperty("finished_at") OffsetDateTime finishedAt,
                      Read read, Counts counts, @JsonProperty("failure_code") String failureCode,
                      @JsonInclude(JsonInclude.Include.NON_NULL) List<Finding> findings) {
        public static Run from(SettingCheckData.Run value) { return of(value, null); }
        public static Run withFindings(SettingCheckData.Run value) { return of(value, value.findings().stream().map(Finding::from).toList()); }
        private static Run of(SettingCheckData.Run value, List<Finding> findings) {
            return new Run(value.id(), value.projectId(), value.documentId(), value.documentTitle(), value.revisionNo(), value.status(), value.stage(),
                    value.createdAt(), value.finishedAt(), Read.from(value.read()), Counts.from(value.counts()), value.failureCode(), findings);
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Chapter(@JsonProperty("document_id") UUID documentId, Run latest) {
        public static Chapter from(SettingCheckData.Chapter value) { return new Chapter(value.documentId(), Run.from(value.latest())); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(Run running, List<Chapter> chapters) {
        public static Summary from(SettingCheckData.Summary value) {
            return new Summary(value.running() == null ? null : Run.from(value.running()), value.chapters().stream().map(Chapter::from).toList());
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Runs(List<Run> runs) {
        public static Runs from(SettingCheckData.Runs value) { return new Runs(value.runs().stream().map(Run::from).toList()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Start(@JsonProperty("document_id") UUID documentId) {
        public SettingCheckData.Start internal() { return new SettingCheckData.Start(documentId); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Verdict(String verdict, String reason) {
        public SettingCheckData.Verdict internal() { return new SettingCheckData.Verdict(verdict, reason); }
        @Override public String toString() { return "Verdict[redacted]"; }
    }
}
