# LOREKEEPER-585 검증 기록

단일 `ls_session`/`__Host-ls_session` 쿠키 발급·삭제와 응답 시점의 활동 수명 계산을 구현했다.
HttpOnly·Strict·Path=/·Domain 생략과 환경별 Secure를 적용한다. 남은 시간은 초 단위로 내림하고
14일을 상한으로 하며, 잘못된 ID·만료에서 기존 쿠키를 변경하지 않는다.

Java 21에서 SessionCookiesTest와 기존 AuthCookiesTest의 12개 테스트가 통과했다.
환경별 발급/삭제 범위, 시계 이동·지연·내림·상한, 도메인 오류 응답의 같은 ID 갱신,
빈 응답·reset의 중복 방지와 no-store 강제를 확인했다. `git diff --check`도 통과했다.

```bash
./gradlew --no-daemon --max-workers=2 test --tests '*SessionCookiesTest' --tests '*AuthCookiesTest'
```

보호 필터의 성공 경로 연결과 공개/실패 경로 비연장은 LOREKEEPER-586에서 검증한다.
