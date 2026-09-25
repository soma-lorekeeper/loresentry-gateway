# LOREKEEPER-590 실행 도구 검증

2026-09-26 통합 러너를 단일 세션 계약으로 변경했다. 서비스 JWT 키 생성·주입을
제거하고 BFF 전용 Redis 권한을 세션 검증·TTL 연장에 맞췄다. 로컬 환경 예제,
README와 현재 구현 상태를 갱신했다. 기존 CI와 Dockerfile은 키 주입 없이
동일 Gradle 빌드 경로를 사용하며 배포 단계는 실행하지 않았다.

## 실행 결과

Java 21 컨테이너로 Auth 격리 복사본과 BFF를 빌드하고, PostgreSQL 18.4·Valkey 9.0.6·
Auth·BFF 두 인스턴스를 기동했다. 로그인·계정 조회·동일 ID 활동 갱신·새 로그인 교체·
이전 세션 폐기의 새 세션 보호·현재 로그아웃 후 양쪽 BFF 거절이 통과했다.

- Auth 커밋: `9c0613f25ed52b87a7dc6ccc4fa223d312237250`
- BFF 코드 기준: `5fe1b1a88a9d190208d3d384d80babe710ac6065`
- BFF JAR SHA-256: `5383dca294992003d3a093e560e11bf12b3edc7349cb38fe0698b5cdb3b79c0c`
- 실행 보고서: `/tmp/bff-auth-integration-wl7jzan8/report.json`

실행 후 해당 컨테이너가 남지 않았고 환경 파일 정리를 확인했다.
Python 구문 검사와 git diff --check를 통과했다. 일반·어댑터 테스트 결과는 589를 따른다.
Content 러너 입력도 세션과 업무 오류 쿠키 갱신에 맞췄으며 실제 Content 검증은 Work 595,
실제 브라우저는 599, 운영은 602에서 수행한다. 과거 실행 기록은 수정하지 않았다.
