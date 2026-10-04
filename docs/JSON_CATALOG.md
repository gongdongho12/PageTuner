# PageTurner JSON 카탈로그

앱과 서버는 `source-runtime/.../source/catalog/PageTurnerJsonCatalog.kt`의 동일한 v0 파서를 사용한다. 앱의 `PageTurnerWebCatalogParser`는 파싱 결과를 기존 `RemoteBookItem`으로 바꾸는 어댑터다. 웹은 [OpenAPI 계약](../contracts/json-catalog-v1.openapi.json)에서 생성한 타입과 런타임 검증을 사용한다.

- 인증된 `GET /api/v1/catalogs/json?url=...`은 정규화한 카탈로그를 반환한다. 원격 JSON은 5MiB, 항목 1,000개, 링크 100개까지다. 상대 링크는 리디렉션 뒤 최종 카탈로그 URL을 기준으로 해석하며 `?page=2`는 파일 경로를 유지한다. 잘못된 행·중복 ID·알 수 없는 파일 형식을 조용히 누락하지 않는다.
- 인증된 `GET /api/v1/catalog-files?url=...`은 최대 32MiB의 원본 바이트를 `application/octet-stream` 첨부파일로 반환한다. 두 API는 읽기이며 CSRF 토큰이 필요 없다. 응답은 `no-store`, 파일은 `nosniff`로 표시한다.
- 소설 수집과 같은 `PublicHttpsNovelHttpClient` 인스턴스를 사용한다. 공개 HTTPS 443, 실제 소켓에 적용되는 공개 DNS 검증, 모든 리디렉션 검증, 최대 5회 이동, 요청별 25초/전체 60초 제한, 읽은 바이트와 압축 해제 바이트 제한, 요청 간격·동시 실행 제한을 공유한다. 원격 주소에 사용자 계정·쿠키를 전달하지 않는다.

웹 `JsonCatalogWorkspace`는 주소, 현재 페이지의 제목·저자 검색, `next`/`prev`/`previous` 링크, TXT 인코딩 선택을 제공한다. 파일을 받은 뒤 기존 `parseLocalDocument`와 계정별 로컬 서재 IndexedDB로 저장하고 같은 리더에서 읽는다. 저장한 파일은 기기 보관함의 로컬 파일 서재에서도 사용할 수 있다. 번역은 기존 로컬 원문 업로드 경로를 사용한다.

카탈로그의 `size`가 있으면 실제 바이트 수와 비교한다. `checksum`은 `sha256:<hex>` 또는 64자리 SHA-256을 확인한다. 명시된 다른 체크섬을 확인한 것처럼 취급하지 않고 가져오기를 거절한다. 파일 ID는 URL이나 표시 제목 대신 실제 파일 내용의 SHA-256으로 결정한다. TXT·Markdown·EPUB·PDF 파싱 제한은 [로컬 읽기 문서](../web/LOCAL_READING.md)를 따른다.

현재 구현은 PageTurner v0 형식이다. OPDS, 외부 계정 인증이 필요한 파일, 비공개·로컬 네트워크 주소는 지원하지 않는다. 카탈로그 표지 URL을 브라우저에서 자동 요청하지 않는다. 카탈로그 검색은 현재 응답 안에서 동작하며 원격 전체 서재 검색을 추정하지 않는다.

검증은 공유 파서 테스트, Spring MVC 인증/응답 테스트, 결정적 HTTP interceptor의 리디렉션/바이트 경계 테스트, 웹의 실제 TXT 파서·IndexedDB·계정 분리/체크섬 테스트를 구분한다. 테스트용 카탈로그를 실사이트 성공으로 보고하지 않는다. 사용자가 지정한 운영 카탈로그의 실제 HTTP 검증은 별도 실행과 결과가 필요하다.
