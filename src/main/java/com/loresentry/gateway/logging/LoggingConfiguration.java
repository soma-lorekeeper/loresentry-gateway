package com.loresentry.gateway.logging;

import java.util.Map;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * 로그·trace 배선. LOG_SCHEMA.md 5·7절.
 *
 * <ul>
 *   <li>필터 두 개: 외부 trace 헤더 숨김(HIGHEST_PRECEDENCE), 요청 시작점·접근 로그(+2). Spring observation 필터는 +1이다.</li>
 *   <li>헬스체크(/health, /health/db)는 span을 만들지 않는다. kubelet probe가 10~20초마다 부르기 때문이다.</li>
 *   <li>MVC 비동기 실행기(채팅 스트리밍)가 span과 AppLogger를 새 스레드로 옮기게 한다.</li>
 *   <li>모든 RestClient 호출에 결과 로그 interceptor를 붙인다.</li>
 *   <li>Boot 기본 오류 응답(/error)에도 trace_id를 싣는다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class LoggingConfiguration {

    public LoggingConfiguration() {
        AppLogger.registerContextAccessor();
    }

    @Bean
    public FilterRegistrationBean<TraceContextHidingFilter> traceContextHidingFilter() {
        var registration = new FilterRegistrationBean<>(new TraceContextHidingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestLogFilter> requestLogFilter(Tracer tracer) {
        var registration = new FilterRegistrationBean<>(new RequestLogFilter(tracer));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    /** 헬스체크 요청에는 observation(=span)을 만들지 않는다. 그 요청의 로그에는 trace가 없다. */
    @Bean
    public ObservationPredicate skipHealthCheckObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier() != null && health(server.getCarrier().getRequestURI()));
    }

    /** Boot가 applicationTaskExecutor(MVC 비동기 포함)에 적용한다. context-propagation에 등록한 값을 새 스레드로 옮긴다. */
    @Bean
    public TaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    @Bean
    public RestClientCustomizer upstreamCallLogging() {
        return builder -> builder.requestInterceptor(new UpstreamCallLogInterceptor());
    }

    @Bean
    public TracePropagation tracePropagation(Tracer tracer, Propagator propagator) {
        return new TracePropagation(tracer, propagator);
    }

    /** Boot 기본 오류 본문({timestamp, status, error, path})에 trace_id를 더한다. 값은 RequestLogFilter가 요청 속성에 남긴다. */
    @Bean
    public DefaultErrorAttributes errorAttributes() {
        return new DefaultErrorAttributes() {
            @Override
            public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
                Map<String, Object> attributes = super.getErrorAttributes(request, options);
                Object traceId = request.getAttribute(RequestLogFilter.TRACE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                if (traceId != null) attributes.put("trace_id", traceId);
                return attributes;
            }
        };
    }

    private static boolean health(String path) {
        return path != null && ("/health".equals(path) || path.startsWith("/health/"));
    }
}
