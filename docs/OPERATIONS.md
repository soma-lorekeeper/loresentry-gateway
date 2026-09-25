# BFF 운영 준비와 검증 인계

2026-09-26 단일 세션 ID 방식의 목표 운영 계약이다. 코드·GitOps·ACL·클러스터에
반영하지 않았으며 운영 준비 완료를 뜻하지 않는다. 이전 조사 결과는
[2026-09-24 운영 기록](verification/OPERATIONS-2026-09-24.md)에 보존한다.

## 설정과 Secret

| 설정 | 요구 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | local 또는 prod 중 하나 |
| `BFF_SESSION_REDIS_HOST`, `BFF_SESSION_REDIS_PORT` | Auth와 같은 쓰기 담당 endpoint |
| `BFF_SESSION_REDIS_USERNAME`, `BFF_SESSION_REDIS_PASSWORD` | 별도 BFF 계정·Secret |
| `BFF_SESSION_REDIS_TLS` | endpoint에 맞춰 명시하고 CA 신뢰 검증 |
| 브라우저·서비스 주소 | local/prod Origin·고정 로그인 결과 주소·내부 서비스 주소 |

기존 세션 연결 설정 이름을 이어 사용하는 설계이며 실제 바인딩·시작 검증은 구현해야 한다.
비활동 제한 14일은 Auth와 BFF에 같은 값으로 적용한다. Auth에는 별도 DB·Redis·Google
설정을 주입하고 Google secret을 BFF에 공유하지 않는다. 비밀값은 Git·로그·검증 출력에 넣지 않는다.
운영 Service 포트는 80, 대상 컨테이너 포트는 8000이며 환경에 맞는 실제 연결로 확인한다.

## Redis ACL과 연결

BFF는 조회뿐 아니라 활동 만료를 연장한다. 새 키 패턴은
`auth:session:{login}:by-id:*`, `auth:session:{login}:by-user:*`다.

| 계정 | 데이터 명령 |
|---|---|
| BFF | `GET`, `EVAL`, `TIME`, `PTTL`, `PEXPIREAT` |
| Auth 세션 | 생성·교체·폐기 스크립트의 `EVAL`, `TIME`, `GET`, `PTTL`, `SET`, `DEL` |
| Auth OAuth | 별도 `auth:oauth:*`의 `SET NX`, `GET`, `GETDEL` |

BFF의 SET·DEL·GETDEL·키 탐색·관리·OAuth 키 접근을 거절한다. `+@read`나 `+@write` 전체를
허용하지 않는다. 연결 초기화의 AUTH·HELLO·PING·CLIENT SETINFO/SETNAME 등 실제 필요한
명령을 드라이버로 확인해 추가한다. 필요하지 않은 EVALSHA·SCRIPT LOAD는 추가하지 않는다.

ACL만으로 특정 Lua 본문이나 14일 상한을 강제할 수 없다. BFF는 허용 키의 TTL을 변경할
권한을 갖는다. 이 신뢰 경계와 보안 검토는 [세션 계약](../../loresentry-authentication/docs/session/SESSION_DESIGN.md#오류와-권한)을 따른다.
검증되지 않은 ACL 문자열을 운영 적용 완료값으로 취급하지 않는다.

사전 GET과 원자적 검증·연장의 전체 예산은 연결 포함 500ms다. 쓰기 담당 노드와 실제
페일오버 라우팅을 확인하고 허용 캐시·복제본·자동 재시도를 사용하지 않는다.
매 인증 요청의 TTL 쓰기 부하, 두 인덱스와 이전 로그인 인덱스의 메모리, Redis·BFF 시각
차이, 쿠키 수명과 서버 TTL의 일치를 측정한다. 고정 hash tag로 한 슬롯에 모이는 한계도 확인한다.

## 내부 접근 제한

ClusterIP·Ingress 부재만으로 내부 우회가 차단됐다고 판단하지 않는다.
BFF가 사용자 신원을 전달하므로 Auth·Content에 직접 위조 헤더를 보내는 경로를 차단해야 한다.

| 경로 | 결과 |
|---|---|
| ALB → BFF | 허용 |
| BFF → Auth·Content 등 명시적 내부 API | 허용 |
| 비허용 Pod·namespace → Auth·Content | 거절 |
| Auth·BFF → 세션 저장소 | 각각 별도 ACL로 허용 |
| 그 밖의 워크로드 → 세션 저장소 | 거절 |
| DNS·Google·각 서비스 DB | 필요한 경로만 유지 |

정책 manifest·CNI 집행과 실제 허용·거절 호출을 확인한다. loopback 통합 테스트는 운영
NetworkPolicy·TLS·자격 증명의 증거가 아니다.

## 관측과 배포 판정

세션 검증·연장 지연 p50/p95/p99, 500ms 초과, 세션 무효·장애, 로그인 생성 실패,
폐기 미확인, Redis 쓰기량과 메모리를 관측한다. Cookie·Authorization·세션 ID·OAuth
code/state·Redis 원문·비밀번호를 로그에 남기지 않는다. 사용자 UUID·세션 해시를 메트릭 라벨로 쓰지 않는다.

기존 일반·통합 테스트 결과는 새 ACL·연장 연산의 검증을 대신하지 않는다.
코드·프론트·브라우저와 실제 운영 설정을 검증한 후 [공동 전환](ROLLOUT.md)을 수행한다.
main push가 자동 배포를 실행하므로 문서 변경 완료를 배포 승인이나 준비 완료로 해석하지 않는다.
