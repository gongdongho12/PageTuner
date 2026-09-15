# 앱·웹 기능 대응표

기준일: 2026-09-16. 현재 작업 트리의 화면 진입점, 상태 모델, 저장소와 API 호출을 기준으로 정리했다. **앱의 모든 기능이 웹에 옮겨진 상태는 아니다.** 현재 공통 흐름은 웹소설 수집, 챕터 원문 저장, 서버 번역 실행, 번역본 조회·보관·읽기까지 연결되어 있다. 로컬 파일·독서 도구·계정·개인 서재도 연결됐으며 아래에 남은 차이와 확인된 연결 결함을 별도로 표시한다.

## 판정 방법

- **완료**: 해당 행에 명시한 범위의 구현과 화면 연결이 있다. 모든 실사이트·브라우저·Android 기기에서 검증됐다는 뜻은 아니다.
- **부분**: 기능의 일부만 연결됐거나 명시적인 제약이 있다.
- **미완**: 사용자 화면 또는 필요한 실행·저장 경로가 없다. 모델이나 클래스만 있어도 완료로 올리지 않는다.
- **진행 중**: 현재 작업에서 추가 중이며 최종 화면 연결·검증이 남아 있다.
- **기기 전용**: Android OS 또는 하드웨어에 결합된 기능이다. 대응하는 웹 기능이 필요한지는 별도로 판단한다.

이 문서는 구현 범위표다. 실제 테스트 실행, 외부 소설 사이트 응답, 번역 공급자 가용성은 [검증 기록](WEB_FRONTEND_VALIDATION.md)과 [소설·번역 워크플로](NOVEL_TRANSLATION_WORKFLOW.md)를 함께 확인한다. UI가 없는 기능과 기기 전용 기능을 합쳐 임의의 완료율을 계산하지 않는다.

## 화면 진입점과 공통 실행 경계

| 구분 | 현재 진입점·역할 | 근거 |
| --- | --- | --- |
| Android 앱 | Local, Favorites, Web Novel, Drive, Settings. Web Novel 안에 기존 소설 화면과 새 서버 서재가 있다. Drive는 준비 중 화면이다. | [MainActivity.kt](../app/src/main/java/com/dongholab/pagetuner/MainActivity.kt), [AppTabSection.kt](../app/src/main/java/com/dongholab/pagetuner/ui/common/AppTabSection.kt) |
| 웹 | 소설, 서버 서재, 기기 보관함, 연결 화면. 소설 안에 소스·카탈로그·책 상세·원문·번역 작업 화면이 있다. | [App.tsx](../web/src/App.tsx), [NovelWorkspace.tsx](../web/src/components/NovelWorkspace.tsx) |
| 공통 도메인 | 원문 문단 ID·순서·내용, sourceRevision, 번역 variant·artifact·payloadHash, 저장 계약을 공통 Kotlin 모듈이 정의한다. | [core-content](../core-content/src/main/kotlin), [core-translation](../core-translation/src/main/kotlin) |
| 공통 소설 수집 | 기존 앱의 WTR-LAB·NovelBuddy·Generic 파서와 어댑터를 `source-runtime`으로 이동했다. 서버는 공개 HTTPS 검증 transport를 주입한다. | [NovelSourceService.kt](../source-runtime/src/main/kotlin/com/dongholab/pagetuner/source/service/NovelSourceService.kt), [PublicHttpsNovelHttpClient.kt](../server/src/main/kotlin/com/dongholab/pagetuner/server/novel/PublicHttpsNovelHttpClient.kt) |
| 공통 번역 실행 | 네 공급자, 용어집 처리, 배치·분할·식별 규칙은 `translation-runtime`에 있다. 앱은 기기에서 호출하고 웹은 서버 API로 실행한다. | [TranslationProviderFactory.kt](../translation-runtime/src/main/kotlin/com/dongholab/pagetuner/translation/TranslationProviderFactory.kt), [ChapterTranslationEngine.kt](../translation-runtime/src/main/kotlin/com/dongholab/pagetuner/translation/ChapterTranslationEngine.kt), [WorkflowTranslator.kt](../server/src/main/kotlin/com/dongholab/pagetuner/server/workflow/WorkflowTranslator.kt) |
| 웹 계약 소비 | 브라우저에서 Kotlin/JVM 모듈을 직접 실행하지 않는다. TypeScript DTO 검증과 OpenAPI 계약을 통해 서버를 호출한다. | [contracts](../contracts), [workflowApi.ts](../web/src/lib/workflowApi.ts), [validation.ts](../web/src/lib/validation.ts) |

## 문서·서재

| 기능 | Android 앱 | 웹 대응 | 범위·근거 |
| --- | --- | --- | --- |
| TXT·Markdown 파일 가져오기 | 완료 | 완료 | 웹은 32MB 이하 파일, BOM/엄격 UTF-8 및 명시적 레거시 인코딩, 내용 해시 ID, 계정별 기기 보관을 지원한다. 일반 서버 artifact와 별도 모델이다. [DocumentLoader](../app/src/main/java/com/dongholab/pagetuner/document/DocumentLoader.kt), [localDocuments](../web/src/lib/localDocuments.ts), [LocalWorkspace](../web/src/components/LocalWorkspace.tsx) |
| EPUB 가져오기·본문·장 이동 | 완료 | 완료 | 양쪽 OPF/spine 순서를 읽는다. 웹은 안전한 XML 텍스트 추출과 문단 ID 기반 목차 이동이며 원본 CSS 레이아웃을 실행하지 않는다. [EpubDocumentReader](../app/src/main/java/com/dongholab/pagetuner/document/EpubDocumentReader.kt), [epubDocument](../web/src/lib/epubDocument.ts), [ReaderTools](../web/src/components/ReaderTools.tsx) |
| EPUB 삽화 표시 | 부분 | 부분 | 앱은 페이지당 첫 두 이미지를 표시한다. 웹은 장별 첫 두 PNG/JPEG/GIF/WebP를 내용 signature로 확인해 기기에 보관하고 삽화 탭에서 표시한다. SVG·외부 이미지·원본 EPUB 레이아웃 전체 재현은 지원 범위 밖이다. [ReaderUi](../app/src/main/java/com/dongholab/pagetuner/ui/reader/ReaderUi.kt), [epubDocument](../web/src/lib/epubDocument.ts), [ReaderTools](../web/src/components/ReaderTools.tsx) |
| PDF 페이지 읽기·맞춤 렌더링 | 완료 / 기기 전용 구현 | 완료 | 앱 PdfRenderer와 웹 PDF.js canvas는 별도 플랫폼 구현이다. 웹은 원본 Blob을 보관하며 이전/다음, 페이지·너비 맞춤, 너비 모드의 버튼식 위/아래 이동을 제공한다. 32MB·2,000페이지 제한, 암호 PDF는 명확한 오류로 처리한다. [PdfDocumentReader](../app/src/main/java/com/dongholab/pagetuner/document/PdfDocumentReader.kt), [LocalPdfReader](../web/src/components/LocalPdfReader.tsx) |
| PDF 텍스트 추출·이미지 OCR | 부분 / OCR 미완 | 부분 / OCR 미완 | 웹은 추출한 실제 텍스트의 검색과 번역 진입을 제공한다. 추출 실패는 텍스트 없는 이미지 페이지와 별도 상태로 보관·표시하며 한 페이지라도 실패하면 파일 전체 번역을 막는다. 원본 페이지는 계속 렌더링한다. 과거 보관본의 추출 상태가 없으면 다시 가져오도록 안내한다. 이미지 OCR은 없다. [pdfDocument](../web/src/lib/pdfDocument.ts), [localDocuments](../web/src/lib/localDocuments.ts), [OCR 계획](OCR_PLAN.md) |
| 기기 디렉터리 탐색 | 완료 / 기기 전용 | 부분 / 파일 선택 | 앱 파일 접근·권한을 사용하는 디렉터리 탐색 화면이 있다. 웹은 브라우저 파일 선택으로 가져오며 임의 로컬 디렉터리 트리를 탐색하지 않는다. [LocalDirectoryBrowserPanel](../app/src/main/java/com/dongholab/pagetuner/ui/library/LocalDirectoryBrowserPanel.kt), [LocalWorkspace](../web/src/components/LocalWorkspace.tsx) |
| 로컬 서재 중복 처리·책 삭제 | 완료 | 완료 | 웹은 서버 번역 보관함과 로컬 파일 서재를 분리한다. 같은 파일 해시의 중복을 막고 본문이 달라지는 재가져오기는 거부한다. 파일 삭제 확인에 해당 북마크·메모·위치 삭제를 명시한다. [LocalLibraryStore](../app/src/main/java/com/dongholab/pagetuner/library/LocalLibraryStore.kt), [localDocuments](../web/src/lib/localDocuments.ts), [LocalWorkspace](../web/src/components/LocalWorkspace.tsx) |
| 폴더·태그·검색·분류 | 완료 | 완료 / 로컬 파일 | 웹 로컬 파일 서재에 제목·형식·폴더·태그 검색, 폴더/즐겨찾기 필터, 최근/제목/폴더 정렬과 분류 편집을 연결했다. 파일 재가져오기 시 분류를 유지한다. 서버 서재 전체의 분류 동기화와는 별개다. [LocalLibraryFilters](../app/src/main/java/com/dongholab/pagetuner/ui/library/LocalLibraryFilters.kt), [localOrganization](../web/src/lib/localOrganization.ts), [LocalLibraryOrganizer](../web/src/components/LocalLibraryOrganizer.tsx) |
| 서버 번역 목록·읽기 | 완료 | 완료 | 사용자별 페이지 목록, 제목, 단일 revision 본문 조회를 양쪽 화면에서 사용한다. 한 책의 여러 revision을 임의로 합치지 않는다. [ServerLibraryScreen](../app/src/main/java/com/dongholab/pagetuner/ui/screen/ServerLibraryScreen.kt), [HttpTranslationStore](../app/src/main/java/com/dongholab/pagetuner/translation/sync/HttpTranslationStore.kt), [api.ts](../web/src/lib/api.ts) |
| 서버 원문 목록·읽기 | 완료 | 완료 | 앱과 웹이 `/chapters` 목록·개별 원문을 읽는다. 원문은 문단 ID/ordinal과 sourceRevision을 검증한다. [ServerLibraryModels](../app/src/main/java/com/dongholab/pagetuner/translation/sync/ServerLibraryModels.kt), [NovelWorkspace](../web/src/components/NovelWorkspace.tsx), [SourceChapterStore](../server/src/main/kotlin/com/dongholab/pagetuner/server/workflow/SourceChapterStore.kt) |
| 현재 앱 문서의 번역 서버 저장·캐시 복원 | 완료 | 부분 | 앱은 실제 전체 segment ID로 매핑하고 미번역/빈 번역을 거부하며 복원 시 로컬 수정 충돌을 막는다. 웹은 서버 작업 결과를 소비하며 로컬 문서 번역 캐시를 업로드하는 화면은 없다. [ServerDocumentMapping](../app/src/main/java/com/dongholab/pagetuner/translation/sync/ServerDocumentMapping.kt), [ServerLibraryViewModel](../app/src/main/java/com/dongholab/pagetuner/translation/sync/ServerLibraryViewModel.kt) |
| 서버 결과를 기기 리더로 열기 | 완료 | 완료 | 앱은 원문/번역문을 기존 텍스트 리더에 연결하고 기기 보관을 선택하면 로컬 서재로 가져온다. 웹은 PagedReader에서 읽고 번역본을 IndexedDB에 보관한다. [MainActivity](../app/src/main/java/com/dongholab/pagetuner/MainActivity.kt), [PagedReader](../web/src/components/PagedReader.tsx) |

## 웹소설 소스·가져오기

| 기능 | Android 앱 | 웹 대응 | 범위·근거 |
| --- | --- | --- | --- |
| WTR-LAB 카탈로그·상세·목차·본문 | 완료 | 완료 | 동일한 source-runtime 어댑터를 사용한다. 로그인 필요·차단·사이트 변경은 실제 오류로 처리하며 현재 모든 작품 접근을 보장하지 않는다. [WtrLabSiteAdapter](../source-runtime/src/main/kotlin/com/dongholab/pagetuner/source/webnovel/WtrLabSiteAdapter.kt), [NovelController](../server/src/main/kotlin/com/dongholab/pagetuner/server/novel/NovelController.kt) |
| NovelBuddy 카탈로그·상세·목차·본문 | 완료 | 완료 | 같은 파서/어댑터를 사용한다. 목차 수집 실패를 정상 전체 목록으로 대체하지 않는다. 실제 접근 가능성은 별도 live 검증 대상이다. [NovelBuddySiteAdapter](../source-runtime/src/main/kotlin/com/dongholab/pagetuner/source/webnovel/NovelBuddySiteAdapter.kt) |
| Generic URL 수집 | 부분 | 부분 | 의미 기반 HTML 추출이다. 임의 사이트의 검색·목차·본문을 전부 지원하지 않으며 알 수 없는 메타데이터를 실제 값처럼 만들지 않는다. [GenericWebNovelSiteAdapter](../source-runtime/src/main/kotlin/com/dongholab/pagetuner/source/webnovel/GenericWebNovelSiteAdapter.kt), [GenericSemanticHtmlScraperAdapter](../source-runtime/src/main/kotlin/com/dongholab/pagetuner/source/scraper/GenericSemanticHtmlScraperAdapter.kt) |
| 검색·원격 페이지 이동 | 완료 | 완료 | 지원 소스의 검색어와 페이지를 전달한다. 소스별 전체 건수를 모르면 확정 합계로 표시하지 않는다. [WebCatalogViewModel](../app/src/main/java/com/dongholab/pagetuner/source/WebCatalogViewModel.kt), [NovelWorkspace](../web/src/components/NovelWorkspace.tsx) |
| 장르·상태·WTR 정렬 등 상세 필터 | 완료 | 완료 / 광고된 옵션 | 웹은 서버 SourceFilters의 장르·상태·정렬·방향을 선택하고 값을 catalog 요청에 전달한다. 미지원 옵션은 제공하지 않는다. [CatalogFilterPanel](../web/src/components/CatalogFilterPanel.tsx), [NovelSourceService](../source-runtime/src/main/kotlin/com/dongholab/pagetuner/source/service/NovelSourceService.kt) |
| 단일 챕터 가져오기·읽기·번역 진입 | 완료 | 완료 | 앱은 기기 서재로 가져오거나 직접 읽고 번역한다. 웹은 서버 원문 저장 후 읽기/번역 작업으로 이동한다. [WebNovelScreen](../app/src/main/java/com/dongholab/pagetuner/ui/screen/WebNovelScreen.kt), [NovelWorkspace](../web/src/components/NovelWorkspace.tsx), [WorkflowController](../server/src/main/kotlin/com/dongholab/pagetuner/server/workflow/WorkflowController.kt) |
| 여러 챕터 일괄 보관·번역 | 완료 | 완료 / 최대 20회차 | 웹 목차에서 회차 범위를 선택해 순차 import·번역하고 계정별 checkpoint로 pause·cancel·명시적 재개를 제공한다. 전체 책을 무제한 한 번에 큐에 넣는 기능은 아니다. 창을 닫아도 이미 제출된 서버 작업은 계속될 수 있다. [BulkNovelWorkspace](../web/src/components/BulkNovelWorkspace.tsx), [bulkNovelQueue](../web/src/lib/bulkNovelQueue.ts) |
| 카탈로그 제목·설명 번역 | 완료 | 완료 / 별도 작업 | 소스 카탈로그 목록과 책 소개에서 제목·설명 번역 설정·실행·취소를 제공한다. 챕터 본문 번역 작업과 별도이며 서버 작업 재시작·실공급자 검증은 개별 근거를 확인한다. [CatalogTranslationCoordinator](../app/src/main/java/com/dongholab/pagetuner/source/CatalogTranslationCoordinator.kt), [CatalogTranslationPanel](../web/src/components/CatalogTranslationPanel.tsx), [catalogTranslation](../web/src/lib/catalogTranslation.ts) |
| 즐겨찾기·소스 주소 저장/삭제 | 완료 | 완료 / 기기 내 | 웹의 책 즐겨찾기와 저장 소스 URL은 계정별 IndexedDB에 보관하고 삭제·목차 재진입을 제공한다. 앱의 소스 계정과 서버 회원 계정은 다른 개념이며 기기 간 자동 동기화하지 않는다. [PersonalShelf](../web/src/components/PersonalShelf.tsx), [personalLibrary](../web/src/lib/personalLibrary.ts) |
| 카탈로그 디스크 캐시·다음 페이지 미리 받기 | 완료 | 완료 / 계정별 임시 보관 | 소스·검색·필터·페이지 키를 분리해 IndexedDB에 저장하고 다음 페이지를 제한적으로 선행 수집한다. 오프라인에서 저장 목록을 열며 TTL·LRU·용량 제한을 적용한다. [catalogCache](../web/src/lib/catalogCache.ts), [CachedCatalogBrowser](../web/src/components/CachedCatalogBrowser.tsx) |
| PageTurner JSON 카탈로그 | 완료 | 완료 / 공개 HTTPS 파일 | 웹소설의 JSON 카탈로그 진입에서 v0 목록·검색·이전/다음, TXT·Markdown·EPUB·PDF 기기 보관과 번역 진입을 연결했다. 서버는 공유 parser와 공개 HTTPS 파일 transport를 사용한다. 원본은 32MB 이하이며 제공된 크기·SHA-256을 검사한다. 모든 원격 카탈로그의 실접속을 보장하지 않는다. [PageTurnerWebCatalogSource](../app/src/main/java/com/dongholab/pagetuner/source/PageTurnerWebCatalogSource.kt), [JsonCatalogWorkspace](../web/src/components/JsonCatalogWorkspace.tsx), [jsonCatalog](../web/src/lib/jsonCatalog.ts) |
| 사용자 CSS 규칙 소스 | 부분 | 미완 | 앱에 규칙 입력/저장은 있으나 CustomRuleScraperAdapter가 현재 사용자 selector를 온전히 실행하는 구현은 아니다. 편집 UI만으로 수집 지원 완료로 판단하지 않는다. [AddCatalogSourceDialog](../app/src/main/java/com/dongholab/pagetuner/ui/source/AddCatalogSourceDialog.kt), [CustomRuleScraperAdapter](../source-runtime/src/main/kotlin/com/dongholab/pagetuner/source/scraper/CustomRuleScraperAdapter.kt) |
| JavaScript 렌더링 보조 | 부분 / 기기 전용 구현 | 미완 | 앱은 Android 렌더링 loader가 있고 서버는 HTTP 수집 경로를 사용한다. 서버가 모든 동적 사이트를 렌더링하는 브라우저라고 설명하면 안 된다. [RenderedChapterLoader](../app/src/main/java/com/dongholab/pagetuner/source/RenderedChapterLoader.kt), [WebNovelChapterLoadStrategy](../source-runtime/src/main/kotlin/com/dongholab/pagetuner/source/webnovel/WebNovelChapterLoadStrategy.kt) |
| Google Drive·FTP 연결 | 미완 | 미완 | 관련 구현 클래스는 있지만 앱 Drive 최상위 화면은 ComingSoon이다. 사용자 계정 설정부터 파일 열기까지 완성됐다는 의미로 표시하지 않는다. [MainActivity](../app/src/main/java/com/dongholab/pagetuner/MainActivity.kt), [GoogleDriveRemoteBookSource](../app/src/main/java/com/dongholab/pagetuner/source/GoogleDriveRemoteBookSource.kt), [FtpRemoteBookSource](../app/src/main/java/com/dongholab/pagetuner/source/FtpRemoteBookSource.kt) |
| 미니앱·스크립트 플러그인 | 미완 | 미완 | 저장 모델·JS bridge·계약은 있으나 현재 메인 화면에서 실행되는 완결 경로가 확인되지 않는다. [MiniProgramStore](../app/src/main/java/com/dongholab/pagetuner/source/miniapp/MiniProgramStore.kt), [PageTurnerJsBridge](../app/src/main/java/com/dongholab/pagetuner/source/miniapp/PageTurnerJsBridge.kt), [ScriptableConnectorPlugin](../app/src/main/java/com/dongholab/pagetuner/source/plugin/ScriptableConnectorPlugin.kt) |

## 번역·용어집

| 기능 | Android 앱 | 웹 대응 | 범위·근거 |
| --- | --- | --- | --- |
| Google Web·Google Cloud·DeepSeek·OpenAI 호환 공급자 | 완료 | 완료 | 공통 네 공급자와 완결 응답 검증을 사용한다. 웹 전체·읽기·묶음·목록 번역 설정에 공통 연결 필드와 서버 고정 예문 확인을 연결했다. 유료 공급자는 localhost HTTP·브라우저 경로를 검증했고 실서비스 성공은 키·접근 권한에 달려 있다. [공급자 연결 확인](PROVIDER_CONNECTION_CHECK.md), [TranslationProviderFactory](../translation-runtime/src/main/kotlin/com/dongholab/pagetuner/translation/TranslationProviderFactory.kt), [TranslationSetup](../web/src/components/TranslationSetup.tsx) |
| 원문/대상 언어·모델·endpoint·API 키 | 완료 | 완료 | 웹은 서버 허용 endpoint 범위 안에서 설정한다. 웹 작업 API 키와 앱 서버 로그인 비밀번호는 화면 메모리로 취급한다. 임의 endpoint를 무제한 허용하는 기능은 아니다. [SettingsScreen](../app/src/main/java/com/dongholab/pagetuner/ui/screen/SettingsScreen.kt), [TranslationSetup](../web/src/components/TranslationSetup.tsx), [WorkflowTranslator](../server/src/main/kotlin/com/dongholab/pagetuner/server/workflow/WorkflowTranslator.kt) |
| 현재 페이지 번역·읽는 속도 기반 선행 번역 | 완료 | 완료 / 서버 보관 원문 | 웹은 실제 원문 페이지 범위, 10쪽 단위 창과 5번째 쪽의 다음 창 준비, 현재 쪽 우선, WPM/pace·공급자·용어집 설정을 사용한다. 로컬 파일은 서버 원문으로 보관한 뒤 같은 리더를 사용한다. 임시 번역은 세션 메모리에만 두며 완성 번역본 저장은 기존 전체 작업으로 실행한다. [RollingTranslationReader](../web/src/components/RollingTranslationReader.tsx), [rollingTranslation](../web/src/lib/rollingTranslation.ts), [읽기 번역 계약](../contracts/reading-translation-v1.md) |
| 일시정지·재개·취소·실패 재시도 | 완료 | 부분 / 작업 경계 차이 | 웹 단일 서버 번역은 취소·체크포인트 재시도, 묶음 큐는 회차 사이 pause·resume을 제공한다. 앱의 현재 페이지·선행 번역을 즉시 pause하는 제어와는 다르다. [TranslationViewModel](../app/src/main/java/com/dongholab/pagetuner/translation/TranslationViewModel.kt), [bulkNovelQueue](../web/src/lib/bulkNovelQueue.ts), [TranslationWorkflowService](../server/src/main/kotlin/com/dongholab/pagetuner/server/workflow/TranslationWorkflowService.kt) |
| 서버 작업 내역·새로고침·결과 읽기 | 완료 | 완료 | 앱 ServerTranslationJobsPanel과 웹 작업 화면이 생성·목록·진행·취소·재시도·결과 읽기를 제공한다. 앱의 모든 로컬 번역 작업이 서버 작업으로 동기화되는 것은 아니다. [ServerTranslationJobsPanel](../app/src/main/java/com/dongholab/pagetuner/ui/screen/ServerTranslationJobsPanel.kt), [ServerLibraryViewModel](../app/src/main/java/com/dongholab/pagetuner/translation/sync/ServerLibraryViewModel.kt), [NovelWorkspace](../web/src/components/NovelWorkspace.tsx) |
| 번역 캐시·중복 방지·내용 검증 | 완료 | 완료 | 앱 캐시와 서버 artifact는 variant/revision/문단 ID를 검증한다. 웹은 wire hash를 확인한 번역을 보관한다. 저장 위치와 동기화 범위는 다르다. [TranslationCache](../app/src/main/java/com/dongholab/pagetuner/translation/TranslationCache.kt), [TranslationApplicationService](../server/src/main/kotlin/com/dongholab/pagetuner/server/translation/TranslationApplicationService.kt), [validation.ts](../web/src/lib/validation.ts) |
| 원문·번역 표시 모드 | 완료 | 완료 / 문단 대조 | 번역·원문·대조 보기를 제공한다. 원문 revision과 양쪽 본문 해시·문단 ID 순서를 검증한 뒤 같은 문단을 연결한다. 다른 언어의 글자 위치를 추정하지 않으며 대조 문서에는 파생 메모를 저장하지 않는다. 원문을 함께 보관하면 오프라인 대조가 가능하다. [TranslationComparisonReader](../web/src/components/TranslationComparisonReader.tsx), [translationComparison](../web/src/lib/translationComparison.ts) |
| 책별 용어집 CRUD·종류·활성 상태 | 완료 | 구현 / 화면 검증 대기 | 웹은 200항목 내 source/target·인물/장소/일반 용어·활성 상태·대소문자·표시 별칭을 편집한다. 옵션의 기본값은 기존 요청 JSON을 유지하며 다음 번역과 재시도에 보존된다. 계정별 저장과 fingerprint를 단위 검증했다. [GlossaryEditor](../web/src/components/GlossaryEditor.tsx), [glossary](../web/src/lib/glossary.ts), [WorkflowGlossaryCompatibilityTest](../server/src/test/kotlin/com/dongholab/pagetuner/server/workflow/WorkflowGlossaryCompatibilityTest.kt) |
| 용어집 JSON 가져오기·내보내기·공유 | 완료 | 부분 / 공통 JSON 파일 | 웹은 앱의 pagetuner-book-glossary v1 필드 전체와 기존 JSON 배열·entries 객체를 읽는다. 내보내기는 앱의 160자 한도를 넘는 용어를 알리고 기존 용어를 덮어쓰지 않는다. 종류·별칭·활성 상태의 파일 왕복 단위 테스트가 있으며 OS 공유 시트는 제공하지 않는다. [GlossaryEditor](../web/src/components/GlossaryEditor.tsx), [glossary.test](../web/src/lib/glossary.test.ts), [BookGlossaryShareCodec](../app/src/main/java/com/dongholab/pagetuner/translation/glossary/BookGlossaryShareCodec.kt) |
| 표시 별칭·인물명·조사 처리·fingerprint | 완료 | 구현 / 화면 검증 대기 | 웹 원문/번역 읽기는 별칭·인물명 강조·한국어 조사를 표시 계층에 적용한다. 원본 텍스트와 해시는 유지하고, 별칭 일부를 선택해도 강조·인용문은 원본 용어 범위로 저장한다. 기본 fingerprint·활성/대소문자 변경과 표시 좌표 변환을 단위 검증했다. [glossaryDisplay](../web/src/lib/glossaryDisplay.ts), [glossaryReader.test](../web/src/lib/glossaryReader.test.ts), [GlossaryTextProcessor](../translation-runtime/src/main/kotlin/com/dongholab/pagetuner/translation/glossary/GlossaryTextProcessor.kt) |

## 읽기·전자잉크 조작

| 기능 | Android 앱 | 웹 대응 | 범위·근거 |
| --- | --- | --- | --- |
| 본문 페이지 넘김 | 완료 | 완료 | 양쪽 모두 독립 페이지를 사용한다. 웹은 실제 DOM 크기에 맞춰 문단을 분할하고 글자/화면 변경 시 anchor를 재배치한다. [ReaderUi](../app/src/main/java/com/dongholab/pagetuner/ui/reader/ReaderUi.kt), [PagedReader](../web/src/components/PagedReader.tsx), [readerPosition](../web/src/components/readerPosition.ts) |
| 글자 크기·행간·여백·글꼴 | 완료 | 완료 / 기기 내 | 웹 읽기 도구에 명조/고딕/고정폭, 크기, 행간, 여백 설정이 있다. 계정별 기기에 저장하며 본문 재배치 시 기존 문단/문자 anchor를 유지한다. 서버 설정 동기화는 없다. PDF 원본 글꼴은 파일 그대로 렌더링한다. [ReaderSettings](../app/src/main/java/com/dongholab/pagetuner/settings/ReaderSettings.kt), [readerPreferences](../web/src/lib/readerPreferences.ts), [ReaderPreferences](../web/src/components/ReaderPreferences.tsx) |
| 책 안 검색·검색 결과 이동 | 완료 | 완료 | 웹에서 문자 그대로 대소문자를 구분하지 않고 검색하며 원본 UTF-16 위치를 반환한다. 결과를 선택하면 해당 페이지로 이동하고 재배치 뒤에도 위치를 유지한다. PDF는 추출된 실제 텍스트만 검색하며 이미지 페이지는 제외한다. 최대 500개 결과를 명시한다. [ReaderViewModel](../app/src/main/java/com/dongholab/pagetuner/reader/ReaderViewModel.kt), [readerSearch](../web/src/lib/readerSearch.ts), [ReaderSearch](../web/src/components/ReaderSearch.tsx) |
| 이름 있는 북마크 | 완료 | 완료 / 기기 내 | 웹 읽기 도구가 현재 문단 ID/문자 위치를 저장하고 이름 있는 북마크 목록·삭제·이동을 제공한다. 계정/문서 namespace를 분리하며 서버 동기화는 없다. [ReaderViewModel](../app/src/main/java/com/dongholab/pagetuner/reader/ReaderViewModel.kt), [readingNotes](../web/src/lib/readingNotes.ts), [ReaderTools](../web/src/components/ReaderTools.tsx) |
| 하이라이트·메모·내보내기 | 완료 | 완료 / 기기 내 | 실제 선택 범위를 원본 UTF-16 문단 위치로 저장하고 글자 크기 변경 뒤 강조를 유지한다. 북마크·메모·강조의 JSON/Markdown 내보내기와 지원 브라우저 공유를 제공한다. 서버 자동 동기화는 별도다. [readingSelection](../web/src/lib/readingSelection.ts), [readingNotes](../web/src/lib/readingNotes.ts), [ReaderTools](../web/src/components/ReaderTools.tsx) |
| 마지막 읽은 위치 | 완료 | 완료 / 기기 내 | 웹 번역 작업 결과·서버 서재·오프라인 보관은 같은 문서 ID와 공통 위치 helper를 사용한다. 과거 위치 키를 복구하며 이전 문서 ID의 메모를 계정 내에서 이전한다. localStorage 쓰기 실패 시 IndexedDB 위치를 사용한다. 서버를 통한 앱↔웹 위치 동기화는 미완이다. [LocalLibraryStore](../app/src/main/java/com/dongholab/pagetuner/library/LocalLibraryStore.kt), [translationReading](../web/src/lib/translationReading.ts), [translationReading.test](../web/src/lib/translationReading.test.ts) |
| 목록 높이에 맞춘 페이지 조작 | 완료 | 완료 | 앱 AdaptiveCollection과 웹 AdaptiveCollection이 별도 플랫폼 구현이다. 공유 정책과 공유 소스 코드는 구분한다. [Android AdaptiveCollection](../app/src/main/java/com/dongholab/pagetuner/ui/common/AdaptiveCollection.kt), [Web AdaptiveCollection](../web/src/components/AdaptiveCollection.tsx) |
| 명시적 목록 스크롤 모드 선택 | 완료 | 완료 | 웹 AdaptiveCollection은 기본 paged이며 독서 설정에서 명시적으로 scroll을 선택했을 때만 목록에 적용한다. 스크롤에서도 이전/다음 버튼이 있고 설정 화면은 페이지 방식으로 복귀 가능하다. 본문은 항상 paged다. [ListLayoutMode](../app/src/main/java/com/dongholab/pagetuner/settings/ListLayoutMode.kt), [AdaptiveCollection](../web/src/components/AdaptiveCollection.tsx), [ReaderPreferences](../web/src/components/ReaderPreferences.tsx) |
| 키보드·물리 페이지 키·터치 방향 설정 | 완료 | 부분 / 브라우저 범위 | 웹은 페이지 키 기본/반전/비활성, 양쪽 터치 영역 기본/반전/버튼 전용을 저장한다. 드래그·긴 누르기·텍스트 선택은 페이지 넘김으로 처리하지 않는다. 볼륨 키 등은 브라우저가 이벤트를 전달할 때만 지원하며 OS 전용 키 제어까지 보장하지 않는다. [readerPreferences](../web/src/lib/readerPreferences.ts), [usePageKeys](../web/src/components/usePageKeys.ts), [useReaderControls](../web/src/components/useReaderControls.ts) |
| 전체 화면·시스템 바·비트맵 전자잉크 변환 | 완료 / 기기 전용 | 부분 | 웹 리더와 PDF 리더에 Fullscreen API 진입/해제와 실패 안내가 있다. 전체 화면은 브라우저 지원·사용자 조작이 필요하다. Android 시스템 바 직접 제어·기기 비트맵 표시 변환은 웹 기능 완료에 포함하지 않는다. [ReaderFullscreenSystemBars](../app/src/main/java/com/dongholab/pagetuner/ui/reader/ReaderFullscreenSystemBars.kt), [useReaderControls](../web/src/components/useReaderControls.ts), [styles.css](../web/src/styles.css) |
| 진단 로그·수동 화면 갱신 | 완료 / 일부 기기 전용 | 부분 | 앱 진단 패널과 갱신 제어가 있다. 웹에는 작업 오류·재시도·PWA 상태가 있지만 동일한 진단 로그 화면은 없다. [DiagnosticLogPanel](../app/src/main/java/com/dongholab/pagetuner/ui/reader/DiagnosticLogPanel.kt), [DiagnosticLogger](../app/src/main/java/com/dongholab/pagetuner/common/DiagnosticLogger.kt) |

## 오프라인·계정·동기화

| 기능 | Android 앱 | 웹 대응 | 범위·근거 |
| --- | --- | --- | --- |
| 가져온 원문/번역 기기 보관 | 완료 | 완료 | 웹 원문 리더의 기기 보관과 번역 다운로드를 각각 검증 후 IndexedDB에 저장한다. 기기 보관함의 원문·번역 탭에서 연결 없이 읽으며 계정별로 분리한다. [OriginalLibrary](../web/src/components/OriginalLibrary.tsx), [personalLibrary](../web/src/lib/personalLibrary.ts), [offline.ts](../web/src/lib/offline.ts) |
| 손상된 보관 항목 복구 | 부분 | 완료 | 웹은 정상 항목과 손상 메타데이터를 분리하고 삭제/다시 받기를 제공한다. 동시에 다른 탭에서 복구한 데이터의 삭제/덮어쓰기를 막는다. 앱에는 저장 검증이 있지만 동일한 손상 목록 복구 UI는 없다. [offline.ts](../web/src/lib/offline.ts), [offline.test.ts](../web/src/lib/offline.test.ts), [App.tsx](../web/src/App.tsx) |
| 네트워크 없이 앱 껍데기·보관본 열기 | 완료 / 설치 앱 | 완료 / PWA 범위 | 온라인 첫 방문에서 공개 shell 설치가 완료된 뒤 기기에 보관한 원문·번역·로컬 파일을 연다. 로그인·새 소설 수집·번역 API는 온라인 기능이다. [serviceWorker.ts](../web/src/lib/serviceWorker.ts), [OriginalLibrary](../web/src/components/OriginalLibrary.tsx), [LocalWorkspace](../web/src/components/LocalWorkspace.tsx) |
| 서버 계정 로그인·로그아웃 | 완료 | 완료 | HTTP Basic·CSRF·사용자별 데이터 격리를 사용한다. 앱은 서버 주소를 지정하고 웹은 같은 origin 서버를 사용한다. 비밀번호를 로컬 보관 데이터에 저장하지 않는다. [HttpTranslationStore](../app/src/main/java/com/dongholab/pagetuner/translation/sync/HttpTranslationStore.kt), [ServerLibraryViewModel](../app/src/main/java/com/dongholab/pagetuner/translation/sync/ServerLibraryViewModel.kt), [api.ts](../web/src/lib/api.ts), [ServerSecurity](../server/src/main/kotlin/com/dongholab/pagetuner/server/ServerSecurity.kt) |
| 회원가입·프로필 편집 | 완료 | 완료 | 앱과 웹에서 서버 회원가입·로그인·프로필 조회·표시 이름 변경을 연결했다. 이메일 인증·OAuth는 별도 미완 범위다. [ServerLibraryScreen](../app/src/main/java/com/dongholab/pagetuner/ui/screen/ServerLibraryScreen.kt), [AccountPanel](../web/src/components/AccountPanel.tsx), [AccountController](../server/src/main/kotlin/com/dongholab/pagetuner/server/account/AccountController.kt) |
| 현재 비밀번호로 비밀번호 변경 | 완료 / 기기 UI 계측 미실행 | 완료 / API·화면 배치 검증 | 공통 API로 현재 비밀번호를 확인한 뒤 변경한다. 성공·응답 유실 시 연결을 해제하고 재로그인을 안내하며 계정 ID·언어·서재를 유지한다. 비밀번호 분실 재설정은 별도 미완 범위다. [비밀번호 변경 규격과 검증](ACCOUNT_PASSWORD_CHANGE.md) |
| 계정 UI 언어·기본 번역 언어 | 부분 / 언어팩 범위 | 완료 / ko·en 팩 | 양쪽 locale/targetLanguage를 분리 저장한다. 앱 AccountLocale은 리소스 조회를 바꾸며 기존 하드코딩 UI 전체의 번역을 뜻하지 않는다. 앱은 계정 번역 언어를 현재 설정에 적용하는 버튼을 제공한다. 웹은 다음 번역 기본값으로 쓰며 미지원 팩은 en으로 표시한다. [AccountLocale](../app/src/main/java/com/dongholab/pagetuner/ui/common/AccountLocale.kt), [AccountPanel](../web/src/components/AccountPanel.tsx), [locale](../web/src/lib/locale.ts) |
| 사용자별 원문·번역·작업 서버 영속화 | 완료 / 서버 작업 | 완료 | 서버 PostgreSQL에 원문/번역/작업이 남고 양쪽 화면이 해당 흐름을 사용한다. 앱도 원문/번역 조회·보관·게시와 서버 번역 작업을 연결했다. 앱 로컬 서재 전체 메타데이터의 동기화를 뜻하지 않는다. [SourceChapterStore](../server/src/main/kotlin/com/dongholab/pagetuner/server/workflow/SourceChapterStore.kt), [TranslationJobStore](../server/src/main/kotlin/com/dongholab/pagetuner/server/workflow/TranslationJobStore.kt), [ServerTranslationJobsPanel](../app/src/main/java/com/dongholab/pagetuner/ui/screen/ServerTranslationJobsPanel.kt) |
| 책 폴더·주석·즐겨찾기·진행 위치의 기기 간 동기화 | 미완 | 미완 | 현재 번역/원문 저장 API만으로 앱의 모든 메타데이터가 동기화되는 것은 아니다. 별도 공통 계약·사용자별 저장·충돌 정책이 필요하다. [현재 동기화 계약](TRANSLATION_SYNC.md), [앱 어댑터 범위](../app/TRANSLATION_STORE_SYNC.md) |
| Drive 자동 백업·복구 | 미완 | 미완 | 공통 백업 정책/서버 백업 관련 데이터는 전체 사용자 Drive 백업 작업자·OAuth·복구 화면의 완성을 의미하지 않는다. [core-backup](../core-backup/src/main/kotlin), [서버 README](../server/README.md) |

## 실제 남은 기능 분류

지속 작업의 우선순위·완료 조건·진행 기록은 [에이전트 작업 큐](AGENT_WORK_QUEUE.md)에서 관리한다.

- **이번에 연결한 앱의 독서 기능:** 현재 페이지/WPM 기반 읽기 번역, 원문·번역 대조, 카탈로그 캐시, 용어집 속성, 임의 범위 강조와 독서 기록 내보내기를 구현했다. 웹의 번역 실행에는 서버 연결이 필요하며, 임시 읽기 번역과 기기에 저장한 완성 번역본은 수명이 다르다. 개별 검증 근거는 검증 기록에서 확인한다.
- **기기 기능과의 대응 판단이 필요한 항목:** 디렉터리 트리 탐색·OS 파일 권한, 볼륨 키 직접 제어·시스템 바·전자잉크 비트맵 변환, Android 렌더링 loader. 웹 파일 선택·Fullscreen API를 동일한 OS 권한 기능으로 세지 않는다.
- **앱에서도 부분 또는 미완인 항목:** OCR, 사용자 CSS 규칙의 완전한 실행, Drive/FTP의 계정 설정부터 파일 열기까지의 화면, 미니앱/스크립트 플러그인 실행, Drive 자동 백업·복구. 이 항목은 웹에만 빠진 완성 앱 기능으로 설명하지 않는다.
- **양쪽을 함께 확장해야 하는 항목:** 독서 메타데이터의 서버 자동 동기화. 이번 ZIP 교환은 계정별 수동 파일 이동과 충돌 시 별도 사본 보존이며, 상시 양방향 자동 동기화가 아니다.

## 2026-09-15 교차리뷰에서 확인한 연결 경계

1. 로컬 문서 번역의 local metadata 누락을 수정했다. TXT parse→projection→upload와 PDF 실제 텍스트만 업로드하는 조합을 포함해 localUpload.test.ts 3개 테스트가 2026-09-15 00:22 KST 통과했다. 실제 브라우저의 파일 가져오기→서버 번역→보관 흐름은 별도 검증 대상이다.
2. 번역 artifact의 문서 ID·읽기 위치 키를 공통화했다. 실제 메모·오프라인 저장소 조합으로 경로 간 위치 공유, 이전 ID 메모 이전과 삭제 후 재생성 방지, 계정 격리, localStorage 실패 시 최신 IndexedDB 위치 우선을 검증했다.
3. PDF 추출 예외를 pdfTextErrorPages로 이미지 페이지와 구분한다. 실제 PDF.js 추출 실패를 주입해 저장 후에도 실패 상태가 유지되고 전체 번역은 거부되며 원본은 렌더링되는지 검증했다. 원본 페이지 렌더 성공은 모든 텍스트의 추출·번역 성공을 의미하지 않는다.

이 표는 구현 범위를 설명한다. 단위 테스트 통과와 실제 앱·브라우저·외부 사이트 검증은 별도 근거로 기록하며, 수정 중인 항목은 재검증 후 갱신한다.

## 표준 ZIP 교환

Android **Local → ZIP**, 웹 **On this device → App · Web ZIP transfer**에서 같은 `.ptlibrary.zip` 규격을 가져오고 내보낸다. [공통 규격](../contracts/library-exchange-v1.md), [앱 동작 범위](../app/LIBRARY_EXCHANGE.md), [웹 동작 범위](../web/LIBRARY_EXCHANGE.md)를 함께 확인한다.

| 기능 | 상태 | 범위 |
| --- | --- | --- |
| 원문·번역·PDF·삽화 교환 | 완료 | 문단 ID와 실제 파일 bytes 보존, 전체 ZIP 검증 후 원자적 저장 |
| 북마크·메모·강조·읽던 위치·분류·용어집 | 완료 / 표현 범위 차이 보존 | 플랫폼이 직접 편집하지 못하는 속성은 ZIP 재출력에 보존. PDF 물리 페이지 기록과 텍스트 위치를 임의 변환하지 않는다. |
| 중복·변경본 처리 | 완료 | 같은 snapshot은 기존 기록 유지, 변경본은 별도 사본. 기존 책을 덮어쓰지 않음 |
| 실제 양방향 확인 | 완료 / 브라우저 UI·Android 어댑터 | Android 출력 ZIP을 웹 UI에서 가져와 본문·북마크 확인. 웹 UI 다운로드 ZIP을 Android 저장소·리더 mapper에서 읽고 재출력 확인. Android 실기기 화면 검증은 별도 |
