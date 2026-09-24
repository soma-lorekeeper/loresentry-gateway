# Auth 로그인 client와 application 검증

2026-09-24, LOREKEEPER-565. prepare/callback은 Auth의 같은 경로에 POST로 호출한다.
로그인 정보는 JSON 본문으로만 전달하며 브라우저 Cookie·Authorization·사용자 헤더를 전달하지 않는다.
client 내부 DTO와 application 결과를 분리했고 사용자별 임시 상태를 BFF에 저장하지 않는다.

Java 21 `./gradlew --no-daemon build` 성공: 일반 테스트 158개, 실패·오류·생략 0개.
모의 Auth로 준비·성공·취소·입력 누락·알려진 오류·상태 불일치·손상 응답과 응답 유실을 검증했다.
토큰 결과가 잘못됐더라도 확인된 login_request_consumed는 보존하며, 통신 실패로 확인하지
못하면 null을 유지한다. 응답 본문 읽기 중 시간 초과도 통신 실패로 분류한다.

토큰·OAuth 입력 DTO와 준비 결과의 toString은 민감 값을 숨긴다. 토큰은 요청별 결과로만
반환한다. 외부 GET, 쿠키 설정·정리와 고정 리다이렉트는 LOREKEEPER-566에서 연결한다.
