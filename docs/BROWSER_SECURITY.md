# BFF 브라우저 보안

2026-09-26 단일 세션 보안 계약을 코드에 반영했다. 실제 브라우저·운영 검증은 후속 작업이다.

## 브라우저 경계

로그인 인증값은 HttpOnly 세션 쿠키 하나다. OAuth 진행 중에만 별도 임시 쿠키를 사용한다.
세션 ID를 브라우저 JSON·URL·JavaScript 저장소에 노출하지 않는다.
운영 프론트와 API는 같은 사이트의 다른 Origin이므로 CORS가 필요하다.
쿠키 요청은 `credentials: "include"`를 사용한다.

## 세션 쿠키

| 항목 | 계약 |
|---|---|
| prod 이름 | `__Host-ls_session` |
| local 이름 | `ls_session` |
| 값 | Auth가 생성한 43자 난수 session_id 원문 |
| 공통 속성 | HttpOnly, Path=/, Domain 생략, SameSite=Strict |
| Secure | prod true, 명시적 local HTTP에서만 false |
| 수명 | 마지막 인증 활동으로부터 14일. 활동 시 같은 ID로 갱신 |

로그인 때 Auth의 expires_at, 보호 요청에서는 Redis 검증·연장이 반환한 expires_at을
사용한다. 응답 헤더를 만들 때 남은 시간을 초 단위로 내림해 Max-Age를 계산하고
1,209,600초를 상한으로 한다. 서버 만료에 시계 오차 유예를 더하지 않는다.
BFF와 Redis의 시각 동기화를 운영에서 확인하며 인증 판정은 Redis TTL을 기준으로 한다.

로그인은 ID 형식·남은 수명을 모두 확인한 뒤 Set-Cookie를 구성한다. 보호 요청은 검증·
연장 성공 시 같은 ID를 다시 설정한다. 이후 도메인 오류가 발생해도 해당 인증 활동은 유지한다.
만료 정보가 잘못됐거나 남은 수명이 양수가 아니면 쿠키를 발급하지 않는다.
쿠키를 갱신하는 응답은 도메인 응답도 `Cache-Control: no-store`다.
삭제는 같은 이름·Path·Domain 범위에서 Max-Age=0으로 한다.

로그인 실패·세션 검증 실패·연장 결과 불명·CSRF 실패에서는 세션 쿠키를 변경하지 않는다.
로그아웃은 CSRF 통과 후 폐기 결과와 별개로 삭제 헤더를 반환하며 활동 연장은 수행하지 않는다.
브라우저가 쿠키를 수락·보관했다는 보장은 하지 않는다.

동일 ID의 동시 응답은 도착 순서에 따라 쿠키 만료를 앞당길 수 있다. 다른 로그인 ID의
늦은 응답은 새 쿠키를 덮을 수도 있다. 응답을 JS에서 무시해도 Set-Cookie는 적용될 수 있으므로
[인증 전환 시 요청 조율](FRONTEND_AUTH_CONTRACT.md#인증-전환과-늦은-응답)을 구현한다.
이 한계가 서버에서 이전 세션을 다시 활성화해도 된다는 뜻은 아니다.

## SameSite 계약

세션 쿠키는 Strict, Google 최상위 GET 콜백의 OAuth 임시 쿠키는 Lax다.
콜백은 기존 세션 쿠키 없이 임시 쿠키·state로 검증하고, 프론트 복귀 후 새 세션으로 API를 호출한다.
같은 사이트의 다른 서브도메인도 있으므로 SameSite가 Origin·전용 헤더 검증을 대체하지 않는다.

`__Host-`는 Secure·Path=/·Domain 생략을 요구한다. API 호스트의 쿠키는 내부 도메인 서비스로
전달하지 않는다. 세션 ID의 폐기 요청에만 BFF가 지정된 Auth API 본문으로 전달한다.
OAuth 임시 쿠키의 5분 수명·소비 여부 처리는 [로그인](auth/LOGIN_FLOW.md#oauth-임시-쿠키)을 따른다.

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
| 세션 저장소 | 개발 Auth와 공유하는 Redis | 운영 Auth와 공유하는 Redis |
| Google 콜백 주소 | `http://localhost:8000/auth/oauth/google/callback` | `https://api.loresentry.com/auth/oauth/google/callback` |

localhost는 Windows 브라우저 기준이며 Linux 호스트의 서버에는 SSH·VS Code 포트 전달로 연결한다.
내부 서비스 주소는 BFF 프로세스·컨테이너 기준으로 따로 설정한다.
개발 브라우저 주소는 localhost로 통일하고, 변경 시 출처·공개 주소·OAuth 등록값을 함께 맞춘다.

HTTP 쿠키 예외는 명시적인 local 프로필의 localhost·루프백 공개 주소에만 적용한다.
임의 요청 헤더나 ALB 뒤의 내부 HTTP 연결을 근거로 운영 쿠키의 Secure를 해제하지 않는다.
OAuth 임시 쿠키도 [기존 환경별 계약](auth/LOGIN_FLOW.md#oauth-임시-쿠키)을 유지한다.

개발 Auth의 DB·Redis·자격 증명도 운영과 분리하고, 개발 주소가 없으면 운영으로 대체하지 않는다.
Google 콜백은 [Auth 설정과 Google 등록값](../../loresentry-authentication/docs/implementation/IMPLEMENTATION_NOTES.md#oauth-요청-설정)을 일치시킨다.
비밀 값은 설정 파일에 기록하지 않고 환경변수·Secret 등 실행 환경에서 주입한다.

## CSRF 검증 계약

별도 CSRF용 비밀값·쿠키·발급 API 없이 Origin과 `X-LS-CSRF: 1`을 검사한다.
상태 변경 API는 `POST`, `PUT`, `PATCH`, `DELETE`로 제공하고 다음 조건을 모두 적용한다.
로그아웃도 검사 대상이며 유효한 세션이 없어도 이 검증을 수행한다.

| 항목 | 계약 |
|---|---|
| 요청 출처 | `Origin`의 스킴·호스트·포트가 환경별 허용 목록의 한 출처와 정확히 일치해야 한다. |
| Origin 오류 | 누락, `null`, 복수 값, 형식 오류와 비허용 출처를 거절한다. Referer로 대체하지 않는다. |
| 전용 헤더 | `X-LS-CSRF: 1`을 요구한다. 헤더 이름은 대소문자를 구분하지 않고, 값은 `1` 하나만 허용한다. |
| 헤더 오류 | 누락, 중복 또는 다른 값을 거절한다. 쿼리·본문의 값으로 대체하지 않는다. |
| 검증 실패 | [외부 오류 계약](EXTERNAL_API.md#보호-api-인증-실패)의 `403 CSRF_REJECTED`, `next_action=NONE`으로 응답한다. |
| 실패 시 동작 | 내부 서비스를 호출하거나 인증 쿠키를 설정·삭제하지 않는다. 프론트는 이 오류로 자동 재시도를 시작하지 않는다. |

프론트는 변경 요청에 헤더를 추가한다. 값 `1`은 비밀이 아니며 갱신·삭제할 CSRF 상태도 없다.
일반 HTML 폼의 직접 제출은 필수 헤더가 없어 지원하지 않는다.

CSRF 검사는 세션 검사·내부 호출보다 먼저 수행하며, 통과 후에도 API의 인증·입력 검증을 거친다.

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
- 일반 `GET`, `HEAD`, `OPTIONS`는 도메인 데이터 변경·로그아웃을 수행하지 않는다.
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

- 개발·운영의 Origin·서비스·자격 증명·저장소 분리를 확인한다.
- 세션 쿠키의 이름·속성·발급·활동 시 연장·삭제를 실제 브라우저에서 확인한다.
- Google 콜백은 기존 세션 없이 임시 쿠키로 검증하고 복귀 후 Strict 쿠키를 사용하는지 확인한다.
- 선행 CSRF 실패는 저장소 연장·내부 호출·쿠키 변경을 수행하지 않는지 확인한다.
- 유효 세션은 도메인 오류에도 활동 연장이 적용되고, 공개 경로·로그아웃은 연장하지 않는지 확인한다.
- 쿠키 만료·14일 비활동·응답 지연·다른 탭 로그인·로그아웃 경합을 검증한다.
- 비허용 Origin·중복 헤더·폼 제출을 거절하고 정상 사전 요청은 허용하는지 확인한다.

이전 브라우저 검증 제외 기록은 새 계약의 완료 판정이 아니다.
구현 후 [프론트 인계](FRONTEND_AUTH_CONTRACT.md)와 [공동 전환](ROLLOUT.md)을 검증한다.
