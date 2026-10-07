# BFF 제공 API

> **책임:** BFF가 브라우저에 제공하는 경로·입력·응답·인증과 오류 계약을 정한다.
>
> **제공자·호출자:** BFF가 제공하고 프론트엔드가 호출한다.
>
> **확인할 때:** 프론트가 요청할 API나 브라우저에 반환할 결과를 구현할 때.
>
> **관련 기준:** Auth·Content 호출은 [API_CALLS.md](API_CALLS.md), 쿠키·CSRF·CORS는 [브라우저 보안](BROWSER_SECURITY.md)을 본다.

단일 세션 ID를 사용하는 현재 BFF API 계약이다. 약관 동의도 아래 API 계약에 포함한다.
이 문서는 제공 API의 진입점이며 인증·본인 계정 API를 상세히 정의한다.
Content의 40개 경로·요청·응답은 제공 계약의 세부 문서인 [Content API](CONTENT_API.md)를 따른다.
공개 `GET /health`는 프로세스 상태를 확인한다. 실행 안내는 [프로젝트 README](../README.md)를 따른다.

## 공통 계약

- 브라우저는 HttpOnly 세션 쿠키 하나로 인증하며 쿠키·CSRF·CORS는 [브라우저 보안](BROWSER_SECURITY.md)을 따른다.
- 본인 계정·Content 등 보호 API는 세션 검증과 활동 만료 연장에 성공해야 내부로 전달한다.
- 로그인 준비·콜백·로그아웃·약관 조회와 완료는 일반 세션 필터에서 제외하고 각 흐름의 검증을 적용한다.
- 로그아웃은 본문 없이 호출한다. 세션 ID를 본문·쿼리·사용자 헤더로 받지 않는다.
- 인증·계정 및 세션 쿠키를 갱신하는 응답에는 `Cache-Control: no-store`를 적용한다.
- 오류 본문은 `code`, `message`, `next_action`이다. next_action은 `NONE`, `RELOGIN`, `RESTART_LOGIN`, `RETRY_LATER`다.
- 일반 API 인증 실패는 JSON 오류다. 로그인 시작·콜백만 페이지 이동 응답을 사용한다.

## API 목록

| 제공 API | 브라우저 입력 | 성공 결과 |
|---|---|---|
| `GET /auth/oauth/google/prepare` | 없음 | 임시 쿠키 설정 후 Google로 `302` |
| `GET /auth/oauth/google/callback` | Google callback 쿼리와 OAuth 임시 쿠키 | 세션 쿠키 설정 후 고정 프론트로 `303` |
| `POST /auth/sessions/revoke` | CSRF 헤더와 세션 쿠키, 본문 없음 | 쿠키 삭제 헤더와 폐기 확인 결과 |
| `GET /auth/users/me` | 세션 쿠키 | `200`, 계정 |
| `PATCH /auth/users/me` | CSRF 헤더·세션 쿠키와 `display_name` 본문 | `200`, 수정된 계정 |
| `PUT /auth/users/me/locale` | CSRF 헤더·세션 쿠키와 `locale` 본문 | `200`, 수정된 계정 |
| `PUT /auth/users/me/onboarding` | CSRF 헤더와 세션 쿠키, 본문 없음 | `204`, 본문 없음 |
| `POST /auth/users/me/deletion` | CSRF 헤더·세션 쿠키와 `confirmation_email` 본문 | `204`, 본문 없음. 세션 쿠키 삭제 |

계정 응답은 `id`, `display_name`, `email`, `onboarding_completed`, `locale`, 수정 입력은 `display_name`만 허용한다.
`onboarding_completed`는 boolean이며 Auth 응답에 없거나 boolean이 아니면 502로 처리한다.
`locale`은 `"ko"`, `"en"` 또는 언어를 기록한 적 없는 계정의 `null`이며 null이어도 항상 포함한다.
Auth 응답에 없으면 `null`, 그 밖의 값이면 502로 처리한다. 변경은 [계정 언어](#계정-언어)를 따른다.
내부 호출의 입력 구성과 응답 변환은 [Auth 호출](API_CALLS.md#auth-호출)을 따른다.
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

## 약관 동의 API

로그인 세션 발급 전의 브라우저 계약이다. 두 API는 일반 세션 필터에서 제외하고
동의 대기 쿠키로 Auth 검증을 받는다. 일반 보호 API에 대한 접근 권한은 부여하지 않는다.
완료 POST에는 [Origin·CSRF 검사](BROWSER_SECURITY.md#csrf-검증-계약)를 적용한다.
응답은 `Cache-Control: no-store`이며 동의 대기·로그인 세션 ID를 JSON이나 URL로 반환하지 않는다.

| 제공 API | 브라우저 입력 | 성공 결과 |
|---|---|---|
| `GET /auth/terms` | 동의 대기 쿠키, 선택 쿼리 `locale`, 본문 없음 | `200`, `terms_version_id`, `version`, `title`, `content`, `effective_at`, `expires_at`, `locale` |
| `POST /auth/terms/accept` | 동의 대기 쿠키·CSRF 헤더, JSON의 `terms_version_id` | `204`, 본문 없음. 로그인 쿠키 설정·동의 대기 쿠키 삭제 |

버전 ID는 UUID 문자열이며 POST 본문에는 이 필드만 허용한다. 조회의 `expires_at`은 동의
대기의 만료이고 시각은 UTC ISO 8601이다. 별도의 취소 API는 제공하지 않는다.

조회의 `locale` 쿼리는 `ko`·`en`일 때만 Auth에 전달한다. 생략·빈 값·그 밖의 값은 오류 없이
쿼리를 보내지 않으며 한국어 원문이 반환된다. 응답의 `locale`은 반환된 `title`·`content`의 언어(`"ko"`·`"en"`)다.
`en`을 요청해도 번역이 없으면 한국어 원문과 `"ko"`를 받는다. 동의는 언어와 관계없이 같은
`terms_version_id`로 기록한다. 이 필드를 반환하지 않는 이전 Auth와 연결되면 `locale`은 `null`이며
이때 본문은 한국어 원문이다. 그 밖의 값은 502로 처리한다.
쿠키 처리 순서는 [동의 연동](auth/LOGIN_FLOW.md#약관-동의-연동)을 따른다.

Google 콜백에는 기존 결과에 `result=terms_required`를 추가한다. Auth의 `TERMS_REQUIRED`를
확인하고 대기 쿠키를 설정한 뒤, 위 고정 로그인 주소로 `303` 복귀한다. 이 결과는 로그인
성공을 뜻하지 않는다. 프론트는 약관 조회로 대기를 확인한다.

오류는 기존 `code`, `message`, `next_action` 형식이다.

| HTTP | code | 조건 | next_action |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 완료 본문·버전 ID 형식 오류 | `NONE` |
| 401 | `CONSENT_REQUEST_INVALID` | 쿠키 부재·중복·형식 오류 또는 Auth가 대기 무효 확인 | `RESTART_LOGIN` |
| 403 | `CSRF_REJECTED` | 완료 요청의 Origin·전용 헤더 오류 | `NONE` |
| 409 | `TERMS_VERSION_MISMATCH` | Auth가 대상 버전 불일치 확인 | `NONE` |
| 503 | `LOGIN_UNAVAILABLE` | 알려진 Auth 장애·통신 실패·완료 결과 불명 | `RESTART_LOGIN` |
| 502 | `UPSTREAM_INVALID_RESPONSE` | 내부 응답 형식·ID·만료 검증 실패 | `RESTART_LOGIN` |

오류별 화면 동작은 [프론트 동의 계약](FRONTEND_AUTH_CONTRACT.md#약관-동의)을 따른다.

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

## 온보딩 완료

`PUT /auth/users/me/onboarding`은 본인 계정의 온보딩 완료를 기록한다. 이미 완료된 계정에
다시 호출해도 `204`이며 최초 완료 시각을 유지한다. 완료 상태를 되돌리는 API는 없다.
도움말에서 온보딩을 다시 보는 것은 프론트 화면 동작이며 이 API를 호출하지 않는다.
오류는 아래 [계정 오류](#계정-오류와-검증)를 따른다.

## 계정 언어

`PUT /auth/users/me/locale`은 본인 계정의 언어를 기록한다. 본문은 `{"locale": "ko"}` 또는
`{"locale": "en"}` 하나다. 본문 없음·필드 누락·null·다른 타입·알 수 없는 필드와 `ko`·`en`이 아닌 값
(대문자·지역 표기 포함)은 Auth를 호출하지 않고 `400 INVALID_REQUEST`로 거절한다.
오류 본문은 다른 계정 입력 오류와 같다. 성공하면 `GET /auth/users/me`와 같은 계정 본문을 `200`으로 반환한다.

세션·CSRF·활동 연장은 `PATCH /auth/users/me`와 같다. 같은 값을 다시 보내도 결과는 같다.
화면 언어를 정하고 전환하는 일은 프론트가 담당하며 BFF는 값을 기록·전달만 한다.
오류는 아래 [계정 오류](#계정-오류와-검증)를 따른다.

## 회원 탈퇴

`POST /auth/users/me/deletion`은 확인용 이메일을 받아 본인 계정과 모든 작업 데이터를
즉시 삭제한다. 유예 기간과 복구 API는 없다. 본문은 `{"confirmation_email": "<string>"}`
하나이며 다른 필드·null·다른 타입은 `400 INVALID_REQUEST`다.

1. Auth의 본인 계정을 조회해 입력 이메일과 비교한다. 앞뒤 공백을 제거하고 대소문자를 구분하지 않는다.
   다르거나 계정에 이메일이 없으면 아무것도 삭제하지 않는다.
2. Content에 본인 작업 데이터 전체 삭제를 요청한다. 반복 호출해도 같은 결과다.
3. Auth에 계정 삭제를 요청한다. Auth가 로그인 세션·동의 대기를 폐기한 뒤 계정·Google 연결·동의 기록을 삭제한다.
   계정이 이미 없다는 `404 USER_NOT_FOUND`는 성공으로 처리한다.
4. 성공하면 로그아웃과 같은 세션 쿠키·이전 쿠키 삭제 헤더를 반환하고 활동 연장 쿠키는 발급하지 않는다.

각 단계는 재시도해도 안전하다. Content 삭제 뒤 Auth 삭제가 실패하면 계정은 남고 작업 데이터만
비어 있을 수 있으며, 같은 요청을 다시 보내면 나머지를 완료한다. 실패 응답은 세션 쿠키를
삭제하지 않고 일반 보호 요청처럼 수명을 갱신한다. 삭제 뒤 이전 쿠키로 보낸 요청은
`401 SESSION_INVALID`다.

| HTTP | code | 조건 | next_action |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 본문 형식 오류·알 수 없는 필드·필드 누락 | `NONE` |
| 400 | `ACCOUNT_CONFIRMATION_MISMATCH` | 입력 이메일이 계정 이메일과 다름. 삭제하지 않음 | `NONE` |
| 401 | `USER_CONTEXT_REQUIRED` | Auth가 사용자 전달 오류 반환 | `RELOGIN` |
| 404 | `USER_NOT_FOUND` | 확인 단계에서 계정이 없음 | `RELOGIN` |
| 503 | `ACCOUNT_DELETION_UNAVAILABLE` | Auth·Content 장애·통신 실패·알려진 내부 오류. 재시도 안전 | `RETRY_LATER` |
| 502 | `UPSTREAM_INVALID_RESPONSE` | 내부 응답 형식·계정 ID 검증 실패 | `NONE` |

세션·CSRF 실패는 [보호 API 인증 실패](#보호-api-인증-실패)를 따른다. 응답은 `Cache-Control: no-store`다.

## 계정 오류와 검증

알려진 계정 오류는 Auth 계약의 상태·code·next_action을 유지한다.
`USER_CONTEXT_REQUIRED`는 BFF 전달 결함일 수 있으므로 일반 세션 오류로 바꾸지 않는다.
온보딩 완료·계정 언어도 같은 규칙을 따른다. 탈퇴 오류는 [회원 탈퇴](#회원-탈퇴)의 별도 표를 따른다.
통신 장애는 `503 ACCOUNT_UNAVAILABLE / RETRY_LATER`, 잘못된 내부 응답은
`502 UPSTREAM_INVALID_RESPONSE / NONE`으로 변환한다. 수정 결과 유실을 자동 재시도하지 않는다.

로그인 쿠키와 보호 요청의 활동 연장, 인증 실패의 쿠키 미변경, 로그아웃 실패의 삭제 헤더,
고정 복귀 주소와 민감값 비노출을 검증한다.
