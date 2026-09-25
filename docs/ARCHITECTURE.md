# BFF 역할과 애플리케이션 구조

2026-09-26 목표 구조다. 현재 코드는 인증 경계를 아직 전환하지 않았다.

**BFF는 하나의 Gradle 모듈에서 브라우저 HTTP 처리, 요청 조율, 내부 서비스 호출,
보안을 분리한다.** 기존 Spring MVC·Virtual Threads·RestClient 구성을 유지한다.

## 역할과 책임 경계

BFF는 명시적인 외부 API별로 내부 서비스를 호출하고 응답을 구성한다.
임의 경로를 전달하는 catch-all 프록시는 두지 않는다.

| 영역 | BFF가 담당하는 일 | 규칙의 소유자 |
|---|---|---|
| 인증 | 세션 검증·활동 연장, 인증 요청 연결, 브라우저 쿠키 처리 | 세션 생성·교체·폐기와 Google 신원 검증은 Auth |
| 계정 | 본인 계정 조회·수정 API 연결 | 계정 데이터와 표시 이름 규칙은 Auth |
| 도메인 요청 | 검증한 사용자 전달, 서비스 호출, 응답 조합 | 프로젝트·파일 등의 데이터와 접근 권한은 해당 서비스 |
| 브라우저 경계 | CSRF·CORS, 외부 응답과 리다이렉트 구성 | BFF |

BFF에는 자체 사용자 세션 저장소나 도메인 DB를 두지 않는다.
[공유 세션 계약](../../loresentry-authentication/docs/session/SESSION_DESIGN.md)에 따라
Auth의 세션 저장소에서 ID를 검증하고 TTL을 연장한다. 로그인 생성·교체·폐기는 Auth가 담당한다.
자체 HttpSession·허용 결과 캐시·읽기 복제본은 사용하지 않는다.
여러 서비스의 상태 변경을 하나의 트랜잭션처럼 보장하거나 보상하는 규칙은 담당 도메인과 별도로 설계한다.

## 패키지와 의존 방향

기본 패키지는 `com.loresentry.gateway`다. 기능이 늘어나면 아래 패키지 안을
`auth`, `content` 등으로 나누며, 아직 계약이 없는 기능의 빈 코드는 만들지 않는다.

| 패키지 | 역할 |
|---|---|
| `web` | 컨트롤러, 외부 요청·응답 DTO, 쿠키·리다이렉트 처리, HTTP 오류 응답 변환 |
| `application` | 요청별 서비스 호출 순서, 결과 조합, 브라우저 처리에 필요한 결과 반환 |
| `client` | 서비스별 RestClient 호출, 내부 API DTO 변환, 내부 오류·통신 실패 분류 |
| `security` | 세션 검증·연장, 요청의 인증 사용자 구성, CSRF 검증, 인증·보안 실패 처리 |
| `config` | 보안·CORS 설정, 클라이언트·설정값·실행기 구성과 의존성 주입 |

의존 방향은 다음과 같이 제한한다.

- `web`은 `application`을 호출한다. 내부 서비스를 호출하는 컨트롤러는 `client`를 직접 호출하지 않는다.
  자체 상태만 반환하는 헬스 엔드포인트에는 별도 application 서비스를 만들지 않아도 된다.
- `application`은 `client`의 호출 계약을 사용한다. `web`이나 `security`에 의존하지 않는다.
- `client`는 `web`, `application`, `security`를 참조하지 않는다.
- `security`는 client의 원자적 세션 검증·연장 결과로 사용자 UUID를 웹 경계에 제공한다.
  Auth·Content HTTP 호출이나 응답 조합을 수행하지 않는다.
- `config`는 필요한 구현체를 연결한다. 다른 패키지는 설정 클래스를 호출해 업무 처리를 수행하지 않는다.

Spring 컴포넌트와 생성자 주입을 사용하며 별도 입력·출력 포트는 두지 않는다.
client는 구체 클래스로 시작하고 교체 구현이 필요할 때 인터페이스를 추가한다.

## 요청과 데이터의 경계

보안 검증을 통과하면 컨트롤러가 사용자 UUID와 요청 값을 application에 전달한다.
application과 client는 전역 보안 컨텍스트에서 사용자를 직접 조회하지 않는다.

application의 입력·결과에는 필요한 값만 담는다. Servlet 요청·응답, `ResponseEntity`,
Spring Security 인증 객체와 RestClient 응답 객체는 사용하지 않는다.
인증 결과의 세션 ID는 요청 처리 중에만 보유하고 web이 쿠키로 변환한다. 외부 응답 DTO나 로그에 세션 ID를 넣지 않는다.

외부·내부 API DTO는 분리한다. client는 내부 응답을 변환하고, application은 결과를 조합하며,
web은 외부 응답을 만든다. 내부 응답을 범용 `Map`으로 노출하거나 Auth와 HTTP DTO 라이브러리를 공유하지 않는다.

client의 헤더 구성은 [사용자 정보 전달 계약](../../docs/bff/INTERNAL_SERVICE_CALLS.md#사용자-정보-전달),
보호 API와 인증 진입점 구분은 [인증 책임](../../docs/bff/auth/AUTH_RESPONSIBILITIES.md)을 따른다.

## 응답 조합과 오류 처리

응답 조합은 application이 담당한다. 병렬 실행과 부분 실패 정책은 실제 조합 API가 정해질 때 설계한다.
보호 요청에서는 [세션 활동 흐름](auth/SESSION_FLOW.md)에 따라 검증과 만료 연장을 수행한다.

client는 [내부 호출 정책](../../docs/bff/INTERNAL_SERVICE_CALLS.md#초기-http-호출-정책)에 따라 오류를 분류한다.
OAuth 소비·세션 폐기 결과가 불명확하면 성공이나 미실행으로 단정하지 않고,
`login_request_consumed` 등 [Auth 내부 API](../../loresentry-authentication/docs/INTERNAL_API.md)의 처리 정보를 보존한다.

application은 후속 처리를 정하고 web은 상태 코드·본문·쿠키·리다이렉트를 만든다.
로그아웃의 Auth 폐기 실패처럼 쿠키 삭제가 필요한 결과를 공통 예외 응답으로 먼저 끝내지 않는다.

컨트롤러 진입 전 보안 실패는 security가 응답한다. web과 security는 같은
[외부 오류 계약](EXTERNAL_API.md)을 따르며, [CSRF 검사](BROWSER_SECURITY.md#csrf-검증-계약)는 세션 검사보다 먼저 수행한다.
내부 본문·헤더·예외 메시지를 그대로 노출하지 않는다.

## 선택 이유

| 결정 | 이유와 감수하는 점 |
|---|---|
| 단일 모듈과 다섯 패키지 | 현재 한 BFF에서 경계를 드러내기에 충분하다. 모듈 간 강제 격리 대신 코드 리뷰와 테스트로 의존 방향을 확인한다. |
| 기존 MVC·Virtual Threads·RestClient 유지 | 현재 코드 기반을 이어 사용한다. 실제 요청 조합이나 스트리밍 요구가 정해지기 전에 웹 스택을 교체하지 않는다. |
| Auth와 다른 구조 사용 | BFF의 중심 책임은 HTTP 경계와 호출 조율이다. 별도 도메인 계층과 포트 체계 없이도 필요한 책임을 분리할 수 있다. |
| 단순 중계도 application을 경유 | 인증 연동과 후속 응답 조합의 진입점을 통일한다. 작은 API에도 얇은 조율 메서드가 생기는 비용은 수용한다. |
| 외부·내부 DTO 분리 | 내부 API 변경과 민감 정보가 브라우저 계약에 바로 전파되지 않게 한다. 변환 코드가 늘어나는 비용은 수용한다. |
| 사용자 UUID를 명시적으로 전달 | 호출자의 출처를 확인하기 쉽고, 보안 컨텍스트에 의존하지 않고 조율 코드를 검증할 수 있다. |

## 구현과 검증

web·application·client·security·config 경계와 Content DTO·경로는 유지하고 인증 경계를
단일 ID 방식으로 전환한다. 현재 소스의 클래스 이름을 새 계약의 구현 완료로 해석하지 않는다.

- 보안 실패 요청의 내부 서비스 미도달과 쿠키 미변경을 검증한다.
- client의 세션 검증·연장과 오류·실행 결과 불명 처리를 실제 저장소에서 검증한다.
- application의 조율, web의 쿠키·리다이렉트·외부 DTO와 민감값 비노출을 확인한다.
- 프론트의 활동 연장과 인증 전환, 운영 ACL·접근 제한을 확인한다.

[운영 준비](OPERATIONS.md)와 [공동 전환](ROLLOUT.md)을 새 계약으로 수행한다.
