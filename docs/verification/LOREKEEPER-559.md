# 인증 쿠키 정책 검증

2026-09-24, LOREKEEPER-559. 쿠키는 web 경계에서 구성한다. HTTPS는 __Host- 접두사와
Secure, local은 접두사 없는 이름을 사용한다. 모든 쿠키는 HttpOnly·Path=/·Domain 생략이며,
AT/RT는 Strict, OAuth 임시는 Lax다. 삭제에도 같은 이름과 범위를 사용한다.

Java 21 `./gradlew --no-daemon build` 성공: 113개 테스트, 실패·오류·생략 0개.
고정 Clock으로 초 단위 내림, 900초·14일·300초 상한, 만료·1초 미만·누락 정보와
잘못된 토큰 문자열·동일 토큰 쌍을 검증했다. 두 쿠키 구성에 모두 성공한 후에만
Set-Cookie를 추가하며 두 번째 토큰이 잘못된 경우 첫 번째 쿠키도 설정하지 않았다.

토큰 결과의 toString은 값을 숨기며 공통 쿠키 처리는 본문·Location을 쓰지 않는다.
인증 API 응답은 no-store, OAuth 응답은 no-referrer를 적용한다. 실제 OAuth·refresh·revoke
컨트롤러의 성공·실패별 적용과 URL·본문 검증은 LOREKEEPER-563에서 수행한다.
