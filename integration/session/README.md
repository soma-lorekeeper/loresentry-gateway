# Auth·BFF 세션 통합 실행

`run.py`는 격리 PostgreSQL 18.4, Redis 7.4 또는 Valkey 9.0.6, 실제 Auth와 BFF 두 프로세스를
빌드·기동하고 로그인, 계정 조회, 새 로그인에 의한 교체와 로그아웃을 검사한다.
외부 Google 응답만 테스트 HTTP/JWK 서버로 대체한다. Auth의 OIDC 서명·nonce·PKCE,
계정 DB와 세션 처리는 실제 코드를 사용한다. 세션 ID의 정규 형식과 해시 인덱스,
양쪽 절대 만료 일치, 14일 활동 연장과 쿠키 Max-Age도 비교한다. 실제 브라우저·운영 검증은 별도다.

Linux 호스트의 Docker, Python 3와 Auth Git 저장소가 필요하다. Java 21과 Gradle은
컨테이너에서 실행한다. 기본 Auth 기준은 LOREKEEPER-590 커밋
`980a27e4935cdc6f7bc1e15940368842dd15295f`이며 원본 작업 트리는 변경하지 않는다.

```bash
python3 integration/session/run.py --auth-source ../loresentry-authentication
# 다른 확정 커밋을 검증할 때 지정한다.
python3 integration/session/run.py --auth-source ../loresentry-authentication --auth-ref <commit>
# 실제 Content의 26개 API 회귀를 추가한다.
# 저장소는 --redis-image redis:7.4-alpine 또는 valkey/valkey:9.0.6-alpine으로 선택한다.
python3 integration/session/run.py --content-source ../loresentry-content
```

Auth는 git archive 복사본에 GoogleFixture를 추가한다. BFF는 현재 작업 트리를
빌드하므로 변경사항을 포함한다. 보고서의 Git 기준 커밋과 JAR 해시를 함께 확인한다.
Content 기본 커밋은 `d26a3d3a244bdebb79375290b9d032f232da5563`이며
`--content-ref`로 바꿀 수 있다. Content는 별도 격리 DB를 사용한다.

서비스 JWT 키는 생성하거나 주입하지 않는다. Google 검증용 키만 fixture가 생성한다.
DB·Valkey·Java 포트는 loopback에 노출한다. BFF 계정은 세션 키의 GET과 Lua 검증·TTL
연장 명령만 허용한다. 기본 Redis 관리자는 테스트 준비와 장애 주입용이다.
운영 ACL은 [운영 문서](../../docs/OPERATIONS.md)에 따라 별도로 구성한다.

실행이 만든 컨테이너와 익명 볼륨, 환경 파일은 finally에서 정리한다.
`/tmp/bff-auth-integration-*`에는 소유자만 접근 가능한 로그와 비밀값 없는
report.json을 남긴다. 실제 Google 동의 화면, 브라우저 쿠키 정책, 운영 접근 제한과
이미지/S3 기능을 검증하는 도구는 아니다.

## 경쟁과 만료 경계

BFF의 저장소 연결은 테스트 전용 loopback RESP 프록시를 통과한다. 사전 GET 응답을
보관한 동안 다른 BFF가 로그인·폐기를 완료하도록 제어해 오래된 조회를 재현한다.
독립 HTTP 연결을 장벽에서 함께 시작해 동시 로그인과 같은 ID의 활동도 검사한다.

만료 직전·정확한 경계·직후는 실제 저장소 Lua의 동일 시각 안에서 만료를 ±1ms로
조정한 뒤 제품 검증 스크립트를 실행한다. 만료 경계에서 서버 시계를 변경하거나
14일을 대기하지 않는다. 공개 경로·preflight 비연장, 업무 오류의 연장과 손상 레코드
거절은 실제 두 BFF의 HTTP 응답으로 검사한다.
