# 로그아웃 검증

2026-09-24, LOREKEEPER-569. POST /auth/tokens/revoke는 CSRF를 통과하면 유효한 AT를
요구하지 않고 RT 쿠키만 사용한다. RT가 없으면 Auth를 호출하지 않는다. 성공·거절·장애의
서버 폐기 결과와 브라우저 쿠키 삭제 헤더를 구분한다.

Java 21 `./gradlew --no-daemon build` 성공: 일반 테스트 187개, 실패·오류·생략 0개.
모의 Auth와 MVC로 confirmed/not_requested(200), rejected(401), unconfirmed(503)를 확인했다.
Auth의 오류·시간 초과에도 동일 범위의 AT·RT Max-Age=0 헤더를 발급했고, CSRF 거절에는
쿠키 삭제와 Auth 호출이 없었다. 로그아웃 실패의 next_action은 NONE이며 재발급을 요청하지 않는다.

RT 중복 등 폐기 대상을 확정할 수 없는 입력이나 예기치 않은 처리 예외도 unconfirmed로
종료하며 쿠키 삭제를 누락하지 않는다. BFF 재시도와 백그라운드 폐기는 없다.
실제 브라우저 적용을 보증하는 cookies_deleted 필드를 반환하지 않는다.
