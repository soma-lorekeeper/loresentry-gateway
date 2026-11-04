package com.loresentry.gateway.otel;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;

/**
 * {@link InjectOnlyTraceContextPropagator}를 {@code otel.propagators}에 쓸 수 있는 이름({@value #NAME})으로 agent에 알린다.
 *
 * <p>agent는 시작할 때 확장 jar의 {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider}에
 * 적힌 이 클래스를 ServiceLoader로 찾고, {@code otel.propagators}(docker/otel-agent.properties)에 적힌 이름과 {@link #getName()}이 같은
 * 전파기를 쓴다.
 */
public final class InjectOnlyTraceContextPropagatorProvider implements ConfigurablePropagatorProvider {
    /** otel.propagators에 적는 이름. */
    public static final String NAME = "tracecontext-inject-only";

    /** 설정 값이 필요 없다. 늘 같은 전파기다. */
    @Override
    public TextMapPropagator getPropagator(ConfigProperties config) {
        return InjectOnlyTraceContextPropagator.INSTANCE;
    }

    @Override
    public String getName() {
        return NAME;
    }
}
