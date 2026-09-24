package com.loresentry.gateway.identity;

import java.util.Collections;
import java.util.UUID;

import com.loresentry.gateway.web.GatewayFailure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * <b>임시 방식이다.</b> 클라이언트가 보낸 {@code X-User-Id}를 그대로 신원으로 인정한다.
 *
 * <p>즉 <b>지금 이 gateway는 인증을 하지 않는다.</b> 헤더에 아무 UUID나 넣으면 그 사용자의 프로젝트를
 * 읽고 쓸 수 있다. 프론트엔드를 실제 API에 붙이기 위해 의도적으로 택한 단계이고, JWT 검증이 들어오면
 * 이 클래스를 교체해 없앤다. 그때 검증한 신원으로 헤더를 <b>덮어쓰므로</b> 클라이언트가 보낸 값은
 * 자동으로 무력화된다.
 *
 * <p>거절 규칙은 authentication·content와 같다. 값이 없는 것과 값이 틀린 것을 구분한다.
 */
@Component
public class ClientHeaderIdentityResolver implements IdentityResolver {

    public static final String HEADER = "X-User-Id";

    @Override
    public UUID resolve(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders(HEADER));
        if (values.isEmpty()) {
            throw new GatewayFailure(GatewayFailure.Reason.USER_CONTEXT_REQUIRED);
        }
        if (values.size() != 1) {
            throw new GatewayFailure(GatewayFailure.Reason.INVALID_REQUEST);
        }

        try {
            String text = values.getFirst();
            UUID id = UUID.fromString(text);
            // UUID.fromString은 비정규 표기도 받아들인다. 왕복해서 같아야만 통과시킨다.
            if (!id.toString().equalsIgnoreCase(text)) {
                throw new GatewayFailure(GatewayFailure.Reason.INVALID_REQUEST);
            }
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new GatewayFailure(GatewayFailure.Reason.INVALID_REQUEST);
        }
    }
}
