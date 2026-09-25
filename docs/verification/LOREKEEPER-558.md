# CSRF 검증

> 이전 구현의 실행 기록이다. 2026-09-26 단일 세션 ID 설계의 구현·검증 완료 근거로 사용하지 않는다. 당시 결과와 수치는 보존한다.

2026-09-24, LOREKEEPER-558. POST·PUT·PATCH·DELETE에 정확한 Origin과 단일
X-LS-CSRF: 1을 요구한다. /auth 전체 예외는 없으며 refresh·revoke도 검사한다.
거절 응답은 403 CSRF_REJECTED/NONE, no-store이며 Set-Cookie를 발급하지 않는다.
허용된 Origin은 CSRF 오류도 CORS로 읽을 수 있다.

Java 21 `./gradlew --no-daemon build` 성공: 107개 테스트, 실패·오류·생략 0개.
MockMvc 필터 체인에서 Origin·헤더 누락, 중복, null, 유사 값, 폼·본문 대체를
거절하고 후속 인증 처리 횟수가 0임을 확인했다. 유효한 CSRF만 후속 인증에 도달했다.
현재 이 테스트의 인증 단계는 AT 부재 오류를 내는 검증용 필터이며 실제 JWT는
LOREKEEPER-561에서 연결한다.

OPTIONS는 CSRF를 건너뛰고 CORS가 인증 전에 처리한다. OAuth prepare/callback GET은
Origin·전용 헤더 없이 페이지 이동할 수 있다. HttpSession이나 CSRF 저장소는 생성하지 않는다.
