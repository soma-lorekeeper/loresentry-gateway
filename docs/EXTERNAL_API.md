# BFF 인증·본인 계정 외부 API

2026-09-26 단일 세션 ID API를 코드에 반영했다. 브라우저·운영 검증은 후속 작업이다.
Content의 경로·도메인 응답은 [별도 계약](CONTENT_API.md)을 따른다.

## 공통 계약

- 브라우저는 HttpOnly 세션 쿠키 하나로 인증하며 쿠키·CSRF·CORS는 [브라우저 보안](BROWSER_SECURITY.md)을 따른다.
- 본인 계정·Content 등 보호 API는 세션 검증과 활동 만료 연장에 성공해야 내부로 전달한다.
- 로그인 준비·콜백·로그아웃은 일반 세션 필터에서 제외하고 각 흐름의 검증을 적용한다.
- 로그아웃은 본문 없이 호출한다. 세션 ID를 본문·쿼리·사용자 헤더로 받지 않는다.
- 인증·계정 및 세션 쿠키를 갱신하는 응답에는 `Cache-Control: no-store`를 적용한다.
- 오류 본문은 `code`, `message`, `next_action`이다. next_action은 `NONE`, `RELOGIN`, `RESTART_LOGIN`, `RETRY_LATER`다.
- 일반 API 인증 실패는 JSON 오류다. 로그인 시작·콜백만 페이지 이동 응답을 사용한다.

## API 목록

| 외부 API | Auth 내부 API | BFF 성공 처리 |
|---|---|---|
| `GET /auth/oauth/google/prepare` | `POST /auth/oauth/google/prepare` | 임시 쿠키 설정 후 Google로 `302` |
| `GET /auth/oauth/google/callback` | `POST /auth/oauth/google/callback` | 세션 쿠키 설정 후 고정 프론트로 `303` |
| `POST /auth/sessions/revoke` | `POST /auth/sessions/revoke` | CSRF 검사, ID 폐기 요청, 쿠키 삭제와 결과 본문 |
| `GET /auth/users/me` | `GET /auth/users/me` | 세션 확인·연장 후 `200`, 계정 |
| `PATCH /auth/users/me` | `PATCH /auth/users/me` | CSRF·세션 확인·연장 후 `200`, 수정된 계정 |

계정 응답은 `id`, `display_name`, `email`, 수정 입력은 `display_name`만 허용한다.
[Auth 내부 API](../../loresentry-authentication/docs/INTERNAL_API.md)를 따른다.
세션 활동 연장을 위한 별도 브라우저 엔드포인트는 없다.

## 로그인 후 고정 주소로 복귀

| 환경 | 고정 결과 주소 |
|---|---|
| local | `http://localhost:3000/login` |
| prod | `https://loresentry.com/login` |

허용 프론트 Origin과 결과 주소가 일치하는지 시작 시 확인한다. 요청의 returnUrl·Host·전달
헤더로 목적지를 정하지 않는다. 콜백은 다음 result 하나만 붙여 `303`으로 이동시킨다.

| result | 조건 |
|---|---|
| `success` | Auth의 세션 생성 성공과 내부 ID·만료 검사를 마치고 쿠키 설정 |
| `cancelled` | `OAUTH_LOGIN_DENIED` |
| `invalid` | 임시 쿠키·콜백 입력 또는 `OAUTH_REQUEST_INVALID` |
| `unavailable` | 알려진 통신 장애·`LOGIN_UNAVAILABLE` |
| `failed` | 신원 검증 실패·잘못된 내부 응답 등 나머지 실패 |

세션 ID·OAuth code·state·임시 식별자·제공자 오류 설명은 복귀 URL에 넣지 않는다.
`Referrer-Policy: no-referrer`를 적용한다. 실패 시 기존 세션 쿠키는 변경하지 않는다.
임시 쿠키는 [소비 결과](auth/LOGIN_FLOW.md#oauth-임시-쿠키)에 따라 정리한다.
`result=success`는 안내 값이며 프론트는 `GET /auth/users/me`로 실제 로그인을 확인한다.

## 보호 API 인증 실패

| HTTP | code | 조건 | next_action |
|---|---|---|---|
| 401 | `SESSION_REQUIRED` | 세션 쿠키 없음 | `RELOGIN` |
| 401 | `SESSION_INVALID` | 중복·형식 오류 또는 부재·만료·현재 세션 불일치 | `RELOGIN` |
| 503 | `SESSION_UNAVAILABLE` | 저장소·ACL·500ms 제한·손상·연장 결과 불명 | `RETRY_LATER` |
| 403 | `CSRF_REJECTED` | Origin·전용 헤더 오류 | `NONE` |

실패한 보호 요청은 내부로 전달하지 않고 쿠키도 변경하지 않는다. 일시 장애를 로그아웃으로
단정하지 않는다. 일반 API의 401·통신 실패를 자동 재전송하지 않는다.
현재 세션을 다시 얻으려면 Google 로그인 흐름을 시작한다.

세션 검증과 연장이 성공하면 내부 도메인 처리의 성공 여부와 별개로 같은 ID의 쿠키 수명을
갱신한다. 단, 공개·로그아웃 경로에는 이 동작을 적용하지 않는다. 상세 시각과 늦은 응답의
한계는 [쿠키 계약](BROWSER_SECURITY.md#세션-쿠키)을 따른다.

## 로그아웃 응답

CSRF를 통과하면 세션 쿠키 삭제 헤더를 발급하고 폐기 확인 결과를 별도로 반환한다.
`session_revocation`은 요청 ID에 대한 결과이며 다른 로그인 상태를 뜻하지 않는다.

| HTTP | 응답 | 의미 |
|---|---|---|
| 200 | `session_revocation=confirmed` | Auth가 해당 세션 폐기 또는 사용 불가 상태 확인 |
| 200 | `session_revocation=not_requested` | 쿠키가 없어 Auth 호출 생략 |
| 400 | `INVALID_SESSION_ID`, `NONE`, `session_revocation=rejected` | 중복 쿠키·ID 형식 오류 |
| 503 | `REVOCATION_UNCONFIRMED`, `NONE`, `session_revocation=unconfirmed` | Auth 오류·통신 실패 등 폐기 미확인 |

오류 응답은 공통 오류 필드와 session_revocation을 함께 포함한다. CSRF 실패는 위 흐름에
진입하지 않고 쿠키도 지우지 않는다. 삭제 헤더가 브라우저에 적용됐다는 보증 필드는 두지 않는다.
서버 폐기 미확인을 완전한 로그아웃 성공으로 표시하지 않는다.

## 계정 오류와 검증

알려진 계정 오류는 Auth 계약의 상태·code·next_action을 유지한다.
`USER_CONTEXT_REQUIRED`는 BFF 전달 결함일 수 있으므로 일반 세션 오류로 바꾸지 않는다.
통신 장애는 `503 ACCOUNT_UNAVAILABLE / RETRY_LATER`, 잘못된 내부 응답은
`502 UPSTREAM_INVALID_RESPONSE / NONE`으로 변환한다. 수정 결과 유실을 자동 재시도하지 않는다.

로그인 쿠키와 보호 요청의 활동 연장, 인증 실패의 쿠키 미변경, 로그아웃 실패의 삭제 헤더,
고정 복귀 주소와 민감값 비노출을 새 계약으로 검증해야 한다.
