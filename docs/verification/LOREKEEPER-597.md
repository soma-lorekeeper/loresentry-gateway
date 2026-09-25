# LOREKEEPER-597 검증 기록

Redis 7.4와 Valkey 9.0.6에서 실제 Auth·두 BFF를 연결한 경쟁·만료 검증을 통과했다.

- 독립 HTTP 연결을 함께 시작한 로그인에서 현재 세션은 하나다. 같은 ID의 동시
  활동은 두 요청 모두 성공하고 인덱스의 절대 만료가 일치한다.
- BFF 사전 GET의 실제 응답을 프록시에서 보관한 동안 다른 BFF에서 새 로그인 또는
  폐기를 완료했다. 보관한 응답을 반환해도 기존 ID는 거절되고 삭제한 키는 재생성되지 않는다.
- 두 인스턴스 역할을 바꿔 활동 뒤 교체·폐기, 교체 뒤 이전 폐기와 그 반대 순서를 검사했다.
- TTL 불일치·만료 없음·미지원 스키마는 두 BFF에서 503, 쿠키와 만료 변경 없음이다.
- 실제 저장소의 같은 Lua 시각 안에서 만료를 +1/0/-1ms로 옮겨 제품 검증 스크립트를
  실행했다. 직전에는 연장, 경계와 이후에는 INVALID이며 이후 두 BFF도 401을 반환한다.
- 공개 health·preflight는 비연장이고 인증된 계정 업무 오류는 쿠키와 14일 수명을 연장한다.

`python3 integration/session/run.py --redis-image <image>`로 각각 실행했다.
Auth `980a27e4935cdc6f7bc1e15940368842dd15295f`, Gateway `d0a5518` 기준에
이번 테스트 변경을 적용했다. 제품 서버 코드는 변경하지 않았다.
보고서는 `/tmp/bff-auth-integration-arcbe8pt/report.json`(Redis)과
`/tmp/bff-auth-integration-ryj7c288/report.json`(Valkey)에 있다.
브라우저 경합과 운영 시계·클러스터 검증은 별도다.
