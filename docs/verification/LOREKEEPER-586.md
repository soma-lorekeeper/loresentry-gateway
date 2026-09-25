# LOREKEEPER-586 검증 기록

2026-09-26, Work `LOREKEEPER-583`에서 보호 요청을 단일 세션 필터로 전환했다.
CSRF 검사 뒤 세션을 검증하며 공개 경로와 로그아웃은 활동 연장에서 제외한다.
검증 실패는 내부 호출과 쿠키 변경 없이 401 또는 503을 반환한다.
검증 성공 후 외부 사용자 헤더와 인증 정보를 제거하고 확인된 UUID만 전달한다.

Java 21에서 SessionFilterTest, CsrfFilterTest, ContentApiRoutesTest,
AccountControllerTest, ContentApiClientTest, AuthAccountClientTest,
GatewayApplicationTests의 74개 테스트를 통과했다. 도메인 오류의 상태와 본문,
쿠키 갱신 및 no-store 유지도 확인했다. Redis 동작 검증은 584 기록을 따른다.

기존 필터 클래스와 JWT 설정의 삭제는 후속 Work 588 범위다.
