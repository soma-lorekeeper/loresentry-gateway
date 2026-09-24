# 프론트 인증 연동 인계

BFF의 로그인 결과 URL은 상태 안내다. `/login?result=success`만 보고 로그인 완료로
처리하지 않고 `GET /auth/users/me`를 `credentials: "include"`로 호출해 확인한다.
사용자가 쿼리를 바꿀 수 있으며, 직후 다른 로그인으로 현재 세션이 바뀔 수도 있다.

## 브라우저 요청

- local은 `http://localhost:3000`에서 `http://localhost:8000`을 호출한다. prod는
  `https://loresentry.com`에서 `https://api.loresentry.com`을 호출한다.
- API 호출에 `credentials: "include"`를 적용한다. `X-User-Id`와 localStorage의
  개발 사용자 ID는 인증 수단으로 사용하지 않는다.
- POST·PUT·PATCH·DELETE에는 `X-LS-CSRF: 1`을 추가한다. 폼 제출·본문·쿼리 값으로
  대신할 수 없다. 재발급과 로그아웃도 같은 조건이다.
- 로그인 시작은 BFF의 `GET /auth/oauth/google/prepare`로 페이지 이동한다.
  결과는 고정 `/login?result=success|cancelled|invalid|unavailable|failed`로 돌아온다.
  BFF는 원래 화면 주소를 보관하지 않으며 임의 returnUrl로 이동하지 않는다.
- 토큰은 HttpOnly 쿠키로만 전달된다. 응답 본문이나 URL에서 AT·RT를 읽지 않는다.

## 오류와 후속 처리

`ACCESS_TOKEN_MISSING`·`ACCESS_TOKEN_EXPIRED`의 REFRESH만 명시적 재발급 대상으로 삼는다.
`SESSION_INVALID`는 재로그인, `SESSION_UNAVAILABLE`는 일시 장애로 처리하며 재발급을
반복하지 않는다. 늦게 온 실패가 더 최근의 성공 상태를 덮지 않도록 요청·탭 간 순서를 조율한다.
내부 서비스 오류나 로그아웃의 401을 재발급 조건으로 사용하지 않는다.

동시에 거절된 요청들은 하나의 재발급 결과를 기다리고, 브라우저 프로필의 여러 탭도
갱신을 조율한다. 성공 후 원래 요청은 한 번만 재시도하고 재발급 API 자체를 자동 재시도하지
않는다. 네트워크 오류나 모든 401을 재발급 대상으로 일반화하지 않는다. 늦은 실패로
사용자 상태를 초기화하거나 성공 이후의 화면 상태를 덮지 않도록 요청 세대를 관리한다.

Content 저장은 If-Match와 X-Save-Id를 유지하고 409의 current/base로 충돌을 처리한다.
버전 복원에도 If-Match를 보내며, Location은 CORS 노출 헤더로 읽을 수 있다.
한글 검색어는 URLSearchParams 등으로 한 번 인코딩한다. 상세 필드는
[Content API](CONTENT_API.md)를 따른다.

쿠키·오류·메서드의 상세 계약은 [외부 API](EXTERNAL_API.md)와
[재발급 흐름](auth/REFRESH_FLOW.md)을 따른다. 이 문서는 프론트 구현 인계이며
현재 프론트 코드의 credentials·CSRF·로그인 결과 화면·탭 조율 구현 완료를 뜻하지 않는다.
이번 작업의 실제 브라우저 검증은 사용자 요청으로 제외했으며
[검증 대기 시나리오](verification/LOREKEEPER-574.md)는 프론트 구현 후 다시 수행한다.
