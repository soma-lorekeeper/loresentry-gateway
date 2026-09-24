# OAuth 브라우저 응답 검증

2026-09-24, LOREKEEPER-566. 로그인 준비 GET은 임시 쿠키 설정 후 Google로 302 이동하고,
콜백 GET은 고정 프론트 /login?result=...로 303 이동한다. 승인되지 않은 인가 호스트·경로는
거절한다. HEAD는 임시 상태를 만들지 않도록 명시적으로 405를 반환한다.

Java 21 `./gradlew --no-daemon build`와 `git diff --check`를 통과했다.
MockMvc로 성공·cancelled·invalid·unavailable·failed, local/prod 주소·쿠키 속성을 확인했다.
Host·전달 헤더·returnUrl·제공자 오류 설명은 결과 URL을 바꾸지 못하며 code·state·토큰을
결과 주소나 본문으로 복사하지 않는다. no-store와 no-referrer를 적용한다.

소비 true일 때만 OAuth 쿠키를 삭제한다. 소비 false/null은 유지하며 실패 시 기존 AT·RT를
삭제하지 않는다. 잘못된 성공 토큰 쌍도 일부 인증 쿠키를 먼저 설정하지 않는다.
[프론트 인계](../FRONTEND_AUTH_CONTRACT.md)에 result=success 이후 users/me 확인을 명시했다.
실제 Google·프론트 브라우저 연동은 후속 통합 검증 범위다.
