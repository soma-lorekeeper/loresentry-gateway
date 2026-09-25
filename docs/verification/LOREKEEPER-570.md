# 본인 계정 API 검증

> 이전 구현의 실행 기록이다. 2026-09-26 단일 세션 ID 설계의 구현·검증 완료 근거로 사용하지 않는다. 당시 결과와 수치는 보존한다.

2026-09-24, LOREKEEPER-570. GET/PATCH /auth/users/me는 인증 경계가 제공한 UUID만
application에 전달한다. Auth 요청에는 이 UUID의 X-User-Id만 설정하며 브라우저 Cookie와
Authorization을 전달하지 않는다. PATCH 외부 입력은 display_name만 허용한다.

Java 21 `./gradlew --no-daemon build` 성공: 일반 테스트 202개, 실패·오류·생략 0개.
client와 MVC 테스트에서 조회·수정, email=null, 추가 입력 필드·문자열 강제 변환 거절,
알려진 오류와 응답 유실을 확인했다. 응답은 id·display_name·email의 외부 DTO로 구성하며,
다른 사용자 id나 필수 정보가 빠진 내부 응답은 502로 차단한다.

Auth의 USER_CONTEXT_REQUIRED는 해당 코드와 RELOGIN을 유지하며 ACCESS_TOKEN_MISSING이나
REFRESH 액션으로 바꾸지 않는다. 통신 실패는 ACCOUNT_UNAVAILABLE/RETRY_LATER,
잘못된 응답은 UPSTREAM_INVALID_RESPONSE/NONE이다. 수정 응답 유실을 자동 재시도하지 않는다.
민감 응답은 no-store이고 실패 시 쿠키를 변경하지 않는다. 실제 Auth·세션과의 전체 연결은
LOREKEEPER-573에서 검증한다.
