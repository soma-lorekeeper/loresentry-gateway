package com.loresentry.gateway.otel;

import java.util.Collection;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * 나가는 호출에 W3C {@code traceparent}를 넣기만 하고, 들어온 값은 읽지 않는 전파기(docs LOG_SCHEMA.md 7절 "시작점").
 *
 * <p>gateway는 인터넷에서 오는 요청을 받는 trace의 시작점이다. agent의 Tomcat 계측은 서블릿 필터보다 먼저 이 전파기로 요청 헤더를 읽어
 * 서버 span의 부모를 정한다. 여기서 {@link #extract}가 받은 문맥을 그대로 돌려주므로 부모가 없고, 외부 요청마다 새 trace가 열린다. 브라우저나
 * 공격자가 보낸 {@code traceparent}·{@code tracestate}·{@code baggage}는 trace에도, 내부 서비스로 가는 헤더에도 남지 않는다.
 *
 * <p>나가는 쪽(RestClient·채팅 SSE의 Apache HttpClient 계측)은 {@link #inject}로 지금 문맥의 {@code traceparent}를 넣는다. 일은
 * {@link W3CTraceContextPropagator}에 맡긴다. 그래서 형식(version 00, trace·span ID, flags)은 다른 서비스의 {@code tracecontext} 전파기와
 * 같고, 지금 문맥에 {@code tracestate}가 있으면(agent의 sampler가 gateway에서 시작한 trace에 쓰는 {@code ot=th:…}) 그것도 함께 실린다.
 * 들어온 {@code tracestate}는 읽지 않으므로 외부 값이 내부 서비스로 이어지지 않는다.
 */
public final class InjectOnlyTraceContextPropagator implements TextMapPropagator {
    /** 상태가 없어 하나만 둔다. */
    public static final InjectOnlyTraceContextPropagator INSTANCE = new InjectOnlyTraceContextPropagator();

    private static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();

    private InjectOnlyTraceContextPropagator() {
    }

    /** 이 전파기가 쓰는 헤더 이름(traceparent, tracestate). 계측이 헤더를 지우거나 다시 쓸 때 본다. */
    @Override
    public Collection<String> fields() {
        return W3C.fields();
    }

    /** 지금 문맥의 span을 {@code traceparent}(있으면 {@code tracestate}도) 헤더로 넣는다. 유효한 span이 없으면 아무것도 넣지 않는다. */
    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        W3C.inject(context, carrier, setter);
    }

    /** 들어온 헤더를 읽지 않는다. 받은 문맥을 그대로 돌려주므로 서버 span은 부모 없이(새 trace로) 시작한다. */
    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        return context;
    }

    @Override
    public String toString() {
        return "InjectOnlyTraceContextPropagator";
    }
}
