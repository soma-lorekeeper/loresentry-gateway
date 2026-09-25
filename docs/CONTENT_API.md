# BFF Content 외부 API 계약

LOREKEEPER-551. [조사 결과](CONTENT_CONTRACT_AUDIT.md)의 26개 경로·메서드와 JSON
필드를 외부 계약으로 유지한다. 해당 경로를 구현했고 실제 Content 연동 결과는
[LOREKEEPER-574](verification/LOREKEEPER-574.md)에 기록했다.
Content의 도메인 규칙과 프론트의 화면 모델을 변경하지 않는다.

## 명시적 API와 데이터 경계

조사표의 각 경로·메서드를 컨트롤러에 선언한다. namespace 와일드카드 중계를 제거하고
지원하지 않는 경로는 404, 지원하지 않는 메서드는 405로 거절한다. HEAD는 명시한 GET의
표준 HTTP 동작으로 제공하며 상태 변경을 수행하지 않는다.

web은 외부 요청 DTO를 읽고 사용자 UUID와 필요한 값을 application에 전달한다.
application은 client 호출 계약을 사용하며, client는 내부 DTO를 읽고 알려진 결과와
실패를 반환한다. web이 외부 응답 DTO와 상태·헤더를 구성한다. byte[]·Map으로 임의 내부
본문을 그대로 반환하지 않는다. HTTP DTO 라이브러리를 다른 서비스와 공유하지 않는다.

JSON 요청은 정의된 필드와 타입만 허용한다. 알 수 없는 필드·잘못된 타입·잘못된 JSON은
400 INVALID_REQUEST/NONE으로 처리한다. 이름 길이·중복·소유권·휴지통·문서 잠금·버전 경쟁은
Content에서 판단한다. PATCH name/description의 null은 현행 Content와 같이 변경 없음이며,
설명을 비우려면 빈 문자열을 사용한다. label 요청 본문은 버전 생성에서만 생략할 수 있다.

경로 UUID 형식 오류는 현재 Content가 사용하는 404 PROJECT_NOT_FOUND/NONE을 유지한다.
검색 q는 원래 문자열의 의미를 보존해 한 번만 인코딩한다. 임의 쿼리를 통째로 전달하지 않는다.

## 인증과 헤더

최종 보호 API는 CSRF(변경 요청)·단일 세션 검증과 활동 만료 연장 후 검증된 UUID만 사용한다.
외부 X-User-Id, Cookie, Authorization은 도메인 서비스 전달 목록에서 제외하고 client가
검증된 사용자 ID 하나로 X-User-Id를 구성한다. 단일 ID의 세션 검증·연장은 2026-09-26 코드에 반영했다. 실제 Content 통합 검증은 595에서 수행한다.
과거 구조 정렬만 완료된 중간 버전을 인증 완료로 취급하지 않는다.

| 헤더 | 처리 |
|---|---|
| Content-Type | JSON 본문 요청에서 application/json을 사용한다. |
| If-Match | 문서 저장·버전 복원의 입력을 의미 변경 없이 전달한다. 필수 여부·revision 판단은 Content가 수행한다. |
| X-Save-Id | 문서 저장의 선택적 멱등 키를 전달한다. BFF는 값을 새로 만들거나 실패 요청을 재전송하지 않는다. |
| If-None-Match | GET에서 선택적으로 전달한다. 현재 Content는 이를 소비하지 않으며 BFF가 304·ETag 기능을 새로 제공하지 않는다. |
| Location | 프로젝트 생성 시 검증된 응답 id로 상대 /projects/{id}를 구성한다. 내부 Location 원문을 복사하지 않는다. |
| Cache-Control | 보호 Content 응답에 no-store를 적용한다. 임의 내부 캐시 정책을 복사하지 않는다. |

전용 요청 헤더가 중복되면 모호한 값을 선택하지 않고 400 INVALID_REQUEST로 거절한다.
브라우저의 인증 쿠키와 세션 ID는 도메인 호출에 전달하지 않는다.

브라우저 CORS의 허용 메서드는 GET·HEAD·POST·PATCH·PUT·DELETE·OPTIONS,
요청 헤더는 Content-Type·X-LS-CSRF·If-Match·If-None-Match·X-Save-Id다.
Location은 기존 연동의 노출 헤더로 유지한다. X-User-Id는 인증 전환 후 허용 목록에서 제외한다.
Origin·credentials·preflight·CSRF 순서는 [브라우저 보안](BROWSER_SECURITY.md)을 따른다.

## 성공 응답

조사표의 상태 코드·DTO·nullable 필드를 유지한다. 프로젝트 생성은 201과 상대 Location,
파일·에피소드 생성 및 버전 생성은 201이다. 휴지통 이동·영구 삭제·에피소드 삭제·버전 삭제는
204이며 본문이 없다. 그 밖의 표에 정의된 결과는 200이다.

프로젝트 last_file은 null 또는 최근 수정된 활성 문서의 id/title이며 외부 DTO로 변환한다.
파일 생성은 요청 kind에 따라 문서와 에피소드 내부 DTO를 구분한다. 트리 응답은
folders·episodes·documents의 정규화된 목록으로 유지한다.

## 오류 변환

일반 오류는 code·message·next_action이다. 알려진 오류의 상태와 의미를 유지하되
message는 BFF가 정의한 고정 문구를 사용하며 내부 원문·SQL·예외·임의 추가 필드는 버린다.

| HTTP | code | next_action |
|---|---|---|
| 400 | INVALID_REQUEST, INVALID_PROJECT_NAME, INVALID_PROJECT_DESCRIPTION, INVALID_FILE_TITLE, INVALID_FILE_LOCATION, INVALID_RELATION_TARGET | NONE |
| 404 | PROJECT_NOT_FOUND, FILE_NOT_FOUND, VERSION_NOT_FOUND, NOT_FOUND | NONE |
| 409 | PROJECT_NAME_TAKEN, PROJECT_NOT_TRASHED, FILE_TITLE_TAKEN, FILE_NOT_TRASHED, DOCUMENT_LOCKED | NONE |
| 409 | DOCUMENT_CONFLICT | NONE |
| 500 | INTERNAL_ERROR(알려진 Content 계약) | NONE |
| 503 | CONTENT_UNAVAILABLE(연결 실패·읽기 시간 초과) | RETRY_LATER |
| 502 | UPSTREAM_INVALID_RESPONSE(알 수 없는 오류·상태 불일치·잘못된 본문·내부 사용자 컨텍스트 오류) | NONE |

DOCUMENT_CONFLICT에는 current와 base를 명시적 DTO로 변환해 포함한다. current는 문서 Content,
base는 Snapshot 또는 null이다. 추가 GET으로 이를 재구성하지 않는다. 잘못된 충돌 데이터는
일반 409로 축소하거나 성공으로 전달하지 않고 UPSTREAM_INVALID_RESPONSE로 처리한다.

Content의 USER_CONTEXT_REQUIRED는 BFF의 사용자 전달 결함일 수 있으므로 브라우저 재로그인
오류로 바꾸지 않는다. 알 수 없는 status/code/next_action 조합도 외부로 그대로 전달하지 않는다.
성공 응답의 필수 필드·상태·JSON 타입을 확인하고 잘못된 결과는 502로 처리한다.
응답을 받지 못한 변경 요청은 미실행으로 단정하지 않는다. RETRY_LATER는 자동 재실행 지시가 아니다.

## 외부 의존성과 적용 순서

- Content·프론트의 실제 26개 API와 저장 헤더는 구조 전환에서 유지한다.
- 프론트의 credentials, CSRF, Google 로그인 결과, 세션 오류·인증 전환 조율과 개발 신원 제거는
  별도 연동 작업이다. 프론트가 준비되지 않아도 임시 신원을 최종 인증으로 허용하지 않는다.
- 프론트 오류 매핑에는 CONTENT_UNAVAILABLE, UPSTREAM_INVALID_RESPONSE 및 인증·세션 오류가
  추가로 필요하다. 기존 네트워크 오류를 무조건 재로그인 조건으로 사용하지 않는다.
- 배포는 각 중간 커밋의 안전성과 프론트·Auth·인프라 준비 상태를 별도로 확인한다.

## 검증 기준

26개 경로·메서드의 입력과 성공·업무 오류, 204·201, nullable 필드, 충돌 current/base,
문서 저장 헤더와 URI 인코딩을 검증한다. 임의 하위 경로와 HTTP 메서드·추가 요청 필드,
내부 민감 필드 노출·잘못된 응답·통신 실패 및 하위 전송 계층의 자동 재시도 부재를 확인한다.
