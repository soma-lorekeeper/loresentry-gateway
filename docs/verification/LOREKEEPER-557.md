# 환경과 CORS 검증

2026-09-24, LOREKEEPER-557. `local` 또는 `prod` 하나를 명시해야 기동한다.
개발 내부 주소와 JWT 공개키 위치는 실행 환경에서 주입하며 운영 주소로 대체하지 않는다.
브라우저 주소·고정 로그인 복귀 주소·Secure 설정은 선택한 환경과 일치해야 한다.

Java 21의 `./gradlew --no-daemon build` 성공: 85개 테스트, 실패·오류·생략 0개.
프로필 누락·동시 활성화, 환경 혼합, 빈 키 위치·서비스 주소와 임의 복귀 주소를 거절했다.
공개키 내용·RSA 강도 검증은 LOREKEEPER-561에서 적용한다.

CORS 필터는 정확한 Origin만 허용한다. 유사 도메인·하위 도메인·임의 localhost 포트·
127.0.0.1·null·후행 슬래시·중복 Origin을 거절한다. Content 메서드·저장 헤더와
Location 노출, credentials, Vary, 3600초 사전 요청 캐시를 확인했다.
테스트용 주소는 testfixture 프로필에만 있으며 운영 기본값이 아니다.
