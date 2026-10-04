# ZIP PDF 준비와 Android 저장 API 소비자

S4b2b2a는 이미 기기에 저장한 교환 ZIP의 PDF를 서버 저장 계약으로 손실 없이 준비하는 코드와 Android API 소비자다. S4b2b1의 저장 API를 기반으로 하며 연결 화면·자동 업로드·계정 binding·위치 동기화를 활성화하지 않는다.

## 준비 경계

선택한 canonical 문서와 실제 자산 bytes를 하나의 최신 저장소 읽기에서 가져온다. 이전 화면의 제목·표시용 문단·캐시된 문서를 준비 원본으로 삼지 않는다. 원래 언어 대소문자, 빈 문단, 전체 문단 ID와 본문, 순서 있는 자산 참조, null과 빈 alt를 유지한다. payload는 선택 문서의 참조에 처음 등장한 순서로 한 번씩만 담으며 다른 책의 자산은 보내지 않는다. 문단·자산 내용은 `PdfContentValidation`과 웹 대응 검증기로 검사한다.

준비 결과는 업로드 권한이나 현재 계정의 연결이 아니다. 호출자는 업로드할 때 별도로 요청 UUID를 정하고, 불확실한 결과의 재시도에는 같은 UUID와 정확한 내용을 유지해야 한다. 준비만으로 서버를 호출하거나 문서를 수정하지 않는다.

## 소비자 경계

Android는 PDF 전용 transport와 strict JSON codec을 사용한다. 기존 번역 transport의 기본 8 MiB를 바꾸지 않고 PDF 전체 조회에만 12 MiB, receipt/verify에 2 MiB 한도를 둔다. 전송 전 입력 사본을 검증하며 조회 후 실제 bytes로 전체 proof를 다시 계산한다. 계정별 client는 close 시 진행 중 요청을 취소하고 늦은 결과도 폐기한다. 계정/origin/선택 세대와 연결 확인은 다음 화면 계층에서 추가로 검사해야 한다.

- 공통 진입점은 `PdfContentDocuments.fromPackage(package, documentIndex)`다. JSON codec `PdfContentWireJson`은 원래 raw JSON의 중복 키·후행 데이터·UTF-8·정수 표기를 검사하고 조회 bytes로 proof를 재계산한다. PDF 응답은 `JSONObject` 생성 전에 배열당 4096개·전체 값 32768개 제한도 검사해 byte 한도 안의 과도한 객체 생성을 막는다. 숫자 치환 목록은 ZIP normalize만 수집하며 기존 ZIP 숫자 정규화와 한도는 변경하지 않는다.
- Android `PortableLibraryStore.preparePdfContent`는 현재 저장 파일을 lock 안에서 다시 읽는다. 다음 화면은 `PortableLibraryViewModel.currentPdfContent`를 통해 reader 쓰기가 끝나기를 기다린 뒤 준비한다. `HttpPdfContentStore`는 명시적 upload/get/verify만 제공하며 계정 변경 시 폐기한다.
- 웹 `createExchangeLibrary(username).preparePdfContent(id, signal)`은 현재 계정의 동일 IndexedDB row에서 canonical 문서와 payload를 읽고 ID/checksum을 재확인한다. `preparePdfContentFromExchange`는 순수 준비 경로이며 어느 쪽도 서버 요청을 시작하지 않는다.
- Android 전용 HTTP는 리다이렉트·자동 압축·연결 오류 재시도를 막는다. POST body는 한 번만 전송되며 503/421 상태는 HTTP 라이브러리의 내부 follow-up 판단에서만 차단하고 호출자에게 원래 상태·헤더·본문을 돌려준다. 반환 내용은 엄격한 미디어 타입·실제 읽은 bytes·JSON 검증을 통과해야 한다. 오류에 서버의 상세 본문이나 자격 증명을 포함하지 않는다.

## 검증

2026-10-05, 첨부 managed worktree의 `codex/pdf-content-clients`에서 검사했다. [draft PR #43](https://github.com/gongdongho12/PageTuner/pull/43)의 base는 `codex/pdf-content-storage`(#42)다.

- 웹 `npm run verify`: 534개/66파일, 생성 계약 15개, typecheck와 production build 성공. 동일 IndexedDB row의 실제 문서·bytes, 캐시 변조 무시, 계정 격리, 삭제/손상/취소와 업로드 한도 초과를 검증했다. 4 MiB를 넘는 기존 ZIP은 기기에 그대로 보존하고 서버 업로드 준비만 거절한다.
- `core-backup:test` 34개·`backup-runtime:test` 36개 통과. 원래 canonical 문서·첫 참조 순서·실제 bytes/proof, strict DTO/UTF-8/중복 키/수치/시간 검사, 8 MiB를 넘는 정상 GET, 과도한 숫자/중첩 배열의 사전 거절과 최대 문단 4096개·참조 128개·payload 64개의 정상 왕복을 포함한다.
- Android PDF 대상 15개는 새 격리 PostgreSQL/서버를 쓰는 opt-in 1개를 포함해 모두 통과했다. 실제 ZIP import → 준비 → 업로드 → 같은 요청 재시도 → 전체 GET → verify → client close 거절을 확인했다. 실제 TCP 소켓에서는 선언/실제/chunked 크기, 리다이렉트, 취소, 업로드 응답 유실, GET/POST 503/421 후속 요청 차단을 검사했다. 421 소켓 검사는 HTTP/1이며 실제 HTTP/2 연결 병합 환경을 실행했다는 뜻은 아니다.
- 전체 Android 537개 통과/16개 opt-in 제외(기존 15개와 새 PDF 실제 서버 검사 1개). APK, lint, 계측 소스 컴파일, 모듈 경계 검사와 서버 `ServerSecurityMvcTest` 7개가 통과했다. 실제 Android 장치에서의 실행은 V3에 남긴다.
- 실제 검증 DB는 별도 `pagetuner_test_20261005_pdf_client`다. 두 번의 opt-in 검사에서 별도 upload ID의 PDF snapshot이 각각 1건 저장됐고 같은 ID 재시도는 같은 receipt를 반환했다. 해당 QA 계정의 원문·번역·위치·메모 문서/현재값·분류·용어집·소스 즐겨찾기 8종 테이블은 모두 0건이었다. 기존 미리보기 데이터는 사용하지 않았다.

현재 CI는 Android/core와 서버 security, 웹/shared proof 검사를 실행한다. PDF 실제 PostgreSQL/HTTP와 `backup-runtime:test`는 별도 로컬 근거이며 CI 통과로 대신하지 않는다. 상세 로컬 로그는 원본 checkout의 `.gradle-home/pdf-client-web-final.log`, `pdf-client-android-targeted-final.log`, `pdf-client-full.log`, `pdf-client-parser-final.log`에 보관했다. 검증용 서버와 이번 실행에서 시작한 PostgreSQL 프로세스는 종료했으며 DB 파일은 보존했다.

## 남은 범위

별도 계정/origin/로컬 사본/전체 proof binding과 앱·웹 명시적 업로드·연결 화면, 실제 decoder 기반 PDF 위치는 다음 단위다. PDF snapshot UUID를 기존 `PortableServerBindingStore`/`ServerReadingDocument`에 넣거나 S1/S2/분류/용어집 권한으로 해석하지 않는다. binding과 계정 자격 증명은 ZIP에 넣지 않는다. 네이티브 PDF 표시 본문과 원본 파일 hash 연결, EPUB 원본 bytes, 최신 계정 위치·메모·분류 ZIP 통합과 전체 S4는 미완료다.

다음 웹 화면은 `LibraryExchangeWorkspace`의 PDF 행에서 별도 전체 화면 패널로 진입하도록 한다. 기존 버튼 수를 늘리지 않고 최신 준비·사용자 업로드·서버 재검증·별도 연결 확인을 나눈다. 준비 결과는 읽은 시점의 사본이므로 연결 저장 직전에 최신 row와 기존 연결 nonce를 같은 IndexedDB transaction에서 비교해야 한다. 계정 A→B→A, client 교체, 다른 탭의 해제/재연결, 검토 중 파일 삭제/변경을 거절한다. `portableBinding.open`은 text의 serverProgress/glossaryIdentity 권한을 만들기 때문에 PDF에 재사용하지 않는다. Android도 reader 쓰기 완료 후 다시 준비하고 별도 binding namespace를 사용한다.

PDF transport 검토에서 발견한 기존 비밀번호/PATCH transport의 자동 HTTP follow-up은 후속 큐 Q1·[PR #44](https://github.com/gongdongho12/PageTuner/pull/44)에서 실제 503 이중 전송을 재현하고 수정했다. PDF 전용 PR #43과 별도 변경이며 [검증 근거](ACCOUNT_MUTATION_TRANSPORT.md)를 구분한다.

Q1의 대상은 `DefaultTranslationStoreHttpTransport`가 보내는 프로필 PATCH와 비밀번호 POST다. 현재 body가 있는 이 두 경로는 한 번만 쓸 수 있는 RequestBody를 적용하고 실제 소켓의 503/421·응답 유실·리다이렉션으로 확인한다. 가입은 별도 UrlConnectionTransport이므로 같은 OkHttp 결함이라고 표시하지 않는다. CSRF 조회와 기존 비밀번호 결과 불명확 처리·자격 증명 제거 정책도 구분해 보존한다.
