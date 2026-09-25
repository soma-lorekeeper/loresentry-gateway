# BFF·Auth 단일 세션 통합 검증

> 이전 구현의 실행 기록이다. 2026-09-26 단일 세션 ID 설계의 구현·검증 완료 근거로 사용하지 않는다. 당시 결과와 수치는 보존한다.

2026-09-24, LOREKEEPER-573. 실제 Auth의 OIDC 검증·계정 DB·JWT·세션 처리와 BFF 두
인스턴스를 연결한 자동화 시나리오를 통과했다. Auth 기준은
`e9d5b5b35dace0b9c7066ec93d1ea35e018963e7`, DB는 PostgreSQL 18.4, 세션 저장소는
Valkey 9.0.6이다. 재현 절차와 격리 범위는 [실행 안내](../../integration/session/README.md)를 따른다.

실행 명령은 `python3 integration/session/run.py`이며, 기록은 Linux 호스트의
`/tmp/bff-auth-integration-chcvn51l/report.json`에 남았다. 외부 Google 토큰·JWK 응답은
fixture이고, 실제 Google 동의 화면이나 브라우저 쿠키 동작을 검증한 결과는 아니다.

| 시나리오 | 확인 결과 |
| --- | --- |
| 로그인·본인 계정 조회·한글 표시 이름 수정 | 실제 Auth DB까지 반영되고 두 BFF에서 조회 성공 |
| RT 회전 | sid 유지, 이전 RT 거절, RT 쿠키 없이도 같은 sid의 미만료 AT는 두 BFF에서 성공 |
| 새 로그인 | sid 교체 후 이전 AT는 SESSION_INVALID, 이전 RT는 REFRESH_REJECTED |
| 이전 세션 로그아웃 | 새 로그인 세션은 유지 |
| 현재 세션 로그아웃 | 두 BFF의 다음 보호 요청을 차단 |
| sid 없는 실제 서명 AT·RT | AT는 ACCESS_TOKEN_INVALID, RT는 REFRESH_REJECTED |
| 외부 X-User-Id·구 auth:refresh 키 | 외부 신원을 인증으로 사용하지 않으며 구 키 fallback 없음 |
| 세션 손상·GET ACL 거절·Redis 중단 | 두 BFF의 Auth·Content 보호 경로에서 SESSION_UNAVAILABLE, 쿠키 변경·내부 호출 없음 |
| 1초 Redis 지연 | 500ms 조회 예산 내 차단, 쿠키 변경·내부 Auth 호출 없음 |
| 장애 복구·다중 인스턴스 | 사전 성공 직후 손상을 즉시 감지하며 복구 후 정상 조회; 세션 캐시 없음 |

요청 계수용 프록시가 내부 호출 도달 여부를 확인한다. 장애 주입과 읽기 전용 BFF 계정은
격리된 저장소에만 적용했다. 이미 인증을 통과한 요청 취소나 열린 스트림 종료는 보장하지 않는다.

실제 소켓 회귀 검사에서 Content 응답 본문의 연결 종료·지연을 502로 오분류하던 문제를
재현하고 503 CONTENT_UNAVAILABLE로 수정했다. 두 경우 모두 재시도 없이 내부 호출
1회임을 확인했다. 전체 `build sessionIntegrationTest` 결과는 일반 테스트 204개와
Redis 7.4·Valkey 9.0.6 테스트 10개 통과, 실패·오류·생략 0개다.

실제 Content 회귀, 프론트·브라우저 연동 및 HTTPS·운영 ACL·네트워크 적용은 별도 검증
대상이다. 이 결과만으로 운영 배포 준비 완료를 판정하지 않는다.
