package com.loresentry.gateway.logging;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

/**
 * 로깅 팩토리({@link LogFlow}, {@link UpstreamCallLogInterceptor})가 우리 span을 열 때 쓰는 tracer와 실패 표시(LOG_SCHEMA.md 7절).
 *
 * <p>span은 EDOT Java agent가 연다: 브라우저 요청 하나(Tomcat 계측. gateway의 전파기는 들어온 traceparent를 읽지 않아 늘 새 trace다),
 * 내부 서비스 호출(Apache HttpClient 계측. RestClient와 채팅 SSE), 세션 저장소 명령(Lettuce 계측). 앱은 OpenTelemetry API만 갖고,
 * agent가 그 API를 자기 SDK에 잇는다. 그래서 {@link Span#current()}가 agent가 연 지금 span이고, {@link #tracer()}로 연 우리 span도
 * agent가 내보낸다. agent가 없는 테스트에서는 API가 아무것도 하지 않거나(지금 span 없음), 테스트가 꽂은 OpenTelemetry SDK가 대신한다.
 *
 * <p>서비스 코드는 이 클래스를 모른다. 추적 API는 logging 패키지 안에서만 쓴다(LOG_SCHEMA.md 5절 "만들기", ArchUnit).
 */
final class TraceContext {
    /**
     * 로깅 팩토리가 여는 span의 계측 이름(scope.name). gitops {@code logging/tools/apm_consistency.py}가 이 이름으로 우리 span을
     * 알아본다(흐름 밖에서 연 작업 span은 허용된 root다).
     */
    static final String INSTRUMENTATION_SCOPE = "com.loresentry.gateway.logging";

    private TraceContext() {
    }

    /**
     * 로깅 팩토리가 우리 span(외부 호출 감싸기, 작업)을 열 때 쓰는 tracer. 실행 중에는 agent의 tracer다.
     * 테스트가 SDK를 바꿔 꽂을 수 있도록 부를 때마다 꺼낸다(GlobalOpenTelemetry가 tracer를 캐시한다).
     */
    static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * 우리 span을 예외로 끝난 실패로 표시한다. span에는 예외 종류만 남는다(예외 event의 메시지와 스택은 collector가 지운다. 스택은 같은
     * span.id의 실패 줄 error.stack_trace로 본다).
     */
    static void failed(Span span, Throwable failure) {
        span.setStatus(StatusCode.ERROR, failure.getClass().getName());
        span.recordException(failure);
    }
}
