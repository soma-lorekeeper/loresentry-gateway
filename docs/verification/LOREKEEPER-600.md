# LOREKEEPER-600 브라우저 연결 검증

Gateway 통합 러너가 실제 정적 프론트·Auth·BFF·Content를 연결해 Chromium·Firefox의
local HTTP와 prod 프로필 HTTPS 쿠키 왕복을 검증했다. 제품 서버 코드는 변경하지 않았다.
실행은 `integration/session/README.md`의 `--browser-project`와 `--browser-only`를 사용한다.

상세 결과와 남은 실제 Google·Windows 검증은 프론트 저장소의
[LOREKEEPER-600 기록](../../../loresentry-frontend/docs/verification/LOREKEEPER-600.md)에 있다.
Google 테스트 공급자를 사용한 자동 검증 성공만으로 이 Atomic을 완료하지 않는다.
