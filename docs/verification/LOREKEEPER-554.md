# Content 경계와 HTTP 전송 검증

> 이전 구현의 실행 기록이다. 2026-09-26 단일 세션 ID 설계의 구현·검증 완료 근거로 사용하지 않는다. 당시 결과와 수치는 보존한다.

LOREKEEPER-554는 외부 요청의 알려지지 않은 필드·타입 강제 변환을 거절하고,
Content 성공 응답의 필수·중첩 필드와 오류 status/code/next_action을 확인한다.
업무 오류 메시지는 고정 문구로 변환하며 DOCUMENT_CONFLICT의 current/base는
명시적 외부 DTO로 보존한다. 추가 내부 필드와 내부 응답 헤더는 전달하지 않는다.

## 실행 결과

2026-09-24, Java 21 컨테이너에서 `./gradlew --no-daemon build` 성공.
테스트 72개, 실패·오류·생략 0개. `git diff --check` 통과.

- MVC: JSON 추가 필드·숫자/불리언의 문자열 변환·잘못된 JSON·연속 JSON 거절,
  잘못된 경로 UUID, 201 Location, 204, nullable 필드, 충돌 current/base를 확인했다.
- Mock HTTP: 한 번 인코딩한 검색, 조건부 헤더, 단일 사용자 UUID, Cookie와
  Authorization 부재, 필수 필드 누락·null 중첩·상태와 오류 코드 불일치를 확인했다.
- 실제 TCP: Apache HttpClient로 GET/POST 각각 연결 종료·읽기 시간 초과를
  발생시켰다. 네 경우 모두 CONTENT_UNAVAILABLE이며 서버 수신 횟수는 1회였다.
  시간 초과 검증은 테스트용 200ms 설정을 사용한다.
- 운영 기본 연결·풀 획득 시간은 2초, 응답·소켓 읽기 시간은 10초다.
  자동 재시도·리다이렉트·쿠키 저장을 비활성화한 전송기를 모든 내부 RestClient에 적용한다.

## 적용 범위

동료가 구현한 Content API 계약과 문서 저장 헤더는 유지한다. 사용하지 않는 범용
byte[] 중계는 제거했다. 응답 유실을 미실행으로 판단하지 않으며 RETRY_LATER는
자동 재시도를 뜻하지 않는다. 인증·세션·CSRF 전환은 LOREKEEPER-555의 후속 범위다.
실제 브라우저·Auth·Redis·Content 통합과 운영 네트워크 검증은 LOREKEEPER-571에서
수행하며 이 기록은 해당 검증을 대신하지 않는다.
