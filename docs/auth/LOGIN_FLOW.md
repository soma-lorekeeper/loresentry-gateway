# BFF 로그인 연동

외부 로그인 시작과 Google callback은 BFF가 받는다.

1. **로그인 시작:** `GET /auth/oauth/google/prepare` 요청을 받으면 같은 경로의 Auth POST API에 로그인 준비를 요청한다.
   응답에 따라 브라우저 연결용 임시 쿠키를 설정하고 Google 로그인 URL로 리다이렉트한다.
2. **콜백 전달:** Google에 등록할 콜백 경로는 BFF의 `/auth/oauth/google/callback`로 한다.
   브라우저가 돌아오면 `code`·`state` 또는 로그인 오류와 임시 쿠키의 값을 Auth에 전달한다.
   BFF는 GET으로 받고 Auth의 같은 경로에는 POST로 전달한다.
3. **로그인 완료:** Auth가 반환한 AT·RT를 [인증 쿠키](../BROWSER_SECURITY.md#atrt-쿠키-발급과-수명)로
   설정하고, 성공·실패 모두 [고정 프론트 주소](../EXTERNAL_API.md#로그인-후-고정-주소로-복귀)로 복귀시킨다.

BFF는 서버에 사용자별 임시 상태를 저장하지 않는다. 검증은 [Auth 로그인 흐름](../../../loresentry-authentication/docs/login/LOGIN_FLOW.md),
콜백 URL은 [Google 요청 설정](../../../loresentry-authentication/docs/login/OAUTH_STATE.md#google-요청-설정)을 따른다.

## OAuth 임시 쿠키

| 항목 | 설정 |
|---|---|
| 이름 | HTTPS 환경은 `__Host-ls_oauth` |
| 값 | Auth가 반환한 `login_request_id` |
| 속성 | `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, Domain 생략 |
| 수명 | Auth의 `expires_at`까지 남은 시간, 최대 300초 |
| 로컬 HTTP 예외 | 로컬 프로필의 localhost·루프백에서만 `ls_oauth`, `Secure=false` |

Google의 최상위 GET 콜백에서 쿠키를 전달할 수 있도록 `SameSite=Lax`를 사용한다.
환경별 예외는 명시적인 프로필 설정으로 제한하고, 전달받은 임의 헤더로 Secure 여부를 결정하지 않는다.
새 로그인 시작은 기존 임시 쿠키를 교체한다. 동일 브라우저 프로필의 동시 로그인 흐름은 지원하지 않으며,
시도가 겹쳐 실패하면 로그인을 다시 시작한다. 다른 기기·브라우저의 로그인은 독립적이다.

콜백 응답의 `login_request_consumed=true`이면 발급 시와 같은 범위로 `Max-Age=0`을 설정한다.
미검증 콜백이나 통신 실패로 소비 여부가 확인되지 않으면 쿠키를 지우지 않고 최대 5분의 만료에 맡긴다.
이렇게 하여 잘못된 `state`를 가진 콜백이 현재 진행 중인 로그인 쿠키를 지우지 않게 한다.

## 구현 시 검증

성공·취소·실패 시 쿠키 처리와 고정 주소 복귀를 확인한다.
복귀 후 로그인 확인·화면 이동은 [프론트 연동 메모](../../../docs/frontend/LOGIN_RETURN.md)를 따른다.
