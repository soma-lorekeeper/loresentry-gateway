# BFF 제공 API — Content

> **책임:** 브라우저가 BFF에 요청하는 Content API의 입력·응답·헤더·오류를 정한다.
>
> **제공자·호출자:** BFF가 제공하고 프론트엔드가 호출한다. Content 서버의 제공 명세가 아니다.
>
> **확인할 때:** 브라우저의 Content 요청·응답과 저장 충돌 처리를 구현할 때.
>
> **관련 기준:** 제공 API의 진입점은 [API.md](API.md), Content 서버 호출·DTO 변환은 [API_CALLS.md](API_CALLS.md#content-호출)를 본다.

BFF가 제공하는 Content API의 경로·요청·응답과 오류 계약이다.
Content의 도메인 규칙과 프론트의 화면 모델을 변경하지 않는다.

## API 목록

모든 ID는 UUID이며 JSON 이름은 snake_case다. 요청의 `?`는 선택 항목이고,
`—`는 요청 본문이 없음을 뜻한다. 응답 객체의 필드는 아래 응답 데이터에서 정의한다.

| 메서드·경로 | 요청 | 성공 |
|---|---|---|
| GET /projects | — | 200 `{projects: Project[]}` |
| GET /projects/trash | — | 200 `{projects: Project[]}` |
| POST /projects | name, description | 201 Project, Location |
| POST /projects/sample | — | 201 Project, Location |
| GET /projects/{id} | — | 200 Project |
| PATCH /projects/{id} | name?, description? | 200 Project |
| POST /projects/{id}/trash | — | 204 |
| POST /projects/{id}/restore | — | 200 Project |
| DELETE /projects/{id} | — | 204 |
| GET /projects/{id}/files | — | 200 folders, episodes, documents |
| GET /projects/{id}/files/trash | — | 200 `{files: TrashEntry[]}` |
| POST /projects/{id}/files | kind, title, folder_code?, episode_id? | 201 Document 또는 Episode |
| PATCH /files/{id} | title | 200 Document |
| PATCH /files/{id}/position | folder_code, episode_id?, before_file_id? | 200 Document |
| POST /files/{id}/trash | — | 204 |
| POST /files/{id}/restore | — | 200 Document |
| DELETE /files/{id} | — | 204 |
| PATCH /episodes/{id} | title | 200 Episode |
| DELETE /episodes/{id} | — | 204 |
| GET /files/{id}/content | — | 200 Content |
| PUT /files/{id}/content | Snapshot, If-Match, X-Save-Id? | 200 Content |
| PUT /files/{id}/lock | locked | 200 Content |
| GET /files/{id}/versions | — | 200 `{versions: Version[]}` |
| POST /files/{id}/versions | label?, 본문 생략 가능 | 201 Version |
| POST /files/{id}/versions/{vid}/restore | If-Match | 200 Content |
| DELETE /files/{id}/versions/{vid} | — | 204 |
| GET /projects/{id}/search | q 쿼리, 생략 가능 | 200 `{hits: Hit[]}` |
| GET /projects/{id}/memos | scope 쿼리, file 이면 document_id | 200 `{memos: Memo[]}` |
| POST /projects/{id}/memos | scope, document_id?, title?, body | 201 Memo, Location `/memos/{id}` |
| PATCH /memos/{mid} | title?, body? | 200 Memo |
| DELETE /memos/{mid} | — | 204 |
| GET /projects/{id}/favorites | — | 200 `{file_ids: UUID[]}` |
| PUT /projects/{id}/favorites/{fid} | — | 200 `{file_ids}` — 매번 전체 목록 |
| DELETE /projects/{id}/favorites/{fid} | — | 200 `{file_ids}` — 매번 전체 목록 |
| GET /projects/{id}/workspace-state | — | 200 `{layout}`; 처음이면 `layout: null` |
| PUT /projects/{id}/workspace-state | layout | 204 |
| POST /projects/{id}/images | file_name?, content_type, size_bytes | 201 ImageTicket, Location |
| POST /projects/{id}/images/{iid}/complete | — | 200 Image |
| GET /projects/{id}/images/{iid} | — | 200 Image |
| POST /feedback | category, message, page?, client? | 201 `{id, created_at}`, Location 없음 |

- **샘플 프로젝트는 본문 없이 요청한다.** 예제 원고·설정 문서를 채우는 일과 이름 중복 시 번호를
  붙이는 일은 Content가 수행하며, 응답·Location·오류는 POST /projects와 같다.
- **피드백은 Content DB에 저장한다.** category는 `BUG`·`IDEA`·`OTHER`, message는 공백 제거 후 1~2000자다.
  page는 보낸 화면 경로(200자 이하), client는 user agent다. 검증과 시간당 20건 제한은 Content가 판단한다.
- **작업공간 레이아웃은 BFF 도 해석하지 않는다.** Content 처럼 불투명한 JSON 으로 통과시킨다.

## 명시적 API와 데이터 경계

위 목록의 각 경로·메서드를 컨트롤러에 선언한다. namespace 와일드카드 중계를 허용하지 않고
지원하지 않는 경로는 404, 지원하지 않는 메서드는 405로 거절한다. HEAD는 명시한 GET의
표준 HTTP 동작으로 제공하며 상태 변경을 수행하지 않는다.

계층별 DTO·데이터 경계는 [서버 구조](ARCHITECTURE.md#요청과-데이터의-경계)를 따른다.

JSON 요청은 정의된 필드와 타입만 허용한다. 알 수 없는 필드·잘못된 타입·잘못된 JSON은
400 INVALID_REQUEST/NONE으로 처리한다. 이름 길이·중복·소유권·휴지통·문서 잠금·버전 경쟁은
Content에서 판단한다. PATCH name/description의 null은 현행 Content와 같이 변경 없음이며,
설명을 비우려면 빈 문자열을 사용한다. label 요청 본문은 버전 생성에서만 생략할 수 있다.

경로 UUID 형식 오류는 현재 Content가 사용하는 404 PROJECT_NOT_FOUND/NONE을 유지한다.
검색 q는 원래 문자열의 의미를 보존해 한 번만 인코딩한다. 임의 쿼리를 통째로 전달하지 않는다.

## 인증과 헤더

모든 Content API는 [공통 인증 계약](API.md#공통-계약)을 따른다. 내부 사용자 헤더와
요청 구성은 [Content 호출](API_CALLS.md#content-호출)에서 정한다.

| 헤더 | 처리 |
|---|---|
| Content-Type | JSON 본문 요청에서 application/json을 사용한다. |
| If-Match | 문서 저장·버전 복원에서 브라우저가 revision_no를 따옴표로 감싸 보낸 값을 변경 없이 전달한다. 필수 여부·revision 판단은 Content가 수행한다. |
| X-Save-Id | 문서 저장의 선택적 UUID 멱등 키를 전달한다. BFF는 값을 새로 만들거나 실패 요청을 재전송하지 않는다. |
| If-None-Match | GET에서 선택적으로 전달한다. 현재 Content는 이를 소비하지 않으며 BFF가 304·ETag 기능을 새로 제공하지 않는다. |
| Location | 프로젝트·샘플 프로젝트 생성 시 검증된 응답 id로 상대 /projects/{id}를 구성한다. 내부 Location 원문을 복사하지 않는다. |
| Cache-Control | 보호 Content 응답에 no-store를 적용한다. 임의 내부 캐시 정책을 복사하지 않는다. |

전용 요청 헤더가 중복되면 모호한 값을 선택하지 않고 400 INVALID_REQUEST로 거절한다.
브라우저의 인증 쿠키와 세션 ID는 도메인 호출에 전달하지 않는다.

CORS의 허용·노출 헤더와 CSRF 검사 순서는 [브라우저 보안](BROWSER_SECURITY.md)을 따른다.

## 성공 응답

API 목록의 상태 코드·DTO·nullable 필드를 유지한다. null 필드를 임의로 생략하지 않는다.
프로젝트 생성과 샘플 프로젝트 생성은 201과 상대 Location, 파일·에피소드 생성·버전 생성·피드백 생성은 201이다.
휴지통 이동·영구 삭제·에피소드 삭제·버전 삭제는 204이며 본문이 없다.
그 밖의 표에 정의된 결과는 200이다.

프로젝트 last_file은 null 또는 최근 수정된 활성 문서의 id/title이며 외부 DTO로 변환한다.
파일 생성은 요청 kind에 따라 문서와 에피소드 내부 DTO를 구분한다. 트리 응답은
folders·episodes·documents의 정규화된 목록으로 유지한다.

## 응답 데이터

- FeedbackCreated: id, created_at.
- Memo: id, project_id, scope(`project`·`file`), document_id, title, body, created_at, updated_at.
- ImageTicket: image_id, key, upload_url, method, headers, expires_at, public_url.
- Image: image_id, project_id, file_name, key, content_type, size_bytes, status(`PENDING`·`COMMITTED`),
  public_url(`COMMITTED` 에서만), created_at, committed_at.
- Project: id, name, description, last_worked_at, trashed_at, created_at, last_file. last_file은 null 또는 최근 수정된 활성 문서의 `{id, title}`이다.
- Document: id, title, folder_code, episode_id, rank, locked, char_count, revision_no, trashed_at, updated_at.
- Episode: id, name, rank. 생성 요청의 title이 응답에서는 name이다.
- 트리: folders는 code/name/position, episodes는 Episode 목록, documents는 Document 목록이다. 트리 조립은 프론트 책임이다.
- TrashEntry: id, title, folder_code, episode_name, trashed_at.
- Snapshot: title, body_md, properties(`{key,value}` 목록), relations(`{relation_key,target_document_id}` 목록).
- Content: id, project_id, title, folder_code, episode_id, body_md, properties, relations, locked, char_count, revision_no, updated_at.
- Version: id, file_id, kind, label, source_revision_no, created_at, snapshot.
- Hit: file_id, title, folder_code, episode_name, snippet(`before,match,after` 또는 null), updated_at.

## 오류 변환

일반 오류는 code·message·next_action이다. 알려진 오류의 상태와 의미를 유지하되
message는 BFF가 정의한 고정 문구를 사용하며 내부 원문·SQL·예외·임의 추가 필드는 버린다.

| HTTP | code | next_action |
|---|---|---|
| 400 | INVALID_REQUEST, INVALID_PROJECT_NAME, INVALID_PROJECT_DESCRIPTION, INVALID_FILE_TITLE, INVALID_FILE_LOCATION, INVALID_RELATION_TARGET, INVALID_MEMO, INVALID_UPLOAD_REQUEST, INVALID_FEEDBACK | NONE |
| 404 | PROJECT_NOT_FOUND, FILE_NOT_FOUND, VERSION_NOT_FOUND, NOT_FOUND, MEMO_NOT_FOUND, IMAGE_NOT_FOUND | NONE |
| 409 | PROJECT_NAME_TAKEN, PROJECT_NOT_TRASHED, FILE_TITLE_TAKEN, FILE_NOT_TRASHED, DOCUMENT_LOCKED, OBJECT_NOT_UPLOADED | NONE |
| 409 | DOCUMENT_CONFLICT | NONE |
| 429 | FEEDBACK_RATE_LIMITED | RETRY_LATER |
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

## 검증 기준

40개 경로·메서드의 입력과 성공·업무 오류, 204·201, nullable 필드, 충돌 current/base,
문서 저장 헤더와 URI 인코딩을 검증한다. 임의 하위 경로와 HTTP 메서드·추가 요청 필드,
내부 민감 필드 노출·잘못된 응답·통신 실패 및 하위 전송 계층의 자동 재시도 부재를 확인한다.
