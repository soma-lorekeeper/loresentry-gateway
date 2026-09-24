# 명시적 재발급 검증

2026-09-24, LOREKEEPER-568. POST /auth/tokens/refresh는 CSRF 후 RT 쿠키만 읽는다.
본문·쿼리 토큰을 사용하지 않으며 유효한 AT를 요구하지 않는다. RT가 없으면 Auth를
호출하지 않고 REFRESH_REJECTED/RELOGIN을 반환한다. 성공은 두 쿠키를 함께 교체하는
204 응답이며 본문이 없다.

Java 21 `./gradlew --no-daemon build` 성공: 일반 테스트 180개, 실패·오류·생략 0개.
MVC와 모의 Auth로 성공, 알려진 오류, 잘못된 성공 토큰 쌍·만료, 응답 유실을 검증했다.
실패 응답에는 Set-Cookie가 없고 내부 오류 원문을 외부에 노출하지 않는다.

실제 TCP 연결 거절은 전송 전 실패로 확인해 REFRESH_UNAVAILABLE/RETRY_LATER로 처리했다.
서버가 요청을 받은 뒤 응답 없이 끊으면 REFRESH_OUTCOME_UNKNOWN/RELOGIN으로 처리했다.
후자의 POST 수신 횟수는 1회였으며 자동 재시도하지 않았다. 성공 응답 정보가 잘못돼도
실제 갱신 가능성이 있으므로 결과 불명으로 처리한다.
