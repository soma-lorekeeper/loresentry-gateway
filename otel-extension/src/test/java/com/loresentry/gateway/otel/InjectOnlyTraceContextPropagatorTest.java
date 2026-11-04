package com.loresentry.gateway.otel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;
import org.junit.jupiter.api.Test;

/**
 * gateway의 inject-only 전파기를 확인한다(docs LOG_SCHEMA.md 7절 "시작점").
 * <ul>
 *   <li>인터넷에서 온 traceparent·tracestate·baggage는 읽지 않는다. agent의 Tomcat 서버 span이 부모 없이(새 trace로) 시작한다</li>
 *   <li>나가는 호출에는 지금 문맥의 traceparent를 W3C 형식으로 넣는다</li>
 *   <li>agent가 otel.propagators의 이름(tracecontext-inject-only)으로 이 전파기를 찾는다</li>
 * </ul>
 */
class InjectOnlyTraceContextPropagatorTest {
    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN = "b7ad6b7169203331";

    /** 요청 헤더 흉내(Map)에서 값을 읽는 어댑터. */
    private static final TextMapGetter<Map<String, String>> HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    @Test
    void extractIgnoresAValidTraceparentFromTheInternet() {
        Map<String, String> incoming = Map.of("traceparent", "00-" + TRACE + "-" + SPAN + "-01",
                "tracestate", "vendor=value", "baggage", "user=someone@example.com");

        Context extracted = InjectOnlyTraceContextPropagator.INSTANCE.extract(Context.root(), incoming, HEADERS);

        // 받은 문맥(root) 그대로라 부모 span이 없다. 서버 span은 새 trace로 시작한다
        assertThat(extracted).isSameAs(Context.root());
        assertThat(Span.fromContext(extracted).getSpanContext().isValid()).isFalse();
    }

    @Test
    void extractKeepsTheContextItWasGiven() {
        // 이미 지금 문맥에 span이 있으면(계측이 넘긴 문맥) 그 문맥을 바꾸지 않는다. 들어온 헤더의 trace로 바꿔치지 않는다
        Context current = Context.root().with(Span.wrap(SpanContext.create(TRACE, SPAN, TraceFlags.getSampled(), TraceState.getDefault())));
        Map<String, String> incoming = Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        Context extracted = InjectOnlyTraceContextPropagator.INSTANCE.extract(current, incoming, HEADERS);

        assertThat(extracted).isSameAs(current);
        assertThat(Span.fromContext(extracted).getSpanContext().getTraceId()).isEqualTo(TRACE);
    }

    @Test
    void injectWritesTheTraceparentOfTheCurrentContext() {
        Context current = Context.root().with(Span.wrap(SpanContext.create(TRACE, SPAN, TraceFlags.getSampled(), TraceState.getDefault())));
        Map<String, String> outgoing = new HashMap<>();

        InjectOnlyTraceContextPropagator.INSTANCE.inject(current, outgoing, Map::put);

        assertThat(outgoing).containsExactly(Map.entry("traceparent", "00-" + TRACE + "-" + SPAN + "-01"));
        assertThat(InjectOnlyTraceContextPropagator.INSTANCE.fields()).contains("traceparent");
    }

    @Test
    void injectWritesNothingWithoutASpan() {
        Map<String, String> outgoing = new HashMap<>();

        InjectOnlyTraceContextPropagator.INSTANCE.inject(Context.root(), outgoing, Map::put);

        assertThat(outgoing).isEmpty();
    }

    @Test
    void theAgentFindsThePropagatorByItsConfiguredName() {
        // agent는 확장 jar의 META-INF/services 등록을 ServiceLoader로 읽고 otel.propagators의 이름과 같은 것을 고른다
        var providers = ServiceLoader.load(ConfigurablePropagatorProvider.class).stream().map(ServiceLoader.Provider::get).toList();

        assertThat(providers).filteredOn(provider -> provider.getName().equals("tracecontext-inject-only")).singleElement()
                .satisfies(provider -> assertThat(provider.getPropagator(null)).isSameAs(InjectOnlyTraceContextPropagator.INSTANCE));
    }
}
