# LOREKEEPER-596 검증 기록

실제 Auth, BFF 두 인스턴스와 Content를 격리 PostgreSQL 18.4 및 Redis 7.4,
Valkey 9.0.6에 각각 연결해 동일한 시나리오를 통과했다.

- Google HTTP/JWK fixture를 통한 실제 OIDC 검증, 신규 계정과 로그인.
- 정규 256-bit ID, 해시만 저장한 인덱스와 계정 ID 일치.
- 두 BFF의 users/me와 계정 수정, 동일 ID 유지, 두 절대 만료 일치와 14일 연장.
- 응답 쿠키 Max-Age와 서버 잔여 수명 비교, HttpOnly/Strict/Path와 no-store.
- 이전 ID의 TTL이 남아 있어도 새 로그인 뒤 거절, 이전 폐기가 새 로그인에 영향 없음.
- 현재 폐기 시 인덱스 삭제와 두 BFF의 거절. 구 토큰 경로 호출 없음.
- Content 26개 API의 권한, 조건부·중복 저장, 업무 오류의 쿠키 연장,
  버전·휴지통·한글 검색·Location/CORS 계약 유지.

기준 Auth: `980a27e4935cdc6f7bc1e15940368842dd15295f`.
Content: `d26a3d3a244bdebb79375290b9d032f232da5563`.
Gateway 기준: `fa57060447d4a6ad021dc89635dc2e031bd10e42`에 이 Atomic의 러너 변경 적용.
Gateway JAR SHA256: `5383dca294992003d3a093e560e11bf12b3edc7349cb38fe0698b5cdb3b79c0c`.

```bash
python3 integration/session/run.py --content-source <content-checkout> --redis-image redis:7.4-alpine
python3 integration/session/run.py --content-source <content-checkout> --redis-image valkey/valkey:9.0.6-alpine
```

실행 보고서는 Linux 호스트의 `/tmp/bff-auth-integration-dt38oc1b/report.json`과
`/tmp/bff-auth-integration-wbt39zt4/report.json`에 있다. 컨테이너·볼륨·환경 파일은
실행 종료 후 정리했다. 실제 Google 동의 화면, 브라우저와 운영 환경은 이 검증에 포함하지 않는다.
