# Content API 연동 조사

2026-09-24, LOREKEEPER-550. 원격 `main`의 Content `4158e29`, 프론트 `afa4fde`와
BFF `7ead2eb`의 코드를 비교했다. BFF 문서 이동 커밋 `483e2e5`는 별도로 보존한다.
이 문서는 구현·배포 완료 기록이 아니라 기존 기능을 보존하기 위한 조사 결과다.

이후 Content `d26a3d3`에서 last_file 갱신과 이미지 API가 추가됐다. 기존 26개 API의
최신 연동 결과와 별도 연동이 필요한 이미지 범위는
[LOREKEEPER-574 검증 기록](verification/LOREKEEPER-574.md)을 따른다. 아래 표는 최초 조사 시점의 기록이다.

## 확인한 근거

- [Content 컨트롤러·DTO 원본](https://github.com/soma-lorekeeper/loresentry-content/tree/4158e2913c50da0e467f519e35fb6f4d4bd4fbaf/src/main/java/com/loresentry/content): `project/ProjectController`, `file/FileController`, `file/EpisodeController`, `document/DocumentController`, `document/VersionController`, `search/SearchController`.
- 같은 소스의 `ProjectResponse`, `FileResponses`, `DocumentResponses`, `DocumentSnapshot`, `SearchResponses`, `web/ErrorResponses`, `ContentExceptionHandler`.
- [프론트 API 어댑터 원본](https://github.com/soma-lorekeeper/loresentry-frontend/tree/afa4fde071cd4ad843f67f95fe59531932b133b9/src/services/api): `projects.ts`, `files.ts`, `documents.ts`, `search.ts`, `http.ts`, `identity.ts`, `errors.ts`.
- BFF의 `ContentRelayController`, `UpstreamClient`와 관련 테스트.

## 경로·요청·응답 대응표

모든 ID는 UUID다. JSON 이름은 snake_case다. `P`는 Project, `D`는 Document,
`C`는 문서 Content, `E`는 Episode, `V`는 Version 응답을 뜻한다.
본문이 없는 요청은 `—`, 응답이 없는 성공은 `204`로 표시한다.

| 메서드·경로 | 요청 | 성공 | 프론트 호출 |
|---|---|---|---|
| GET /projects | — | 200 `{projects: P[]}` | projects.list |
| GET /projects/trash | — | 200 `{projects: P[]}` | projects.listTrash |
| POST /projects | name, description | 201 P, Location | projects.create |
| GET /projects/{id} | — | 200 P | projects.get, getSettings |
| PATCH /projects/{id} | name?, description? | 200 P | projects.rename, saveSettings |
| POST /projects/{id}/trash | — | 204 | projects.moveToTrash |
| POST /projects/{id}/restore | — | 200 P | projects.restore |
| DELETE /projects/{id} | — | 204 | projects.deletePermanently |
| GET /projects/{id}/files | — | 200 folders, episodes, documents | files.tree |
| GET /projects/{id}/files/trash | — | 200 `{files: TrashEntry[]}` | files.listTrash |
| POST /projects/{id}/files | kind, title, folder_code?, episode_id? | 201 D 또는 E | files.create |
| PATCH /files/{id} | title | 200 D | files.rename |
| PATCH /files/{id}/position | folder_code, episode_id?, before_file_id? | 200 D | files.move |
| POST /files/{id}/trash | — | 204 | files.moveToTrash |
| POST /files/{id}/restore | — | 200 D | files.restore |
| DELETE /files/{id} | — | 204 | files.deletePermanently |
| PATCH /episodes/{id} | title | 200 E | files.rename |
| DELETE /episodes/{id} | — | 204 | files.deleteEpisode |
| GET /files/{id}/content | — | 200 C | documents.get |
| PUT /files/{id}/content | Snapshot, If-Match, X-Save-Id? | 200 C | documents.save |
| PUT /files/{id}/lock | locked | 200 C | documents.setLocked |
| GET /files/{id}/versions | — | 200 `{versions: V[]}` | versions.list |
| POST /files/{id}/versions | label?, 본문 생략 가능 | 201 V | versions.saveNamed |
| POST /files/{id}/versions/{vid}/restore | If-Match | 200 C | versions.restore |
| DELETE /files/{id}/versions/{vid} | — | 204 | versions.remove |
| GET /projects/{id}/search | q 쿼리, 생략 가능 | 200 `{hits: Hit[]}` | search.search |

## 응답 데이터

- P: id, name, description, last_worked_at, trashed_at, created_at, last_file. 현재 last_file은 null이다. 프론트는 향후 `{id,title}`도 받을 수 있게 정의돼 있다.
- D: id, title, folder_code, episode_id, rank, locked, char_count, revision_no, trashed_at, updated_at.
- E: id, name, rank. 생성 요청의 title이 응답에서는 name이다.
- 트리: folders는 code/name/position, episodes는 E 목록, documents는 D 목록이다. 트리 조립은 프론트 책임이다.
- TrashEntry: id, title, folder_code, episode_name, trashed_at.
- Snapshot: title, body_md, properties(`{key,value}` 목록), relations(`{relation_key,target_document_id}` 목록).
- C: id, project_id, title, folder_code, episode_id, body_md, properties, relations, locked, char_count, revision_no, updated_at.
- V: id, file_id, kind, label, source_revision_no, created_at, snapshot. 프론트는 RESTORE를 PRE_RESTORE로 변환한다.
- Hit: file_id, title, folder_code, episode_name, snippet(`before,match,after` 또는 null), updated_at.

## 반드시 유지할 동작

- 문서 저장과 버전 복원은 If-Match를 요구한다. 프론트는 revision_no를 따옴표로 감싸 보낸다. 서버는 이 값으로 경쟁을 판단한다.
- X-Save-Id는 문서 저장의 선택적 UUID 멱등 키다. 현재 프론트 saveId 옵션을 그대로 전달한다. BFF의 자동 재시도를 허용하는 근거로 사용하지 않는다.
- DOCUMENT_CONFLICT는 409와 code/message/next_action 외에 current(C), base(Snapshot 또는 null)를 함께 반환한다. 프론트의 3-way 병합에 둘 다 필요하다.
- PROJECT_NAME_TAKEN, FILE_TITLE_TAKEN, DOCUMENT_LOCKED 등은 프론트가 code로 구분한다. message 원문에는 의존하지 않는다.
- 프로젝트 생성 Location을 유지한다. 현재 프론트가 이를 읽지는 않지만 기존 외부 응답 계약이다. 내부 호스트 주소를 그대로 노출하지 않는다.
- GET 검색 q의 한글·공백·예약 문자 의미를 유지하고 이중 인코딩하지 않는다.
- 204 응답은 본문 없이 반환한다. nullable JSON 필드를 임의로 생략하지 않는다.

## 임시 구현과 정합성 차이

| 항목 | 조사 결과 | 전환 방향 |
|---|---|---|
| 신원 | 프론트 identity.ts가 localStorage에 개발 UUID를 만들고 http.ts가 X-User-Id로 전송한다. BFF는 형식만 검사한다. | 단일 세션 ID 검증으로 대체하고 프론트의 개발 신원 제거를 별도 의존성으로 명시한다. |
| 브라우저 인증 | 현재 fetch에는 credentials: include, X-LS-CSRF, 새 인증 계약과 탭 간 전환 조율이 없다. | 보안 BFF의 실제 브라우저 연동 전에 프론트 준비가 필요하다. |
| 헤더 | If-Match와 X-Save-Id는 실제 소비된다. If-None-Match는 BFF 허용 목록에만 있고 현재 Content·프론트에 소비 구현이 없다. | 실제 저장 헤더는 보존한다. If-None-Match 전달을 유지해도 304/ETag 지원이라고 주장하지 않는다. |
| 응답 | 현재 BFF는 byte[] 원문을 전달한다. | 내부·외부 DTO와 명시적 오류 변환을 적용하되 소비 필드를 보존한다. |
| 라우팅 | namespace 하위 임의 경로를 중계한다. | 위 26개 경로·메서드를 명시하고 미지원 API를 새 기능처럼 노출하지 않는다. |
| README | Content README의 gateway 미중계 설명은 현재 BFF 코드와 다르다. | 구현 사실은 코드로 판단하고 동료 저장소를 임의 수정하지 않는다. |
| 미지원 기능 | 즐겨찾기는 프론트 localStorage, 일반 폴더·섹션·서버 export는 미지원이다. | 신규 Content 기능을 BFF에서 구현하지 않는다. |

Content의 소유권·이름·휴지통·잠금·순위·경쟁 검증은 해당 서비스 책임이다.
기존 테스트의 사용자 헤더 신뢰와 원문 응답 기대값은 최종 계약의 근거로 삼지 않는다.

## 검증 기록

위 여섯 컨트롤러의 26개 메서드·경로를 프론트 어댑터의 호출과 직접 대조했다.
요청 DTO와 응답 DTO, 오류 처리, 문서 저장 헤더의 수신·발신 지점을 확인했다.
서버 실행·브라우저 E2E·운영 검증은 이번 조사에서 수행하지 않았다.
