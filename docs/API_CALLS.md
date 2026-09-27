# BFF가 호출하는 API

> **책임:** BFF의 요청이 어떤 서버 호출로 이어지는지, 입력을 어떻게 구성하고 결과를 어떻게 처리하는지 정한다.
>
> **호출자·대상:** BFF가 Auth·Content를 호출한다. Google OAuth API는 Auth가 호출한다.
>
> **확인할 때:** Auth·Content 클라이언트나 요청 조율·내부 호출 오류 처리를 구현할 때.
>
> **관련 기준:** 브라우저에 제공하는 계약은 [API.md](API.md), Auth의 입력·응답 정의는 [Auth API](../../loresentry-authentication/docs/API.md)를 본다.

Auth 호출은 단일 세션 ID 방식의 목표 계약이다. 현재 JWT 구현의 refresh·revoke는
[프로젝트 README](../README.md#api)와 [AuthApiClient](../src/main/java/com/loresentry/gateway/client/auth/AuthApiClient.java)를 따른다.
Content 호출 경로는 현재 구현을 유지하며 인증 선행 조건만 새 세션 방식으로 전환한다.

## 공통 호출 규칙

보호 요청은 인증을 통과한 뒤 호출하며 상태 변경 요청은 CSRF도 검사한다. 검증된 사용자 UUID만 `X-User-Id`로
설정하며 브라우저가 보낸 사용자 헤더·Cookie·Authorization을 그대로 전달하지 않는다.
로그인·폐기처럼 보호 요청 인증을 거치지 않는 경로는 아래 입력 구성과 [인증 흐름](auth/LOGIN_FLOW.md)을 따른다.

내부 HTTP 연결·풀 획득 제한은 2초, 읽기 제한은 10초다. 자동 재시도·리다이렉트·쿠키 저장을
사용하지 않는다. 운영 주소는 서비스 설정에서 가져오며 브라우저 입력으로 호출 호스트를 정하지 않는다.

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

Auth가 받는 필수 필드·반환 필드·오류 코드의 기준은 [Auth API](../../loresentry-authentication/docs/API.md)다.
콜백의 `login_request_consumed`는 오류 전달 중에도 보존하고 확인할 수 없는 값을 false로 바꾸지 않는다.
응답 유실을 명령 미실행이나 폐기 성공으로 단정하지 않는다. 재요청·쿠키 처리의 기준은
[로그인](auth/LOGIN_FLOW.md)과 [로그아웃](auth/LOGOUT_FLOW.md)을 따른다.

약관 동의에 따른 가입 대기·완료 호출은 아직 API 경로·응답이 확정되지 않았다.
[Auth 동의 설계](../../loresentry-authentication/docs/account/TERMS_CONSENT_DESIGN.md)에 맞춰 확정 후 이 목록에 추가한다.

## Content 호출

[제공 Content API 목록](CONTENT_API.md#api-목록)의 38개 경로는 **각각 같은 메서드·경로의
Content API를 호출한다.** 각 경로의 요청을 외부 DTO에서 내부 DTO로 변환하고 인증된
사용자 UUID를 전달한다. namespace 하위의 임의 경로를 중계하지 않는다.
실제 호출 목록은 [ContentApiClient](../src/main/java/com/loresentry/gateway/client/content/ContentApiClient.java)에 있다.
Content의 제공 명세는 Content 서버가 소유하며, BFF의 [Content API](CONTENT_API.md)는 브라우저에 제공하는 계약이다.

| 항목 | BFF가 구성·처리하는 내용 |
|---|---|
| 신원 | 인증된 `X-User-Id`만 설정 |
| 요청 본문 | 정의된 내부 DTO로 변환하고 JSON으로 전송 |
| 저장 헤더 | `If-Match`·`X-Save-Id`를 값 변경 없이 전달. BFF가 멱등 키를 생성하거나 요청을 자동 재전송하지 않음 |
| 조건 조회 | GET의 `If-None-Match`를 선택적으로 전달. BFF가 별도 304·ETag 기능을 만들지 않음 |
| 검색 | q의 한글·공백·예약 문자 의미를 보존해 한 번만 인코딩 |
| 성공 | 내부 DTO의 필수 필드·상태·타입을 검사해 외부 DTO로 변환. nullable 필드 유지 |
| 생성 Location | 반환된 프로젝트 ID로 상대 `/projects/{id}` 구성. 내부 주소를 복사하지 않음 |
| 충돌 | `DOCUMENT_CONFLICT`의 current·base를 외부 DTO로 변환. 추가 GET으로 재구성하지 않음 |
| 실패 | 제공 Content API의 오류 변환 규칙 적용. 응답이 유실된 변경 요청을 미실행으로 단정하지 않음 |

## HTTP 호출이 아닌 연동

세션 검증·활동 연장은 Auth HTTP API 호출이 아니라 공유 Redis 저장소 연산이다.
[세션 활동 흐름](auth/SESSION_FLOW.md)과 [공유 세션 계약](../../loresentry-authentication/docs/session/SESSION_DESIGN.md)을 따른다.
`GET /health`는 BFF 자체 응답이다. 기존 연결 확인용 진단 경로는 기능 API의 호출 계약에 포함하지 않는다.
