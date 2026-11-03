package com.loresentry.gateway.logging;

import java.util.function.BiConsumer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * RestClient를 거치지 않는 호출(채팅 SSE의 Apache HttpPost)에 지금 span의 traceparent를 직접 싣는다.
 * RestClient 호출은 observation이 자동으로 붙이므로 이 클래스를 쓰지 않는다.
 */
public final class TracePropagation {
    /** span이 없을 때(테스트 등) 아무것도 싣지 않는 객체. */
    public static final TracePropagation NONE = new TracePropagation(null, null);

    private final Tracer tracer;
    private final Propagator propagator;

    public TracePropagation(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** 지금 span이 있으면 W3C 헤더(traceparent 등)를 header에 넣는다. */
    public void inject(BiConsumer<String, String> header) {
        if (tracer == null || propagator == null) return;
        Span span = tracer.currentSpan();
        if (span == null) return;
        propagator.inject(span.context(), header, BiConsumer::accept);
    }
}
