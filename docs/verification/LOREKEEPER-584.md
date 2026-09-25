# LOREKEEPER-584 검증 기록

BFF는 세션 ID 형식 검증과 사전 GET 뒤 Lua에서 두 인덱스·현재 해시·TTL을 다시 검사한다.
성공한 경우에만 Redis 시각부터 14일로 두 만료를 연장하고 사용자와 만료 시각을 반환한다.
연결·GET·EVAL의 전체 예산은 500ms이며 저장소 오류와 연장 결과 불명은 인증 실패다.

Java 21에서 단위 테스트 3개와 Redis 7.4·Valkey 9.0.6 통합 테스트 12개가 통과했다.

- 정규 ID 입력, 서버 기준 수명·양쪽 절대 만료 일치, 오래된 생성 시각에도 활동 연장.
- 조회 후 새 로그인·폐기, 같은 ID의 동시 활동, 삭제된 키 비재생성.
- 부재·만료·현재 해시 불일치와 손상·중복 필드·만료 없음·TTL 불일치의 오류 구분.
- 전용 ACL로 GET/EVAL/TIME/PTTL/PEXPIREAT와 연결 초기화만 허용하고 생성·삭제·OAuth 조회 거절.
- 연결 지연의 전체 500ms 제한, 늦은 연결의 비연장, EVAL 응답 유실 시 거절·비재시도.

```bash
TEST_REDIS_PORT=<격리 Redis 포트> TEST_VALKEY_PORT=<격리 Valkey 포트> ./gradlew --no-daemon --max-workers=2 test --tests '*OpaqueSessionVerifierTest' --tests '*OpaqueSessionReaderTest' sessionIntegrationTest --tests '*OpaqueSessionIntegrationTest'
```

첫 테스트 설정에서 Lettuce ACL API와 하위 명령 직렬화 차이를 수정한 뒤 위 테스트가 통과했다.
HTTP 필터와 쿠키 연결은 후속 Atomic에서 수행한다. 운영 ACL·부하와 실제 브라우저 검증은 별도 Work 범위다.
