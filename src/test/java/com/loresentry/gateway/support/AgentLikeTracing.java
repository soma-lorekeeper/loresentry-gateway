package com.loresentry.gateway.support;

import java.io.IOException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 테스트에서 EDOT Java agent가 하던 일 가운데 로깅 팩토리와 테스트가 기대는 것을 흉내 낸다. 운영에서는 agent가 하고
 * (docker/otel-agent.properties, otel-extension의 inject-only 전파기), 테스트에는 agent가 없다. 모든 Spring 테스트에 붙도록 test
 * auto-configuration으로 등록한다(META-INF/spring/...AutoConfiguration.imports).
 * <ul>
 *   <li>요청: 모든 필터보다 먼저 서버 span을 열어 지금 문맥으로 둔다(Tomcat 계측). gateway의 전파기는 들어온 traceparent를 읽지 않으므로
 *       이 흉내도 요청 헤더를 보지 않고 늘 새 trace(부모 없는 span)를 연다. 전파기 자체는 otel-extension의 단위 테스트가 확인한다</li>
 *   <li>RestClient 호출: 보내는 순간의 지금 문맥으로 traceparent 헤더를 넣는다. 같은 이름의 헤더가 있으면 바꾼다(Apache HttpClient 계측이
 *       CLIENT span의 문맥으로 넣는 것과 같은 자리). 우리 결과 줄 interceptor(upstream call span)보다 안쪽에서 돈다</li>
 *   <li>채팅 SSE 호출: 같은 일을 하는 Apache HttpClient interceptor({@link #TRACEPARENT}). SSE용 HttpClient는 static 메서드로 만들어
 *       Spring이 붙여 줄 자리가 없으므로, 확인하는 테스트가 자기가 만든 HttpClient에 직접 붙인다</li>
 * </ul>
 *
 * <p>span은 이 클래스의 SDK tracer로 만들고 내보내지 않는다. 지금 문맥(OpenTelemetry context)에 놓이기만 하면 로깅 팩토리와 formatter가
 * agent 아래에서와 같이 읽는다.
 */
@AutoConfiguration
public class AgentLikeTracing {
    /** 서버 span을 여는 tracer. 내보내는 곳이 없다. */
    private static final Tracer TRACER = SdkTracerProvider.builder().build().get("agent-like-tracing");

    /**
     * 채팅 SSE의 Apache HttpClient 계측 흉내. 요청을 보내는 순간의 지금 문맥으로 traceparent를 넣는다(이미 있으면 바꾼다).
     * {@code HttpClients.custom().addRequestInterceptorLast(AgentLikeTracing.TRACEPARENT)}로 붙인다.
     */
    public static final HttpRequestInterceptor TRACEPARENT = (request, entity, context) -> {
        request.removeHeaders("traceparent");
        W3CTraceContextPropagator.getInstance().inject(Context.current(), request, (carrier, key, value) -> carrier.setHeader(key, value));
    };

    /** 모든 필터보다 먼저 서버 span을 연다(agent의 Tomcat 계측은 필터 체인 바깥에서 연다). 들어온 trace 헤더는 읽지 않는다. */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> agentLikeServerSpan() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Span span = TRACER.spanBuilder(request.getMethod()).setNoParent().setSpanKind(SpanKind.SERVER).startSpan();
                try (Scope ignored = span.makeCurrent()) {
                    chain.doFilter(request, response);
                } finally {
                    span.end();
                }
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * RestClient 호출의 Apache HttpClient 계측 흉내. Boot가 모든 RestClient.Builder에 붙인다. 운영 customizer(LoggingConfiguration의
     * 결과 줄 interceptor)보다 뒤에 붙어 그 interceptor 안쪽, 곧 upstream call span 안에서 돈다.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    RestClientCustomizer agentLikeTraceparent() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            W3CTraceContextPropagator.getInstance().inject(Context.current(), request,
                    (carrier, key, value) -> carrier.getHeaders().set(key, value));
            return execution.execute(request, body);
        });
    }
}
