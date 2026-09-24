# BFF 운영 준비와 검증 인계

**2026-09-24 기준 운영 배포 준비는 미완료다.** 격리된 실제 서비스·드라이버 검증은
통과했으나, 최신 GitOps에는 신규 BFF/Auth 설정과 BFF Redis ACL·NetworkPolicy가 없다.
이 문서는 준비 조건과 증거를 인계하는 산출물이며 운영 적용 완료 판정이 아니다.

## 확인한 근거

GitOps 원격 main `8d2acd0ef594cee9fe1c99f7aeb26a54c6d03404`를 fetch 후 읽었다.
원본 작업 트리와 클러스터는 변경하지 않았다.

| 대상 | 저장소에서 확인한 상태 | 배포 전 필요한 증거 |
| --- | --- | --- |
| `workload/base/gateway/deployment.yaml` | prod 프로필·JWT·세션 Redis 환경 변수와 공개키 마운트 없음 | 아래 설정 주입과 새 이미지 기동 성공 |
| `workload/base/authentication/deployment.yaml` | DB 설정만 있고 신규 JWT·Google·Redis 설정 없음 | 동일 sid 계약의 Auth 이미지·설정 및 로그인 검증 |
| `workload/base/auth-valkey/` | 단일 Recreate 노드, requirepass 사용; BFF 전용 ACL 없음 | 실제 쓰기 담당 endpoint와 별도 BFF 계정·키/명령 권한 검사 |
| `workload/overlays/prod/kustomization.yaml` | 위 항목을 추가하는 patch 없음 | 적용할 운영 manifest와 Argo CD 동기화 결과 |
| NetworkPolicy | 조회한 GitOps main에 선언 없음 | CNI 정책 집행 활성화, 허용 호출 성공과 우회 호출 거절 |

이 실행 환경에는 kubectl·AWS CLI가 없고 운영 담당자의 실제 클러스터 검증 결과도
제공되지 않았다. 따라서 클러스터의 별도 수동 설정 유무, Secret 내용, 네트워크 차단,
실제 배포 이미지의 준비 상태를 추정하지 않았다. GitOps 상태만으로 운영 준비를 인정할
근거는 없으며, 지금 main push로 자동 배포하면 선언된 설정만으로 신규 BFF가 기동하지 못한다.

## BFF 설정과 Secret

| 설정 | 운영 요구 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod`; local/prod 중 하나만 활성화 |
| `BFF_JWT_PUBLIC_KEY` | 읽기 가능한 SPKI RSA 공개키 PEM 파일의 컨테이너 경로; 2048비트 이상 |
| `BFF_JWT_KEY_ID` | 해당 공개키로 검증할 Auth의 kid와 동일한 값 |
| `BFF_SESSION_REDIS_HOST`, `BFF_SESSION_REDIS_PORT` | Auth와 같은 저장소의 쓰기 담당 endpoint, 일반 포트 6379 |
| `BFF_SESSION_REDIS_USERNAME` | default가 아닌 BFF 전용 읽기 계정 |
| `BFF_SESSION_REDIS_PASSWORD` | Secret에서 주입하는 해당 계정 비밀번호 |
| `BFF_SESSION_REDIS_TLS` | 실제 endpoint의 TLS 설정에 맞춰 명시; CA 신뢰도 실제 연결로 확인 |

운영 내부 HTTP 주소는 authentication-api·content-api·graph-rag-api·ai-chat-api의
Service 포트 80이다. 각 Service의 대상 컨테이너 포트는 8000이다. BFF 공개 응답·쿠키
계약은 `https://api.loresentry.com`, 프론트 Origin은 `https://loresentry.com`으로 고정한다.
AT/RT 개인키와 Google client secret은 Auth에만 주입하고 BFF에 주지 않는다. 키·비밀번호
원문을 Git, 명령 출력, 검증 보고서에 넣지 않는다.

## Redis ACL과 연결 검증

실제 Lettuce RESP2 연결과 Redis 7.4.11·Valkey 9.0.6에서 검증한 BFF 권한은 다음과 같다.
운영 비밀번호는 보안 경로로 설정하며, 아래 줄은 비밀번호를 포함한 운영 명령이 아니다.

```text
키: ~auth:session:*
명령: -@all +get +auth +ping +hello +client|setinfo +client|setname
```

`+@read` 전체를 허용하지 않는다. SET·DEL·GETDEL·EXPIRE·EVAL·EVALSHA·KEYS와
`auth:refresh:*`·OAuth 임시 키·다른 서비스 키는 거절해야 한다. Auth는 별도 계정으로
자신의 OAuth/세션 저장·Lua 명령을 수행한다. BFF 권한을 Auth에 그대로 적용하지 않는다.

[Redis/Valkey 검증](verification/LOREKEEPER-562.md)에서 세션 GET 성공, 타 키·쓰기·스크립트
거절, 요청당 정확히 한 번의 GET, TTL 불변과 지연 시 늦은 명령 부재를 확인했다.
[실제 Auth 통합](verification/LOREKEEPER-573.md)에서도 해당 계정으로 새 로그인·RT 회전·
로그아웃을 검증했다. 이 격리 환경의 ACL 성공은 운영 Secret·ACL 적용 증거를 대체하지 않는다.

조회는 연결 초기화부터 GET 완료까지 500ms이고 재시도·허용 캐시·읽기 복제본을 사용하지
않는다. 운영 endpoint의 역할은 DNS 이름만으로 판단하지 말고 인프라 담당자가 실제 쓰기
담당 노드와 라우팅·페일오버 동작을 확인해야 한다. 저장소 재시작·eviction·상태 유실 시
사용자는 재로그인해야 하며 과거 스냅샷 복원으로 폐기 세션이 부활하지 않도록 관리한다.

## 내부 API 접근 제한

ClusterIP와 Ingress 부재만으로 클러스터 내부의 우회 호출을 차단했다고 판단하지 않는다.
Auth·Content는 X-User-Id를 신뢰하므로, BFF를 거치지 않는 요청을 실제로 차단해야 한다.

| 경로 | 필요한 결과 |
| --- | --- |
| ALB → gateway-api:80 / Pod:8000 | 허용; 공개 Ingress는 BFF만 대상 |
| BFF → authentication-api·content-api:80 / Pod:8000 | 허용; 계약에 맞는 내부 호출 |
| 허용되지 않은 Pod·namespace → Auth·Content | 거절; X-User-Id 위조만으로 접근 불가 |
| BFF·Auth → 세션 쓰기 담당 노드:6379 또는 설정한 TLS 포트 | 각각 별도 ACL로 허용 |
| 그 밖의 워크로드 → 세션 저장소 | 거절 |
| DNS·Auth의 Google 호출·서비스별 기존 DB 등 | 실제 필요한 egress를 인프라 정책에서 유지 |

인프라 담당자는 정책 manifest·CNI 집행 설정, 허용/거절 요청의 출발 Pod·namespace·대상
포트·시각과 결과를 제출해야 한다. 민감 값은 기록하지 않는다. 격리 통합 도구는 서비스를
Linux loopback에 바인딩하며 운영 NetworkPolicy나 클러스터 우회 차단을 검증하지 않는다.

## 관측과 배포 판정

| 관측 항목 | 확인할 내용 |
| --- | --- |
| 세션 조회 지연 | 연결을 포함한 p50/p95/p99와 500ms 시간 초과 비율 |
| 세션 실패 | SESSION_INVALID·SESSION_UNAVAILABLE 발생률, ACL/연결/레코드 이상 진단 |
| Auth 상태 변경 | 로그인 실패, 재발급 경쟁 거절, REFRESH_OUTCOME_UNKNOWN·REVOCATION_UNCONFIRMED |
| 보안 경계 | CSRF/CORS 거절과 보호 요청의 내부 서비스 미도달 |
| 민감값 보호 | Cookie·Authorization·AT/RT·OAuth code/state·Redis 값·비밀번호 로깅 금지 |

사용자 UUID·sid·jti·토큰을 메트릭 라벨로 사용하지 않는다. BFF는 예외 원문과 내부 응답
원문을 브라우저에 노출하지 않고 토큰 DTO의 문자열 출력을 가린다. 현재 전용 지연/오류
메트릭·대시보드를 배포한 것은 아니며, 위 항목의 운영 수집·알람은 인프라 인계 대상이다.

main push는 자동 이미지 배포를 실행한다. GitOps 설정·ACL·접근 제한의 실제 준비와
Auth/BFF 공동 전환 계획을 확인한 뒤 시행한다. 프론트 검증 제외 승인은 운영 설정이
준비됐다는 뜻이 아니며, 프론트 인증 기능은 여전히 별도 구현이 필요하다.
