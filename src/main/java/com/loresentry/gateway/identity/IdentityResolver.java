package com.loresentry.gateway.identity;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청에서 호출자 신원을 얻는다. 이 인터페이스가 있는 이유는 지금의 임시 방식(§클라이언트 헤더)을
 * JWT 검증으로 바꿀 때 **갈아끼울 곳을 한 군데로 묶어 두기 위해서**다. 업스트림에 신원을 싣는 코드는
 * 이 결과만 보므로 나머지는 바뀌지 않는다.
 */
public interface IdentityResolver {

    UUID resolve(HttpServletRequest request);
}
