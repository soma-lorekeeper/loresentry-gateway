# 활성 세션 조회 검증

2026-09-24, LOREKEEPER-562. AT 검증 후 쓰기 담당 endpoint에서 검증된 sub의
`auth:session:{userId}`를 한 번 GET한다. 요청마다 연결하며 허용 캐시·복제본·연결 풀과
재접속·재전송을 사용하지 않는다. 연결 초기화와 GET에 하나의 500ms 예산을 적용한다.
요청별 연결의 비용은 후속 실제 환경 지연 측정 대상이며 허용 결과를 캐시하지 않는다.

## 실행 결과

Java 21 `./gradlew --no-daemon build sessionIntegrationTest` 성공.
일반 테스트 145개와 Redis/Valkey 통합 테스트 10개 모두 실패·오류·생략 0개.
실제 버전은 Redis 7.4.11(`redis:7.4`), Valkey 9.0.6이다.

- 동일 sid에서 refresh_jti가 회전해도 기존 미만료 AT의 세션 검사를 통과했다.
  새 sid로 교체하면 기존 AT의 세션 검사를 거절했다.
- 두 요청의 GET 통계 증가가 정확히 2회였고 TTL을 연장하지 않았다.
- 부재·만료·sid 불일치는 SESSION_INVALID, 연결·ACL·레코드 손상·미지원 버전은
  SESSION_UNAVAILABLE로 차단한다. 중복 JSON 필드와 잘못된 필드 타입도 거절한다.
- BFF 전용 계정으로 세션 GET과 연결 초기화를 수행했다. SET·DEL·GETDEL·EXPIRE·
  EVAL·KEYS와 OAuth·이전 RT 키 GET은 거절됐다.
- CLIENT PAUSE로 연결 초기화를 지연시켰을 때 500ms 예산 안에서 기다린 뒤 차단했다.
  측정 허용 범위는 400–750ms이며, 서버 복구 후 늦은 GET이나 재시도는 없었다.
- 실제 보안 필터에서 세션 실패 시 컨트롤러 호출과 Set-Cookie가 없음을 확인했다.

## 재현과 범위

`src/test/resources/session-redis.conf`는 루프백 포트로 노출하는 격리 테스트 전용 설정이다.
관리 계정이 비밀번호 없이 열려 있으므로 운영 설정으로 사용하지 않는다.
각 버전 컨테이너에 이 파일을 설정으로 마운트하고 포트를 `TEST_REDIS_PORT`,
`TEST_VALKEY_PORT`에 지정한다. Gradle 프로세스가 해당 루프백 포트에 접근할 수 있어야 한다.
일반 `build`는 redis 태그를 제외하며 `sessionIntegrationTest`가 두 실제 서버를 검증한다.

Lettuce의 연결·명령 시간 제한과 재접속 옵션은
[공식 Client Options](https://redis.github.io/lettuce/advanced-usage/client-options/)를 기준으로 설정했다.
운영 Redis endpoint가 실제 쓰기 담당 노드인지, 운영 ACL·자격 증명·네트워크가 같은 제한을
적용하는지는 이 테스트의 확인 범위가 아니다. LOREKEEPER-576에서 별도 증거가 필요하다.
