# BFF가 호출하는 API

> **책임:** BFF의 요청이 어떤 서버 호출로 이어지는지, 입력을 어떻게 구성하고 결과를 어떻게 처리하는지 정한다.
>
> **호출자·대상:** BFF가 Auth·Content·AI 챗(ai-chat)을 호출한다. Google OAuth API는 Auth가 호출한다.
>
> **확인할 때:** Auth·Content·AI 챗 클라이언트나 요청 조율·내부 호출 오류 처리를 구현할 때.
>
> **관련 기준:** 브라우저에 제공하는 계약은 [API.md](API.md), Auth의 입력·응답 정의는 [Auth API](../../loresentry-authentication/docs/API.md)를 본다.

Auth 호출은 단일 세션 ID 계약을 사용한다. 실제 호출 구현은
[AuthApiClient](../src/main/java/com/loresentry/gateway/client/auth/AuthApiClient.java)에 있다.

## 공통 호출 규칙

보호 요청은 인증을 통과한 뒤 호출하며 상태 변경 요청은 CSRF도 검사한다. 검증된 사용자 UUID만 `X-User-Id`로
설정하며 브라우저가 보낸 사용자 헤더·Cookie·Authorization을 그대로 전달하지 않는다.
로그인·폐기처럼 보호 요청 인증을 거치지 않는 경로는 아래 입력 구성과 [인증 흐름](auth/LOGIN_FLOW.md)을 따른다.

내부 HTTP 연결·풀 획득 제한은 2초, 읽기 제한은 10초다. 자동 재시도·리다이렉트·쿠키 저장을
사용하지 않는다. AI 챗 답 스트림만 [전용 클라이언트](#답-스트림-중계)를 쓴다. 운영 주소는 서비스 설정에서 가져오며 브라우저 입력으로 호출 호스트를 정하지 않는다.

client는 내부 DTO를 검증·변환하고 application은 후속 처리를 결정한다. web은 제공 API의
상태·외부 DTO·쿠키·리다이렉트를 구성한다. 내부 응답·예외 원문을 그대로 반환하지 않는다.
통신 실패, 알려진 업무 오류, 잘못된 응답을 구분하고 [제공 API](API.md)와
[Content 오류 계약](CONTENT_API.md#오류-변환)에 맞게 변환한다.

## Auth 호출

| BFF가 처리하는 요청 | Auth 호출 | 입력 구성 | 응답 사용 |
|---|---|---|---|
| `GET /auth/oauth/google/prepare` | `POST /auth/oauth/google/prepare` | 빈 JSON 객체 | 인가 URL로 리다이렉트하고 요청 식별자·만료로 임시 쿠키 설정 |
| `GET /auth/oauth/google/callback` | `POST /auth/oauth/google/callback` | 쿼리의 code·state 또는 error와 임시 쿠키의 요청 식별자 | 세션 ID·만료로 세션 쿠키 설정, 소비 결과로 임시 쿠키 정리 |
| `POST /auth/sessions/revoke` | `POST /auth/sessions/revoke` | CSRF 통과 후 쿠키의 ID를 `session_id` 본문으로 구성. 쿠키가 없으면 호출 생략 | 폐기 결과와 브라우저 쿠키 삭제를 구분하여 반환 |
| `GET /auth/users/me` | `GET /auth/users/me` | 인증된 사용자 UUID를 `X-User-Id`로 구성 | 본인 계정 외부 DTO로 변환 |
| `PATCH /auth/users/me` | `PATCH /auth/users/me` | 인증된 사용자 UUID와 검증한 `display_name` | 수정된 본인 계정 외부 DTO로 변환 |
| `PUT /auth/users/me/locale` | `PUT /auth/users/me/locale` | 인증된 사용자 UUID와 `ko`·`en`으로 검증한 `{"locale"}` 본문. 검증 실패 시 호출 생략 | 수정된 본인 계정 외부 DTO로 변환 |
| `PUT /auth/users/me/onboarding` | `PUT /auth/users/me/onboarding` | 인증된 사용자 UUID, 본문 없음 | `204`만 성공으로 처리 |
| `PUT /auth/users/me/acquisition` | `PUT /auth/users/me/acquisition` | 인증된 사용자 UUID와 받은 유입 경로 필드 그대로 | `204`만 성공으로 처리 |
| `POST /auth/users/me/deletion` | `GET /auth/users/me` → AI 챗 `DELETE /users/me/data` → `DELETE /auth/users/me` | 인증된 사용자 UUID만 `X-User-Id`로 구성, 본문 없음 | 아래 탈퇴 호출 순서 참고 |

Auth가 받는 필수 필드·반환 필드·오류 코드의 기준은 [Auth API](../../loresentry-authentication/docs/API.md)다.
콜백의 `login_request_consumed`는 오류 전달 중에도 보존하고 확인할 수 없는 값을 false로 바꾸지 않는다.
응답 유실을 명령 미실행이나 폐기 성공으로 단정하지 않는다. 재요청·쿠키 처리의 기준은
[로그인](auth/LOGIN_FLOW.md)과 [로그아웃](auth/LOGOUT_FLOW.md)을 따른다.

계정 응답의 `onboarding_completed`는 필수 boolean이다. `locale`은 선택이며 없으면 `null`로 변환하고,
`ko`·`en`·null이 아닌 값은 잘못된 응답이다. 계정 조회·수정 응답의 `id`가 요청
사용자와 다르면 잘못된 응답으로 처리한다.

### 회원 탈퇴 호출

하나의 브라우저 요청을 다음 순서로 처리하며 앞 단계가 실패하면 뒤 단계를 호출하지 않는다.

| 순서 | 호출 | 성공 | 실패 처리 |
|---|---|---|---|
| 1 | Auth `GET /auth/users/me` | `200` 본인 계정. 이메일 비교 | 알려진 401·404는 유지, 장애·기타 알려진 오류는 `503 ACCOUNT_DELETION_UNAVAILABLE`, 잘못된 응답은 502 |
| 2 | AI 챗 `DELETE /users/me/data` | `204` | 잘못된 응답은 502, 나머지는 `503 ACCOUNT_DELETION_UNAVAILABLE` |
| 3 | Auth `DELETE /auth/users/me` | `204` 또는 `404 USER_NOT_FOUND` | 알려진 401은 유지, 장애·기타 알려진 오류는 503, 잘못된 응답은 502 |

이메일이 일치하지 않으면 2단계부터 호출하지 않는다. AI 챗 삭제는 반복해도 같은 결과이고 Auth
삭제는 이미 없는 계정을 404로 알려 주므로, 결과를 모르는 실패 뒤에도 브라우저 재요청으로
완료할 수 있다. BFF는 자동 재시도하지 않는다. Auth가 세션을 폐기한 뒤 응답이 유실되면
재요청은 세션 필터에서 `401 SESSION_INVALID`가 된다.

탈퇴 중에 BFF는 Content를 호출하지 않는다. Auth가 계정을 삭제한 뒤 발행하는 `auth.user.deleted.v1`을
Content가 받아 작업 데이터를 삭제한다.

### 약관 동의 호출

Auth 내부 필드·오류의 기준은 [Auth 동의 API](../../loresentry-authentication/docs/API.md#약관-조회와-동의-완료)다.
BFF가 브라우저 입력에서 내부 요청을 구성하는 규칙은 다음과 같다.

| BFF 요청 | Auth 호출 | 입력 구성 | 응답 사용 |
|---|---|---|---|
| `GET /auth/terms` | 같은 GET 경로 | 동의 쿠키 값을 `X-Consent-Request-Id` 헤더로 구성. 브라우저 `locale`이 `ko`·`en`일 때만 같은 쿼리로 전달 | 정의된 약관 조회 필드만 외부 DTO로 반환. `locale`은 없으면 `null`, `ko`·`en` 외의 값은 잘못된 응답 |
| `POST /auth/terms/accept` | 같은 POST 경로 | CSRF·입력 검증 후 쿠키의 `consent_request_id`와 본문의 `terms_version_id`로 내부 JSON 구성 | 세션 ID·만료 검증 후 로그인 완료 처리, 브라우저에는 `204` |

브라우저가 보낸 내부 헤더나 사용자 ID는 사용하지 않는다. 쿠키 부재·중복·형식 오류는
Auth 호출 전에 거절한다. 동의 대기 Redis를 직접 조회하지 않는다.
콜백은 새 `status`로 로그인 완료와 동의 대기를 구분하고 각 분기의 필수 필드를 검증한다.
결과는 [BFF 동의 흐름](auth/LOGIN_FLOW.md#약관-동의-연동)에 전달한다.

알려진 Auth 오류는 [브라우저 동의 API](API.md#약관-동의-api)의 매핑을 따른다.
공통 통신 제한·자동 재시도 금지 규칙을 유지하며, 응답 유실을 미실행으로 간주하지 않는다.

## Content 호출

[제공 Content API 목록](CONTENT_API.md#api-목록)의 45개 경로는 **각각 같은 메서드·경로의
Content API를 호출한다.** 각 경로의 요청을 외부 DTO에서 내부 DTO로 변환하고 인증된
사용자 UUID를 전달한다. namespace 하위의 임의 경로를 중계하지 않는다.
실제 호출 목록은 [ContentApiClient](../src/main/java/com/loresentry/gateway/client/content/ContentApiClient.java)에 있다.
`DELETE /projects/{id}`가 `204`로 끝나면 이어서 AI 챗 데이터를 [정리](#ai-챗-호출)한다.
Content의 제공 명세는 Content 서버가 소유하며, BFF의 [Content API](CONTENT_API.md)는 브라우저에 제공하는 계약이다.

| 항목 | BFF가 구성·처리하는 내용 |
|---|---|
| 신원 | 인증된 `X-User-Id`만 설정 |
| 요청 본문 | 정의된 내부 DTO로 변환하고 JSON으로 전송 |
| 저장 헤더 | `If-Match`·`X-Save-Id`를 값 변경 없이 전달. BFF가 멱등 키를 생성하거나 요청을 자동 재전송하지 않음 |
| 조건 조회 | GET의 `If-None-Match`를 선택적으로 전달. BFF가 별도 304·ETag 기능을 만들지 않음 |
| 검색 | q의 한글·공백·예약 문자 의미를 보존해 한 번만 인코딩 |
| 샘플 언어 | `POST /projects/sample`의 `locale`이 `ko`·`en`일 때만 같은 쿼리로 전달. 생략·그 밖의 값은 쿼리 없이 호출 |
| 성공 | 내부 DTO의 필수 필드·상태·타입을 검사해 외부 DTO로 변환. nullable 필드 유지 |
| 생성 Location | 반환된 프로젝트 ID로 상대 `/projects/{id}` 구성. 내부 주소를 복사하지 않음 |
| 피드백 | `POST /feedback`의 category·message·page·client만 내부 DTO로 전달. 길이·종류 판단과 시간당 횟수 제한은 Content가 수행 |
| 충돌 | `DOCUMENT_CONFLICT`의 current·base를 외부 DTO로 변환. 추가 GET으로 재구성하지 않음 |
| 최신화 | `POST /projects/{id}/graph-refresh`는 본문 없이 호출하고 `200`·`202`를 모두 성공으로 받음. 최신화 오류의 current·drafts·documents·problems를 code에 맞춰 검증해 외부 DTO로 변환 |
| 실패 | 제공 Content API의 오류 변환 규칙 적용. 응답이 유실된 변경 요청을 미실행으로 단정하지 않음 |

## AI 챗 호출

브라우저의 [AI 챗 경로](API.md#ai-챗-api) 10개는 **각각 같은 메서드·경로의 ai-chat API를 호출한다.**
실제 호출 목록은 [ChatApiClient](../src/main/java/com/loresentry/gateway/client/chat/ChatApiClient.java)에 있고,
계약의 기준은 [AI 챗 요구사항 §4](../../docs/AI_CHAT_REQUIREMENTS.md#4-api-계약)다.

| 항목 | BFF가 구성·처리하는 내용 |
|---|---|
| 허용 목록 | `loresentry.chat.allowed-users`에 없는 사용자는 Auth·ai-chat을 호출하지 않고 `403 CHAT_NOT_ENABLED`(`NONE`) |
| 신원 | 인증된 `X-User-Id`만 설정 |
| 프로필 | 세션 만들기·질문 보내기에서만 Auth `GET /auth/users/me`를 읽어 `X-User-Locale`(`ko`·`en`일 때만)과 `X-User-Display-Name`(UTF-8 퍼센트 인코딩)을 붙인다. Auth가 실패하면 경고만 남기고 두 헤더 없이 호출 |
| 요청 본문 | 정의된 DTO의 타입만 검사해 그대로 전달. 질문·이름의 규칙은 ai-chat이 판단 |
| 성공 | 필수 필드와 `status`·`role`·`intent`·근거 `kind` 값, 사용량의 음수 여부를 검사해 외부 DTO로 변환 |
| 오류 | 아래 code·상태 조합만 통과시키고 message는 BFF 고정 문구. 그 밖의 조합·JSON 아님은 `502 UPSTREAM_INVALID_RESPONSE`, 연결 실패·시간 초과는 `503 AI_CHAT_UNAVAILABLE`(`RETRY_LATER`) |
| 정리 | 탈퇴 2단계의 `DELETE /users/me/data`. 프로젝트 영구 삭제 뒤의 `DELETE /projects/{id}/chat/data`는 실패해도 경고만 남기고 `204` |

| HTTP | code | next_action |
|---|---|---|
| 400 | INVALID_REQUEST, CHAT_TITLE_REQUIRED, CHAT_QUESTION_REQUIRED | NONE |
| 404 | CHAT_SESSION_NOT_FOUND, PROJECT_NOT_FOUND | NONE |
| 409 | CHAT_NOT_LAST_QUESTION, CHAT_ANSWER_IN_PROGRESS, CHAT_ANOTHER_ANSWER_IN_PROGRESS | NONE |
| 422 | CHAT_CONTEXT_TOO_LARGE | NONE |
| 429 | CHAT_DAILY_LIMIT_REACHED | NONE |
| 503 | CHAT_MODEL_UNAVAILABLE, CONTENT_UNAVAILABLE | RETRY_LATER |

### 답 스트림 중계

`POST /chat/sessions/{id}/messages`는 ai-chat의 상태 코드를 먼저 본다. `200 text/event-stream`이 아니면
위 규칙의 JSON 오류로 답하고, 200이면 받은 바이트를 해석하지 않고 `StreamingResponseBody`로 옮기며
읽을 때마다 내보낸다. Spring 7이 넘겨주는 출력 스트림은 flush를 무시하므로 서블릿 응답의 `flushBuffer()`를 쓴다.

| 항목 | 값 |
|---|---|
| 클라이언트 | 일반 클라이언트와 분리한 Apache HttpClient(`ChatConfiguration.streamHttpClient`) |
| 연결·풀 획득 / 읽기 | 2초 / 45초(ai-chat ping 15초의 세 배) |
| 연결 풀 | 서버당·전체 200 |
| 연결 재사용·압축·재시도·리다이렉트 | 사용하지 않음. 재시도할 수 없는 POST가 서버가 닫은 연결을 잡지 않게 매번 새로 연결 |
| 비동기 요청 제한 | `spring.mvc.async.request-timeout: 180s` |

- 브라우저가 끊기면 쓰기 실패를 보고 ai-chat 연결을 바로 닫는다. ai-chat은 생성을 끝까지 하고 저장한다.
- ai-chat 연결이 중간에 끊기거나 45초 동안 아무것도 오지 않으면 브라우저 응답을 그냥 끝낸다. 오류 이벤트를
  만들어 넣지 않으며, 프론트는 `done` 없이 끝난 스트림을 메시지 목록(`generating`)으로 복구한다.
- 세션 쿠키 갱신은 첫 쓰기와 세션 필터 종료 중 먼저 오는 쪽에서 한 번만 붙는다.

## HTTP 호출이 아닌 연동

세션 검증·활동 연장은 Auth HTTP API 호출이 아니라 공유 Redis 저장소 연산이다.
[세션 활동 흐름](auth/SESSION_FLOW.md)과 [공유 세션 계약](../../loresentry-authentication/docs/session/SESSION_DESIGN.md)을 따른다.
`GET /health`는 BFF 자체 응답이다. 기존 연결 확인용 진단 경로는 기능 API의 호출 계약에 포함하지 않는다.
