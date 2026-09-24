# 실제 Auth·BFF 단일 세션 통합 검증

`run.py`는 PostgreSQL 18.4, Valkey 9.0.6, 실제 Auth와 BFF 두 프로세스를 격리 실행한다.
브라우저의 쿠키 정책, 실제 Google 동의 화면, Content 및 운영 인프라 검증은 포함하지 않는다.

Linux 호스트에 Docker, Python 3, OpenSSL과 Auth Git 저장소가 있어야 한다. Java 21과
Gradle은 컨테이너에서 실행한다. 최초 실행에는 이미지·빌드 의존성 다운로드가 필요하다.

```bash
python3 integration/session/run.py
# 다른 Auth 저장소/커밋을 검증할 때 명시한다.
python3 integration/session/run.py --auth-source ../loresentry-authentication --auth-ref <commit>
```

기본 Auth 커밋은 `e9d5b5b35dace0b9c7066ec93d1ea35e018963e7`이다. 원본 Auth 작업 트리는
읽기만 하며, `git archive` 복사본에 `GoogleFixture.java`를 추가해 빌드한다. 외부 Google의
토큰·JWK 응답을 로컬 HTTP 서버로 대체하고, 실제 Auth의 OIDC 서명·nonce 검증과 PKCE
교환, 계정 DB, JWT 발급 및 Redis 세션 처리를 실행한다. fixture는 BFF 소스 세트와 배포
산출물에 들어가지 않는다. Auth fixture 산출물 역시 운영에 배포하지 않는다.

테스트 전용 키·비밀번호를 사용한다. DB·Valkey 포트와 Java 서버는 Linux 호스트의
loopback에만 노출한다. Valkey의 기본 관리자 계정은 장애 주입 전용이며, BFF에는 별도의
`auth:session:*` GET 계정을 주입한다. 이 설정을 운영 설정으로 복사하지 않는다.

검증 항목은 실제 로그인·계정 조회와 수정·RT 회전, 이전 미만료 AT 유지, 새 로그인 후
이전 AT/RT 거절, 이전 세션 로그아웃의 새 세션 보호, 현재 로그아웃, sid 없는 서명된 AT
거절, 구 키 fallback 부재다. 세션 손상·ACL 거절·지연·중단 시 두 BFF가 503을 반환하고
쿠키를 유지하며 내부 Auth를 호출하지 않는지 요청 계수로 확인한다. 이미 인증을 통과한
요청 취소나 열린 스트림 종료는 보장하지 않는다.

성공 시 출력된 `/tmp/bff-auth-integration-*` 경로에 토큰을 포함하지 않는 `report.json`을
남긴다. 빌드·컨테이너 로그도 같은 소유자 전용 디렉터리에 남는다. 종료 시 이 실행이
만든 컨테이너·익명 볼륨과 JWT 개인키·환경 파일을 정리한다. 다른 컨테이너나 DB에는
접근하지 않는다. 일반 `./gradlew build`와 별도로 실행하는 검증이다.
