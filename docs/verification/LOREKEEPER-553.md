# LOREKEEPER-553 검증

> 이전 구현의 실행 기록이다. 2026-09-26 단일 세션 ID 설계의 구현·검증 완료 근거로 사용하지 않는다. 당시 결과와 수치는 보존한다.

2026-09-24. Content API 26개를 명시적 web 컨트롤러·application·client로 전환했다.
외부와 내부 DTO는 별도 클래스이며 application에는 Servlet·ResponseEntity·SecurityContext
참조가 없다. 연결 확인·DB health의 내부 호출도 ProbeService로 옮겼다.

- Java 21 Docker 환경에서 `./gradlew --no-daemon build` 성공.
- 테스트 51개, 실패·오류·건너뜀 0개.
- 프로젝트 생성의 상대 Location·nullable 필드, 문서 저장 revision·멱등 헤더,
  중복 헤더 거절, 임의 하위 경로·미지원 메서드 차단, 204, 한글·예약 문자 검색과
  Cookie·Authorization 미전달을 검증했다.
- application의 HTTP·web·security 의존과 client의 web·application·security 의존을
  소스 검사했으며 해당 참조가 없다.

보안 인증은 LOREKEEPER-555에서 구현한다. 엄격한 내부 응답 검증·실패 분류·전송 계층의
재시도 금지는 다음 Atomic LOREKEEPER-554 범위다. 실제 Content·브라우저·운영 검증은
이번 실행에 포함하지 않았다.
