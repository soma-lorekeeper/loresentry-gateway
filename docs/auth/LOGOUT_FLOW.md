# BFF 로그아웃 연동

1. [CSRF](../BROWSER_SECURITY.md#csrf-검증-계약)를 검사한다. 실패하면 Auth 호출·쿠키 삭제 없이 거절한다.
2. RT가 있으면 Auth에 폐기를 요청한다. 서버 처리는 [Auth 폐기 정책](../../../loresentry-authentication/docs/token/REFRESH_TOKEN_DESIGN.md#로그아웃-폐기-실패)을 따른다.
3. Auth 성공·실패와 관계없이 [쿠키 삭제 규칙](../BROWSER_SECURITY.md#atrt-쿠키-발급과-수명)에 따라 AT·RT 삭제 헤더를 반환한다.
4. 서버 폐기 결과는 [외부 로그아웃 응답](../EXTERNAL_API.md#로그아웃-응답)으로 구분해 전달한다.

## 구현 시 검증

- Auth의 폐기 실패 응답이나 통신 실패에도 AT·RT 쿠키 삭제 헤더가 발급 범위와 일치하는지 확인한다.
- 프론트가 브라우저 쿠키 삭제와 서버 RT 폐기 실패·미확인을 구분하고, 완전한 폐기 성공으로 표시하지 않는지 확인한다.
