# BFF 인증·본인 계정 외부 API

구현된 BFF 인증·본인 계정 API 계약이다. 실제 Auth와의 연동은 검증했으며,
프론트·브라우저와 운영 적용 상태는 [검증 기록](verification/LOREKEEPER-574.md)과
[운영 준비](OPERATIONS.md)를 따른다. Content는 [별도 API 계약](CONTENT_API.md)에 정의한다.

## 공통 계약

- 쿠키·CSRF·CORS는 [브라우저 보안](BROWSER_SECURITY.md)을 따른다.
- 로그인·콜백·재발급·로그아웃은 유효한 AT를 필수로 요구하지 않는다.
  본인 계정·Content API에는 AT 검증 후 공유 저장소의 활성 세션 검사를 적용한다.
- 요청 본문이 있는 계정 수정은 JSON을 사용한다. 재발급·로그아웃은 본문 없이 호출하며
  브라우저 쿠키에서 RT를 읽는다. 이 API들에서 본문·쿼리로 토큰을 받지 않는다.
- 인증·본인 계정 응답에는 `Cache-Control: no-store`를 적용한다.
- 일반 오류 본문은 `code`, `message`, `next_action`이다. 프론트는 message를 파싱하지 않는다.
  `next_action`은 `NONE`, `REFRESH`, `RELOGIN`, `RESTART_LOGIN`, `RETRY_LATER`를 사용한다.
- 인증 실패를 로그인 페이지로 자동 리다이렉트하지 않는다. 페이지 이동인 로그인 시작·콜백만
  리다이렉트하며, 일반 API는 상태 코드와 JSON 오류로 응답한다.

## API 목록

**외부 경로는 대응하는 Auth 내부 API의 전체 경로와 통일한다.**
같은 경로라도 브라우저는 BFF 호스트를, BFF는 내부 Auth 호스트를 호출한다.
로그인 시작·콜백은 브라우저 페이지 이동을 받으므로 외부 GET을 내부 POST로 변환한다.
쿠키·JSON·리다이렉트 변환과 각 서버의 책임은 유지한다.

| 외부 API | BFF가 호출하는 Auth API | BFF 처리와 외부 성공 응답 |
|---|---|---|
| `GET /auth/oauth/google/prepare` | `POST /auth/oauth/google/prepare` | OAuth 임시 쿠키 설정, `302`로 Google 인가 URL 이동 |
| `GET /auth/oauth/google/callback` | `POST /auth/oauth/google/callback` | 콜백 정보 전달, AT·RT 설정 후 `303`으로 고정 프론트 주소 이동 |
| `POST /auth/tokens/refresh` | `POST /auth/tokens/refresh` | CSRF 검증 후 RT 전달, 새 AT·RT 설정, `204`, 본문 없음 |
| `POST /auth/tokens/revoke` | `POST /auth/tokens/revoke` | CSRF 검증 후 RT 폐기 요청, AT·RT 삭제, `200`, 폐기 확인 결과 |
| `GET /auth/users/me` | `GET /auth/users/me` | AT 검증 후 본인 계정 조회, `200`, `id`, `display_name`, `email` |
| `PATCH /auth/users/me` | `PATCH /auth/users/me` | CSRF·AT 검증 후 표시 이름 수정, `200`, 수정된 본인 계정 정보 |

계정 수정 요청은 `display_name`만 허용한다. 도메인 검증 규칙과 내부 요청·응답은
[Auth 내부 API](../../loresentry-authentication/docs/INTERNAL_API.md)를 따른다.
기존 `GET /auth`는 연결 확인용 엔드포인트이며 위 계약에 포함하지 않는다.

## 로그인 후 고정 주소로 복귀

BFF는 원래 작업 화면을 보관·검증하지 않고, 성공·실패 모두 설정된 프론트 주소로 보낸다.
현재 존재하는 프론트 `/login` 화면을 결과 처리 진입점으로 사용한다.
이 화면의 로그인 결과 처리 기능은 프론트 구현 때 추가한다.

| 환경 | 고정 결과 주소 |
|---|---|
| local | `http://localhost:3000/login` |
| prod | `https://loresentry.com/login` |

결과 주소는 BFF 설정으로 받으며, 해당 환경의 허용 프론트 Origin과 일치하는지 시작 시 검증한다.
브라우저가 보낸 `returnUrl`, `redirect_uri`, Host·전달 헤더로 목적지를 정하지 않는다.
로그인 시작 API에 임의 복귀 경로를 받는 계약을 추가하지 않는다.

BFF는 고정 주소에 `result` 하나를 붙여 `303`으로 이동시킨다.
예를 들어 성공은 `/login?result=success`이다.

| result | 조건 |
|---|---|
| `success` | Auth 로그인 성공과 새 토큰 쌍 검사를 마치고 인증 쿠키 설정 |
| `cancelled` | Auth의 `OAUTH_LOGIN_DENIED` |
| `invalid` | 임시 쿠키·콜백 입력 누락 또는 Auth의 `OAUTH_REQUEST_INVALID` |
| `unavailable` | 로그인 준비·콜백의 알려진 통신 장애 또는 `LOGIN_UNAVAILABLE` |
| `failed` | 신원 검증 실패, 유효하지 않은 내부 응답과 그 밖의 로그인 실패 |

임시 상태의 만료·소비·state 불일치를 BFF에서 임의로 구분하지 않는다.
Auth가 제공하는 계약상 구분까지만 반영한다.
URL에 토큰·code·state·임시 식별자·제공자의 오류 설명을 복사하지 않는다.
결과 응답에는 `Referrer-Policy: no-referrer`를 적용한다.

실패 시 AT·RT를 새로 설정하거나 기존 AT·RT를 삭제하지 않는다.
OAuth 임시 쿠키는 [소비 여부에 따른 정리 규칙](auth/LOGIN_FLOW.md#oauth-임시-쿠키)을 따른다.
CSRF 등 공통 보안 검증으로 거절된 요청은 위 리다이렉트로 우회 처리하지 않는다.

프론트는 `result=success`만으로 로그인했다고 판단하지 않고 `GET /auth/users/me`로 확인한다.
쿼리 값은 사용자도 변경할 수 있는 화면 안내 값이다.
원래 화면 복귀는 [프론트 후속 메모](../../docs/frontend/LOGIN_RETURN.md)에서 다룬다.

## 보호 API 인증 실패

| HTTP | code | 조건 | next_action |
|---|---|---|---|
| 401 | `ACCESS_TOKEN_MISSING` | AT 쿠키 부재 | `REFRESH` |
| 401 | `ACCESS_TOKEN_EXPIRED` | 만료 외의 필수 검증은 통과한 AT의 만료 | `REFRESH` |
| 401 | `ACCESS_TOKEN_INVALID` | 서명·발급자·사용 대상·토큰 종류 등 AT 검증 실패 | `RELOGIN` |
| 401 | `SESSION_INVALID` | 활성 세션 부재·만료 또는 AT의 sid와 불일치 | `RELOGIN` |
| 503 | `SESSION_UNAVAILABLE` | 세션 연결·ACL·500ms 시간 제한·손상 등으로 검증 불가 | `RETRY_LATER` |
| 403 | `CSRF_REJECTED` | Origin·전용 헤더 검증 실패 | `NONE` |

`REFRESH`는 RT가 실제로 유효하다는 보장이 아니라 재발급을 시도할 수 있다는 뜻이다.
앞의 두 오류는 BFF가 내부 서비스에 전달하기 전에 생성한 경우에만 사용한다.
내부 서비스의 401을 이 오류로 바꾸지 않는다.
세션 오류에서는 재발급을 시도하지 않으며 실패 요청을 내부 서비스로 전달하지 않는다.
AT·세션 검사 실패는 쿠키를 설정하거나 삭제하지 않는다. sid 없는 AT는
ACCESS_TOKEN_INVALID로 거절하며 구 RT 저장 키를 조회하지 않는다.
프론트의 재발급 조율과 원래 요청 1회 재시도는 [기존 계약](auth/REFRESH_FLOW.md)을 따른다.

## 재발급 응답

성공 시 브라우저 쿠키만 교체하고 토큰을 JSON으로 반환하지 않는다.
RT가 없으면 Auth를 호출하지 않고 `401 REFRESH_REJECTED`, `next_action=RELOGIN`으로 응답한다.
Auth가 반환한 알려진 재발급 오류는 [내부 오류 계약](../../loresentry-authentication/docs/INTERNAL_API.md#오류-계약)의
상태 코드·code·next_action을 유지하고, message는 외부용 문구로 구성한다.

BFF와 Auth 사이의 통신 실패에서 요청이 전송되지 않았음이 확인되면
`503 REFRESH_UNAVAILABLE`, `RETRY_LATER`로 응답한다.
전송·처리 결과가 불명확하거나 성공 응답의 토큰 쌍이 유효하지 않으면
`503 REFRESH_OUTCOME_UNKNOWN`, `RELOGIN`으로 응답한다.
미분류 오류는 성공이나 재시도 가능한 결과로 바꾸지 않는다.

재발급을 자동 재시도하지 않으며, 실패 응답은 AT·RT 쿠키를 설정·삭제하지 않는다.
늦게 도착한 실패 응답이 다른 탭에서 성공한 새 쿠키를 지우지 않도록 하기 위한 선택이다.
재로그인이 필요한 경우 프론트는 보호 작업을 중단하고 로그인 흐름으로 진행한다.

## 로그아웃 응답

CSRF를 통과한 요청에서 AT·RT 삭제 헤더를 발급하고, 서버 RT 폐기 확인 결과를 별도로 반환한다.
`refresh_revocation`은 Auth의 해당 RT 폐기 결과이며 다른 기기의 로그인 상태를 나타내지 않는다.

| HTTP | 응답 | 의미 |
|---|---|---|
| 200 | `refresh_revocation=confirmed` | Auth가 폐기 완료·상태 부재 또는 유효하게 검증된 RT의 만료를 확인 |
| 200 | `refresh_revocation=not_requested` | 요청에 RT가 없어 Auth 폐기를 요청하지 않음 |
| 401 | `INVALID_REFRESH_TOKEN`, `next_action=NONE`, `refresh_revocation=rejected` | Auth가 폐기 입력 RT를 거절 |
| 503 | `REVOCATION_UNCONFIRMED`, `next_action=NONE`, `refresh_revocation=unconfirmed` | Auth 오류·통신 실패 등으로 폐기 완료를 확인하지 못함 |

실패 응답에도 쿠키 삭제 헤더를 붙인다. 단, CSRF 거절은 이 처리에 진입하지 않는다.
응답에 `cookies_deleted=true`처럼 브라우저의 실제 적용을 보증하는 필드는 두지 않는다.
RT가 없다는 사실을 서버에 남은 RT가 없다는 뜻으로 해석하지 않는다.
Auth의 제한된 폐기 재시도 외에 BFF의 재시도나 백그라운드 폐기 작업은 추가하지 않는다.

## 계정 오류와 검증

본인 계정 API의 알려진 Auth 오류는 내부 계약의 상태·code·next_action을 유지한다.
내부 API의 `USER_CONTEXT_REQUIRED`나 전달 헤더 오류는 BFF의 사용자 전달 결함일 수 있으므로
일반 보호 API의 재발급 대상 오류로 취급하지 않는다.
계정 조회·수정의 통신 장애는 `503 ACCOUNT_UNAVAILABLE`, `RETRY_LATER`로 반환한다.
미분류 내부 응답은 `502 UPSTREAM_INVALID_RESPONSE`, `NONE`으로 처리하고 원문을 노출하지 않는다.
수정 요청의 응답 유실은 처리 여부 미확인이므로 자동 재시도하지 않는다.

구현 시 고정 복귀 주소·허용 결과값, 재발급 대상 오류의 생성 위치,
재발급 실패의 쿠키 미변경과 로그아웃 실패의 쿠키 삭제 헤더를 검증한다.
프론트는 로그아웃의 401이나 내부 서비스 401로 재발급을 시작하지 않아야 한다.

## 선택 이유

로그인은 고정 `/login` 화면을 재사용해 BFF에 복귀 경로 저장소를 추가하지 않는다.
재발급은 쿠키 갱신만 필요하므로 204로, 로그아웃은 폐기 확인 여부를 전달해야 하므로
결과 본문을 반환한다. 외부·내부 경로는 통일해 일대일 호출 관계를 쉽게 추적하도록 한다.
같은 경로를 사용하더라도 Auth 내부 응답을 그대로 브라우저에 노출하지 않는다.
