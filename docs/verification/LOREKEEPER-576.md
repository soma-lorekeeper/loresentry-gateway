# 운영 준비 조건 조사와 인계

> 이전 구현의 실행 기록이다. 2026-09-26 단일 세션 ID 설계의 구현·검증 완료 근거로 사용하지 않는다. 당시 결과와 수치는 보존한다.

2026-09-24, LOREKEEPER-576. [운영 준비 문서](../OPERATIONS.md)에 실제 드라이버의
BFF ACL·설정 주입·내부 접근 제한·관측 요구와 운영 증거의 부재를 정리했다.

Redis 7.4.11/Valkey 9.0.6의 10개 통합 테스트와 실제 Auth·BFF 두 인스턴스 검증을
근거로 GET 허용, 쓰기·다른 키·스크립트 거절, 손상·지연·ACL 실패 차단을 확인했다.
운영 ACL과 NetworkPolicy는 적용하거나 실제 클러스터에서 검증하지 않았다.

GitOps 원격 main `8d2acd0ef594cee9fe1c99f7aeb26a54c6d03404`의 gateway·authentication·
auth-valkey manifest와 prod overlay를 읽고, 필수 환경·키 마운트·BFF ACL·네트워크 정책
준비가 선언되어 있지 않음을 확인했다. 이 문서 작업의 완료는 **운영 준비 미완료 판정을
포함한 인계 완료**이며 실제 미준비를 완료로 판정하지 않는다. 운영 변경은 Work 575의
명시된 범위 밖이다.

문서의 설정명·포트·드라이버 권한을 코드·테스트 fixture·GitOps와 대조했고 링크와
`git diff --check`를 확인했다. 운영 담당 결과가 없으므로 main push·자동 배포는 보류한다.
