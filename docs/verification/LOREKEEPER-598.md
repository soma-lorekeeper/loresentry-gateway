# LOREKEEPER-598 검증 기록

Redis 7.4와 Valkey 9.0.6에서 실제 Auth·BFF 드라이버의 장애 시나리오를 통과했다.

- BFF AUTH·GET·EVAL 응답 지연과 EVAL 전/후 연결 종료는 503, 쿠키 갱신과 내부 호출 없음이다.
  지연 요청은 500ms 예산에 HTTP·스케줄링 여유를 둔 900ms 안에 끝났다.
- 응답 유실·연결 종료 뒤 EVAL 횟수는 한 번이고, 독립된 후속 요청은 새 연결로 성공했다.
- 실제 전용 BFF 자격 증명은 SET·DEL·OAuth 키 조회를 거절했다. PEXPIREAT 권한을
  제거하면 두 BFF 모두 실패하며 연장하지 않는다.
- Lua 첫 연장 뒤 오류는 TTL 불일치로 거절한다. 로그인 첫 SET 뒤 오류는 고아 ID를
  만들지만 현재 인덱스를 교체하지 않아 새 ID는 거절하고 기존 로그인은 유지한다.
- Auth 로그인 EVAL 응답 유실 뒤 재연결해도 EVAL을 재실행하거나 성공 쿠키를 발행하지 않는다.
- Auth 폐기의 GET 응답을 연속 지연하면 2초 예산(HTTP 여유 300ms) 안에 미확인 결과와
  삭제 쿠키를 반환한다. BFF의 Auth HTTP 호출은 한 번이고, 늦은 GET은 삭제를 시작하지 않는다.
- 발행한 원문 세션 ID와 fixture 비밀값은 오류 본문·Auth/BFF stdout/stderr에 노출되지 않았다.

`run.py --redis-image redis:7.4-alpine`과 `--redis-image valkey/valkey:9.0.6-alpine`을 실행했다.
Auth `980a27e4935cdc6f7bc1e15940368842dd15295f`, Gateway `c1c271d`에 테스트 변경을 적용했다.
보고서: `/tmp/bff-auth-integration-vtjzs3lg/report.json`,
`/tmp/bff-auth-integration-grc4atkw/report.json`.
BFF `build sessionIntegrationTest`도 성공했다(제품 코드가 같아 Gradle 검증 결과 재사용).
기존 실제 Lettuce ACL 테스트와 이번 HTTP 장애 검증을 함께 사용한다.

운영 TLS·ACL·CNI와 부하 측정은 이 결과로 대체하지 않는다.
