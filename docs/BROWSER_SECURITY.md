# BFF 브라우저 보안

2026-09-17 결정. 브라우저의 쿠키·CSRF·CORS와 환경별 설정을 정의한다.

## 브라우저 경계

Auth가 발급한 AT·RT는 BFF가 HttpOnly 쿠키로 전달하며 브라우저 JSON 응답이나 URL에 넣지 않는다.
상태 변경 요청에는 CSRF 검사를 적용하고, CORS도 BFF가 처리한다.
운영 프론트와 API는 같은 사이트지만 출처가 다르므로 CORS 설정이 필요하다.
프론트는 쿠키를 사용하는 API 호출에 `credentials: "include"`를 적용한다.

## AT·RT 쿠키 발급과 수명

두 쿠키 모두 HttpOnly, `Path=/`, Domain 생략, `SameSite=Strict`를 적용한다.
Secure는 HTTPS에서 true이며 [local HTTP 예외](#환경별-설정)에서만 false다.

| 항목 | AT | RT |
|---|---|---|
| HTTPS 환경 이름 | `__Host-ls_at` | `__Host-ls_rt` |
| local HTTP 이름 | `ls_at` | `ls_rt` |
| Max-Age 기준 | Auth의 `access_expires_at`까지 남은 시간 | Auth의 `refresh_expires_at`까지 남은 시간 |

토큰 수명은 [Auth JWT 계약](../../loresentry-authentication/docs/token/JWT_DESIGN.md#토큰-유효기간)의 AT 15분·RT 14일을 따른다.
BFF는 응답을 만드는 시점의 남은 시간을 초 단위로 내림해 Max-Age를 계산하고,
각 토큰의 확정 유효기간을 상한으로 둔다. JWT 검증의 시계 오차 허용분은 더하지 않는다.
쿠키의 존재와 별개로 서버는 토큰을 검증한다.

로그인·재발급 성공 시 AT·RT를 각각의 Set-Cookie로 함께 교체한다.
내부 응답의 토큰·만료 시각이 누락됐거나 쿠키의 남은 수명이 양수가 아니면 새 토큰 쌍을
성공으로 전달하지 않는다. 발급 정보 검사를 마친 뒤 응답 쿠키를 구성한다.
삭제는 발급 때와 같은 이름·Path·Domain 범위에서 `Max-Age=0`으로 처리한다.
요청별 교체·삭제 여부와 `no-store` 응답은 [외부 API 계약](EXTERNAL_API.md)을 따른다.

## SameSite 계약

local·prod 모두 아래 값을 명시한다. 새 쿠키는 용도에 따라 별도로 판단한다.

| 쿠키 | SameSite | 선택 이유 |
|---|---|---|
| AT | `Strict` | 같은 사이트의 프론트에서 보호 API를 호출하므로 외부 사이트의 페이지 이동에 첨부할 필요가 없다. |
| RT | `Strict` | 재발급·로그아웃도 같은 사이트의 프론트가 API로 요청한다. |
| OAuth 임시 쿠키 | `Lax` | Google에서 돌아오는 최상위 GET 콜백에서 브라우저 연결을 확인해야 한다. |

운영은 모두 HTTPS, local은 모두 HTTP localhost를 사용하는 같은 사이트 구성이다.
호스트나 스킴을 바꿀 때는 이 전제를 다시 확인한다.

Strict는 같은 사이트의 다른 서브도메인까지 차단하지 않으므로,
합의한 [Origin·전용 헤더 검증](#csrf-검증-계약)을 유지한다.
Google 콜백은 기존 AT·RT 첨부를 요구하지 않고
[OAuth 임시 쿠키와 state 검증](auth/LOGIN_FLOW.md)을 사용한다.
로그인 성공 후 프론트로 복귀해 시작하는 API 호출에서 새 AT·RT를 사용한다.

## RT 쿠키의 전송 범위

`__Host-` 접두사를 지원하는 브라우저는 HTTPS에서 설정, Secure 적용, Domain 생략,
`Path=/` 조건을 강제한다. 이로써 다른 서브도메인의 상위 도메인용 쿠키나 같은 이름의
다른 경로용 쿠키가 끼어드는 것을 방지한다.
[접두사 규칙](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie#cookie_prefixes)

이 보호를 얻기 위해 RT가 필요 없는 일반 API에도 쿠키가 첨부되는 비용을 수용한다.
`Path=/`는 API 호스트의 전체 경로에 적용되며, 다른 호스트 전송이나 API 접근 권한을 뜻하지 않는다.
실제 전송에는 SameSite 등 다른 쿠키 조건도 적용된다.

BFF는 요청의 RT를 재발급·로그아웃에서만 사용한다. 일반 보호 API는 AT 검증 후 활성 세션을 조회하고,
RT가 첨부되어도 인증 대체나 자동 재발급에 사용하지 않는다.
내부 전달은 [내부 서비스 호출 계약](../../docs/bff/INTERNAL_SERVICE_CALLS.md#사용자-정보-전달)을 따른다.

## 환경별 설정

개발 프론트는 개발용 BFF에 연결하고 같은 코드의 `local`·`prod` 프로필로 설정을 분리한다.
운영 API에 개발 Origin을 추가하거나 별도 개발 Kubernetes 배포를 만드는 결정은 아니다.

| 설정 파일 | 역할 |
|---|---|
| `application.yaml` | 환경에 공통인 설정과 동작 기준 |
| `application-local.yaml` | 개발용 주소·CORS와 로컬 HTTP 쿠키 예외 |
| `application-prod.yaml` | 배포용 주소·CORS와 HTTPS 쿠키 설정 |

[Spring Boot 프로필](https://docs.spring.io/spring-boot/reference/features/profiles.html)은
`SPRING_PROFILES_ACTIVE=local` 또는 `prod`로 명시한다. local 기본 활성화와 두 프로필 동시 사용은 허용하지 않는다.
인증·CSRF 로직은 두 환경에서 동일하게 적용한다.

| 항목 | 개발용 `local` | 배포용 `prod` |
|---|---|---|
| 브라우저의 프론트 주소·허용 Origin | `http://localhost:3000` | `https://loresentry.com` |
| 브라우저의 BFF 주소 | `http://localhost:8000` | `https://api.loresentry.com` |
| 내부 서비스 주소 | 개발용 서비스 주소를 명시 | 운영 클러스터의 서비스 주소 |
| JWT 검증 공개키 | 개발 Auth의 공개키 | 운영 Auth의 공개키 |
| Google 콜백 주소 | `http://localhost:8000/auth/oauth/google/callback` | `https://api.loresentry.com/auth/oauth/google/callback` |

localhost는 Windows 브라우저 기준이며 Linux 호스트의 서버에는 SSH·VS Code 포트 전달로 연결한다.
내부 서비스 주소는 BFF 프로세스·컨테이너 기준으로 따로 설정한다.
개발 브라우저 주소는 localhost로 통일하고, 변경 시 출처·공개 주소·OAuth 등록값을 함께 맞춘다.

HTTP 쿠키 예외는 명시적인 local 프로필의 localhost·루프백 공개 주소에만 적용한다.
임의 요청 헤더나 ALB 뒤의 내부 HTTP 연결을 근거로 운영 쿠키의 Secure를 해제하지 않는다.
OAuth 임시 쿠키도 [기존 환경별 계약](auth/LOGIN_FLOW.md#oauth-임시-쿠키)을 유지한다.

개발 Auth의 DB·Redis·서명키·자격 증명도 운영과 분리하고, 개발 주소가 없으면 운영으로 대체하지 않는다.
Google 콜백은 [Auth 설정과 Google 등록값](../../loresentry-authentication/docs/implementation/IMPLEMENTATION_NOTES.md#oauth-요청-설정)을 일치시킨다.
비밀 값은 설정 파일에 기록하지 않고 환경변수·Secret 등 실행 환경에서 주입한다.

## CSRF 검증 계약

별도 CSRF 토큰·쿠키·발급 API·서버 세션 없이 Origin과 `X-LS-CSRF: 1`을 검사한다.
상태 변경 API는 `POST`, `PUT`, `PATCH`, `DELETE`로 제공하고 다음 조건을 모두 적용한다.
재발급·로그아웃도 검사 대상이며 유효한 AT가 없어도 이 검증을 수행한다.

| 항목 | 계약 |
|---|---|
| 요청 출처 | `Origin`의 스킴·호스트·포트가 환경별 허용 목록의 한 출처와 정확히 일치해야 한다. |
| Origin 오류 | 누락, `null`, 복수 값, 형식 오류와 비허용 출처를 거절한다. Referer로 대체하지 않는다. |
| 전용 헤더 | `X-LS-CSRF: 1`을 요구한다. 헤더 이름은 대소문자를 구분하지 않고, 값은 `1` 하나만 허용한다. |
| 헤더 오류 | 누락, 중복 또는 다른 값을 거절한다. 쿼리·본문의 값으로 대체하지 않는다. |
| 검증 실패 | [외부 오류 계약](EXTERNAL_API.md#보호-api-인증-실패)의 `403 CSRF_REJECTED`, `next_action=NONE`으로 응답한다. |
| 실패 시 동작 | 내부 서비스를 호출하거나 인증 쿠키를 설정·삭제하지 않는다. 프론트는 이 오류로 토큰 재발급이나 자동 재시도를 시작하지 않는다. |

프론트는 변경 요청에 헤더를 추가한다. 값 `1`은 비밀이 아니며 갱신·삭제할 CSRF 상태도 없다.
일반 HTML 폼의 직접 제출은 필수 헤더가 없어 지원하지 않는다.

CSRF 검사는 AT 검사·내부 호출보다 먼저 수행하며, 통과 후에도 API의 인증·입력 검증을 거친다.

## CORS와 예외 경로

CSRF와 CORS는 [환경별 프론트 Origin](#환경별-설정)을 공유한다.
전체 서브도메인·모든 포트를 허용하는 패턴은 사용하지 않는다.

CORS는 credentials를 허용하고, 허용된 출처에만 해당 Origin을 응답한다.
인증·계정과 Content API의 메서드는 `GET`, `HEAD`, `POST`, `PATCH`, `PUT`, `DELETE`, `OPTIONS`,
요청 헤더는 `Content-Type`, `X-LS-CSRF`, `If-Match`, `If-None-Match`, `X-Save-Id`로 제한한다.
프론트 JavaScript에 `Location`을 노출하며 사전 요청의 max-age는 1시간이다.
다른 메서드·헤더가 필요해지면 외부 API 계약과 함께 명시적으로 추가한다.
CORS 응답의 `Vary: Origin`을 유지하고, 인증 응답은 공유 캐시에서 재사용하지 않는다.

- CORS 사전 요청은 실제 인증·CSRF 검사보다 먼저 처리한다. 사전 요청에는 인증 쿠키나
  `X-LS-CSRF` 값 자체를 요구하지 않고, 출처·요청 예정 메서드·헤더를 확인한다.
- 사전 요청 성공이 실제 요청 검사를 대신하지 않는다. 실제 변경 요청에도 위 계약을 적용한다.
- 일반 `GET`, `HEAD`, `OPTIONS`는 도메인 데이터 변경·재발급·로그아웃을 수행하지 않는다.
  지원하지 않는 메서드는 라우팅에서 거절한다.
- OAuth의 `GET /auth/oauth/google/prepare`와 `GET /auth/oauth/google/callback`은 페이지 이동 흐름으로
  취급하며 Origin·전용 헤더 요구를 적용하지 않는다. 콜백은
  [state와 브라우저 연결 검증](../../loresentry-authentication/docs/login/LOGIN_FLOW.md)을 따른다.
  로그인 준비의 임시 상태 생성도 이 전용 흐름에 한정한다.
  인증 경로 전체를 `/auth/**`로 일괄 예외 처리하지 않는다.

비허용 출처는 오류 응답도 읽지 못할 수 있으며, 이를 위해 허용 목록을 넓히지 않는다.

## 선택 이유

[OWASP의 전용 헤더 방식](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#employing-custom-request-headers-for-ajaxapi)으로
CSRF 토큰 발급·갱신 절차를 줄인다. 다른 출처의 JavaScript는 사전 검사를 거쳐야 하고 일반 폼에는 필수 헤더가 없다.
대신 모든 변경 API에서 검사를 수행하고 Origin 허용 목록을 좁게 유지해야 한다.
SameSite는 추가 방어이며 이 검사를 대체하지 않는다. 허용된 프론트의 XSS나 비브라우저 호출자 인증을 해결하지는 않는다.

## 구현 시 검증

- 환경: 개발도 인증·CSRF를 검사하며, 출처·연결 대상·키·저장소가 운영과 분리되는지 확인한다.
- 쿠키: 환경별 이름·속성·수명과 교체·삭제 범위가 계약과 일치하는지 확인한다.
- 브라우저: 로컬 포트 전달, AT·RT 없는 Google 콜백의 임시 쿠키 전달, 복귀 후 Strict 쿠키 사용을 확인한다.
- RT: 일반 요청에서 인증·자동 재발급에 사용하거나 도메인 서비스에 전달하지 않는지 확인한다.
- CSRF: 비허용 서브도메인·유사 도메인·Origin 오류·헤더 오류·폼 제출을 거절한다.
  사전 요청 없이 직접 호출해도 검사하며, 재발급·로그아웃을 포함해 실패 시 내부 호출·쿠키 변경이 없는지 확인한다.
- 예외: 정상 CORS 사전 요청과 Google 페이지 이동·콜백을 차단하지 않는지 확인한다.

## 현재 구현과 검증 상태

환경별 설정·정확한 Origin·CSRF·쿠키·AT와 세션 경계는 구현했고 일반·실제 서비스 통합
검증을 통과했다. [외부 API](EXTERNAL_API.md)와 [검증 결과](verification/LOREKEEPER-573.md)를
따른다. 위 목록의 실제 브라우저 항목은 [사용자 승인으로 이번 완료 범위에서 제외](verification/LOREKEEPER-574.md)했다.
운영 배포 준비는 [별도 미완료 상태](OPERATIONS.md)다.
