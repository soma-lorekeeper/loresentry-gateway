package com.loresentry.gateway.web;

/**
 * gateway 자신이 만드는 실패. 업스트림이 준 실패는 이 타입이 되지 않고 본문 그대로 통과한다.
 *
 * <p>코드 이름과 응답 모양은 authentication·content와 같다. 셋이 같은 클라이언트에게 답하므로
 * 오류 표현이 갈리면 프론트엔드가 "누가 답했는가"에 따라 분기해야 한다.
 */
public class GatewayFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        USER_CONTEXT_REQUIRED,
        UPSTREAM_UNAVAILABLE,
        INTERNAL_ERROR
    }

    private final Reason reason;

    public GatewayFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
