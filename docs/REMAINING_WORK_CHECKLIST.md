# PageTuner 남은 작업 체크리스트

- [x] **S1 웹 committed issuer·ZIP 옵션 연결**: [draft PR #67](https://github.com/gongdongho12/PageTuner/pull/67), base PR #66. 고정 account/origin lease·선택/옵션 세대·기본 해제·오프라인 해제·기존 최종 guard를 연결했다. 웹915/86파일·build/공유 웹/APK/경계·Chrome StrictMode12개/heldGET·Blob500항목 동등성·고정 iframe ko/en 경계 확인. [범위](PORTABLE_PROGRESS_EXPORT_UI.md).
- [ ] **S1 이번 웹 ZIP OS 저장·독립 파일 재개봉**: link.click 요청1회 뒤 download 이벤트 timeout·새 파일 미확인. Chrome 다운로드 관리 URL 정책 거절을 우회하지 않으며 사용자에게 파일 경로를 한 번 요청했다. 준비 Blob6,138bytes의 동등성과 실제 OS 저장 완료를 구분한다.

- [x] **S1 웹 최신 GET canonical ZIP composer**: [draft PR #66](https://github.com/gongdongho12/PageTuner/pull/66), base PR #65. 용어집/분류/progress 누적·canonical/fullproof/raw nonce·모든 journal await 뒤 원본 재조회·고정 authority/선택 참조. 신규40·전체웹915/86파일·build/공유 웹/APK/경계37초·production ZIP8사례 웹55,860→JVM57,234→웹 전체 동등성과 500항목 보존. fakeIndexedDB/주입 GET·PUT0/외부fetch0이며 실제 UI 다운로드는 후속이다. [범위](CANONICAL_PROGRESS_EXPORT.md).

- [x] **S1 웹 직접 최신 GET 세션 기반**: [draft PR #65](https://github.com/gongdongho12/PageTuner/pull/65), base PR #64. controller/flush 없이 readonly alias journal·GET·최종 검사, pending/queued/conflict·실패/삭제 highwater·최종 await 역전·terminal 폐기와 production 응답 검증. 신규47·웹875/85파일·typecheck/build/공유 웹/APK/경계 성공. canonical ZIP 준비·UI 발급/다운로드·Android·채택은 후속이다. [범위](FRESH_READING_PROGRESS_READER.md).

2026-10-26 갱신: [실제 QA 분류/PDF UI](LIVE_ORGANIZATION_PDF_VERIFICATION.md)와 [S1 passive ZIP 계약·codec](PORTABLE_READING_PROGRESS_SNAPSHOT.md)을 추가했다. S1 최신 GET·별도 채택과 S2·물리 Android 검증은 계속 남는다.

- [x] **S1 passive 읽던 위치 ZIP snapshot 계약·codec**: 전체 text identity·absent/null·정확한 UTF-16 anchor, legacy/sibling 보존, 명시적 한도 거절. core51/runtime61·웹828/84파일·앱726통과/16제외·APK/lint/계측 소스/공유 웹/경계·실제 ZIP8사례 교차 동등성. 직접 GET/채택 UI 완료를 뜻하지 않는다.
- [x] **실제 QA 서버의 웹 분류 채택·ZIP PDF 보관/연결 UI**: 실제 Chrome 파일 선택/명시 연결/비교/별도 확정과 PostgreSQL·HTTP 재조회, present/empty v1·absent v0·타계정404. PDF 원본598bytes/hash·별도연결/해제와 한 페이지 렌더 확인. 물리 Android·생성 PDF OS 저장·전체 S4는 미완료다.

갱신: 2026-10-25 (한국 시간). 실행 순서와 상세 근거는 [작업 큐](AGENT_WORK_QUEUE.md), 플랫폼별 범위는 [기능 대응표](APP_WEB_FEATURE_MATRIX.md)를 따른다. **전체 프로젝트와 S4는 아직 미완료다.** 구현 완료와 실기기·실서비스 검증 완료는 별개로 기록한다.

## 현재 진행 단위

- [x] **S4c3b 선행 Android 연결 세대 보완**: [draft PR #52](https://github.com/gongdongho12/PageTuner/pull/52), base PR #51. 기존 연결을 보존해 nonce/tombstone으로 이전하고 같은 UUID 재연결·늦은 읽기 오류·연결 저장 경합·용어집 export/채택을 검사했다. 관련46개·Android602통과/16제외·runtime50·APK/lint/계측 소스/경계 성공. [범위와 후속 경계](PORTABLE_BINDING_GENERATIONS.md). 최종 head CI는 큐에서 확인한다.
- [x] **S4c3b 웹 분류 직접 GET 세션**: [draft PR #53](https://github.com/gongdongho12/PageTuner/pull/53), base PR #52. controller를 열지 않는 GET과 읽기 전용 journal 검사, pending/queued/conflict·version 역행/동일 version 변경·동시 GET 역전 거절. 대상21개·전체웹685개/76파일·계약15·typecheck/build 성공. [현재 범위](PORTABLE_ORGANIZATION_EXPORT.md).
- [x] **S4c3b canonical 캡처·분류 ZIP 준비**: [draft PR #54](https://github.com/gongdongho12/PageTuner/pull/54), base PR #53. 현재 문서/실제 bytes·읽기 기록 재조회, raw nonce·실제 응답 검증, implicit 메모 sync/outbox 방지 및 분류 ZIP bytes/저장 guard 구현. 웹720개/78파일·계약15·typecheck/build·APK/공유 웹/경계 성공. [범위·비원자성 한계](PORTABLE_EXPORT_CAPTURES.md).
- [x] **S4c3b 웹 분류 ZIP 다운로드 UI**: [draft PR #55](https://github.com/gongdongho12/PageTuner/pull/55), base PR #54. 용어집 동시 포함·terminal 화면 세대·일반 ZIP 최종 guard·계정 읽기 canonical 확인. 웹740개/80파일·계약15·build/APK/경계, 실제 브라우저 OS ZIP500항목 전체 동등성과 지연 응답 ABA 거절을 확인했다. 계정 API는 mock, 화면은 DOM 측정이며 스크린샷 timeout·신규DB 종단·물리 Android 미검증을 구분한다. [범위](PORTABLE_ACCOUNT_EXPORT_UI.md).
- [x] **S4c3b Android 분류 직접 GET·ZIP/SAF 연결**: [draft PR #56](https://github.com/gongdongho12/PageTuner/pull/56), base PR #55. 용어집 동시 보존·전체 문서/nonce/journal·선택 lease와 SAF open 전후 ticket. 대상38개·앱625통과/16제외·runtime50·APK/lint/계측 소스/경계 성공. AtomicFile shared 잠금과 기존 용어집 검사 경합을 수정했다. 물리 SAF/회전/재생성과 계측 실행은 V3다. [범위](ANDROID_ORGANIZATION_EXPORT.md).
- [x] **S4c3b 웹 채택 nullable journal·새 CAS 기반**: [draft PR #57](https://github.com/gongdongho12/PageTuner/pull/57), base PR #56. null 원자 비교/쓰기·마지막 취소 가능 guard·직접 GET·새 mutation, 동일 값/빈 값 의도와 기존 pending 보존. 대상64개·웹781개/81파일·계약15·build/공유 웹/APK/경계·실제 격리 PostgreSQL 분류18개 통과. ZIP 권한 adapter/UI와 실기기 근거는 아직 없다. [범위](ORGANIZATION_ADOPTION_CAS.md).
- [x] **S4c3b 웹 분류 비교·명시적 채택**: [draft PR #58](https://github.com/gongdongho12/PageTuner/pull/58), base PR #57. canonical/full proof·readonly raw nonce·terminal 세션과 별도 최종 확인 연결. 웹808개/83파일·계약15·build/공유 웹/APK/경계, 실제 Chrome 모의 API의 PUT0→확정 PUT1·absent/빈 값·계정 ABA·unmount/client·ko/en 세로/가로 검증. 실제 서버 UI·물리 Android 근거는 아니다. [범위](ORGANIZATION_ADOPTION_UI.md).
- [x] **S4c3b Android nullable journal·새 CAS command 기반**: [draft PR #59](https://github.com/gongdongho12/PageTuner/pull/59), base PR #58. 부재/기존 빈 값 구분·전체 journal 원자 비교·직접 GET/새 UUID·최종 guard·postcommit 취소 성공 보존. 대상25개·앱650통과/16제외·runtime50·APK/lint/계측 소스/경계 성공. 아직 내부 미연결 기반이며 계측5개는 실행하지 않았다. [범위·조정 필요](ANDROID_ORGANIZATION_ADOPTION_CAS.md).
- [x] **S4c3b Android 정상 sync actor CAS/reload 조정**: [draft PR #60](https://github.com/gongdongho12/PageTuner/pull/60), base PR #59. 모든 저장 nullable CAS·지연 GET/ACK/오류와 stale 편집 거절·inactive 최신 journal/cooldown·session/account/client 명시 인계. 대상21개·앱660통과/16제외·runtime50·APK/lint/계측 소스/경계 성공. 실제 서버/실기기는 미검증이다. [범위·dispatch 한계](ANDROID_ORGANIZATION_SYNC_CAS.md).
- [x] **S4c3b text commit guard 내부 기반**: [draft PR #61](https://github.com/gongdongho12/PageTuner/pull/61), base PR #60. canonical 전체 문서·v2 raw binding 잠금/재진입 쓰기 거절·명시적 계정/선택 수명·memory-only 최종 guard를 구현했다. 대상47개·신규31개·앱691통과/16제외·runtime50·APK/lint/계측 소스/경계 성공. 실제 계정/Compose 소유자·UI는 미연결이다. [범위](PORTABLE_TEXT_COMMIT_GUARDS.md).
- [x] **S4c3b 실제 text 계정 수명 소유자**: [draft PR #62](https://github.com/gongdongho12/PageTuner/pull/62), base PR #61. VM의 verified connection/lifetime 동시 발급·입력/교체/비밀번호/로그아웃/해제의 영구 종료와 늦은 비밀번호 오류 뒤 새 로그인 보존. 대상34개·신규12개·앱703통과/16제외·runtime50·APK/lint/계측 소스/경계 성공. 실제 VM/주입 HTTP 회귀이며 Compose/실기기 근거가 아니다. [범위](TEXT_ACCOUNT_LIFETIME_OWNER.md).
- [x] **S4c3b Android 분류 비교·명시적 채택 연결**: [draft PR #63](https://github.com/gongdongho12/PageTuner/pull/63), base PR #62. 실제 authority/terminal 선택·canonical 전체 문서/raw nonce·direct verify/GET·nullable journal 최종 CAS와 별도5탭 확인 화면·postcommit 인계를 연결했다. 대상53개·신규22개·앱725통과/16제외·runtime50·APK/lint/계측 소스/경계 성공. 실제 기기 화면/신규 서버 UI 근거는 아니다. [범위](ANDROID_ORGANIZATION_ADOPTION_UI.md).
- [ ] **S4c3b 다음 검증**: Android 실제 Compose·SAF·회전·재생성. 실제 PostgreSQL의 웹 분류 별도 채택/PDF 연결 UI 종단은 2026-10-26 확인했다. 독립 구현은 S1 직접 최신 GET·별도 채택과 S2 ZIP 통합으로 이어간다.
- [x] **PR #51 실제 서버·Android HTTP 검증 및 CSRF 수정**: 사용자 승인으로 분리한 QA DB에서 회원가입·계정/서재97요청·실제 NovelBuddy/Google 91문단 및 Android HTTP adapter 왕복을 확인했다. 실제 서버의 CSRF 거절이401로 잘못 바뀌던 결함을403으로 수정했고 최신 HTTP3경로를 재확인했다. [상세와 한계](SERVER_LIVE_VERIFICATION.md)
- [x] **PR #51 오프라인 웹 업데이트 대기 안내**: 새 worker가 대기하면 작업을 보관한 뒤 같은 주소의 모든 탭을 닫고 다시 열도록 안내한다. 강제 교체·사이트 데이터 삭제 없이 기기 자료를 보존한다. 웹665개/76파일 및 중간 head `703629e` CI 성공.
- [x] **PR #51 WTR 실제 회차 가져오기**: `6f79dec`·`f7ca005`의 응답/상대 경로 수정 후 production Java reader와 실제 서버 import가129문단/HTTP200으로 통과했다. 소스52·서버237·앱586 통과/16제외 및 APK/lint/경계 성공. 카탈로그의 간헐적 외부 timeout과 전체 사이트 가용성은 구분한다. [실행 기록](SERVER_LIVE_VERIFICATION.md)
- [x] **S4c3a 분류 passive ZIP snapshot**: [draft PR #50](https://github.com/gongdongho12/PageTuner/pull/50), base PR #49. 전체 text identity·내용 proof, absent/present-empty, 태그 순서·Unicode·기존 분류·형제 확장을 보존한다. core45/runtime50/web660, 실제 웹→JVM→웹 ZIP 7사례 전체 동등성 검증. [범위·후속 경계](PORTABLE_ORGANIZATION_SNAPSHOT.md)
- [x] **S4c3a 앱 통합 회귀**: Android585 통과/16 opt-in 제외·APK/lint/계측 소스/경계 성공. JVM ZIP 왕복과 물리 기기 SAF·브라우저 다운로드는 구분한다. 최종 head CI는 PR에서 확인한다.
- [ ] **S4c3b 최신 계정 분류 export·명시적 채택 전체 검증**: 웹/Android 구현과 실제 서버 웹 채택 UI는 확인했다. Android 물리 기기 검증은 남는다.
- [x] **B2 후속 EPUB·본문 기반 PDF 구현**: [PR #49](https://github.com/gongdongho12/PageTuner/pull/49), `codex/pdf-epub-document-export`, base PR #48. 새 EPUB는 텍스트·언어·목차를 담고 원본 EPUB 복원을 주장하지 않는다. Android는 A4 PDF를 생성하고 웹은 브라우저 **PDF로 저장**·인쇄용 문서 보기로 연결한다. PDF 원본 저장과 구분하며 삽화·메모·원본 식별자는 새 문서에 포함하지 않는다.
- [x] **B2 후속 로컬 검사·EPUB 검증**: 웹 643개/75파일·계약 15개·core-content 18개, Android 최종 601개 중 585개 통과/16개 제외·APK/lint/계측 소스/모듈 경계 통과. 웹 전체 검사 후 UI·문구 수정은 typecheck/build 재확인. 실제 CJK 120문단 EPUB는 EPUBCheck 5.4.0 오류 0·경고 0이고 웹 가져오기에서 제목 포함 122문단·33쪽·마지막 본문·100% 진행률 확인. ko/en·390×844/844×390 형식/PDF 준비 패널의 넘침·문단 잘림 없음, 보이는 버튼 44px 이상 확인. [범위·검증](DOCUMENT_FILE_EXPORT.md)
- [ ] **B2 후속 실제 PDF 렌더·저장 검증**: PDF 시각 검사·브라우저 OS 저장·Android 실기기 렌더/SAF는 남았다. Blob 미리보기 이동 정책 차단은 우회하지 않는다. EPUB UI 가져오기는 같은 exporter의 Node SSR 생성 파일을 사용했으며 OS 다운로드를 거친 왕복으로 기록하지 않는다. 최종 head CI 결과는 작업 큐에서 별도로 확인한다.
- [x] **B2 별도 문서 파일 내보내기**: 웹·Android TXT/Markdown 및 검증된 PDF 원본, 기존 ZIP/읽기 기록 내보내기 분리. [draft PR #48](https://github.com/gongdongho12/PageTuner/pull/48), [범위·검증](DOCUMENT_FILE_EXPORT.md). OS 다운로드 왕복과 Android 실기기 SAF는 검증 대기로 구분한다.
- [x] **S4b2b2c — 동일 PDF 원본 bytes·실제 디코더 페이지 수·표시 본문 연결** 구현·자동검사. [draft PR #46](https://github.com/gongdongho12/PageTuner/pull/46), 공통 `c9dad4a`·웹 `95e6708`·앱 `e4429e7`. 물리 Android 실행은 V3에 남는다.
- [x] PDF 검토 결함 수정: Android 계측 소스 컴파일, 부분 추출 PDF 번역 export 거절, 캐시 표시 전 원본 문맥 검사.
- [x] 공유 웹 PDF의 WebCrypto 없음·읽던 위치·도구 왕복을 실제 Chrome/PDF.js와 모의 공유 응답으로 검사. 물리 핫스팟은 별도 미검증.
- [x] 앱567 통과/16 제외·웹564·core37/runtime36, APK/lint/계측 소스/경계·실제 브라우저 결과 기록. [상세](PDF_DECODER_CONTEXT.md)
- [x] PR #46의 문서까지 포함한 **마지막 head `bdb2e0b`** [CI 성공](https://github.com/gongdongho12/PageTuner/actions/runs/37281711919) 확인(3분35초).
- [x] **B1 기본 로컬 사용 흐름**: 첫 화면의 계정 없는 파일·ZIP 진입, 독립 보관함·로컬 설정, 페이지 방식 시작 화면, 실제 PDF 보관·재열기 검증. 웹566·저장소/ZIP 격리 및 왕복 검사2개. [draft PR #47](https://github.com/gongdongho12/PageTuner/pull/47), [기본 사용 안내](QUICK_START.md).
- [x] **기본 서버 사용 흐름의 실제 검증**: 별도 승인된 격리 DB/서버에서 회원가입→NovelBuddy 회차→Google 웹 번역91문단을 실제 API로 확인했다. 2026-10-12 실제 웹 QA 로그인→완료 번역→한국어 리더29쪽→기기 보관 성공을 확인했다. API와 화면 근거를 구분하며 모든 소스·실기기·오프라인 검증 완료라는 뜻은 아니다. [근거](SERVER_LIVE_VERIFICATION.md)
- [ ] **이번 실제 번역의 네트워크 단절 읽기**: 새 탭의 재로그인과 기기 보관본 재열기는 성공했다. 네트워크 단절 상태는 별도로 확인한다.

## S4 — 앱 ↔ 웹 ZIP 및 원본 연결

- [ ] **S4b2b2b 실제 서버 UI 종단 검증**: ZIP PDF 업로드 → 별도 연결 → 재검증 → 읽기 → 해제. PR #45 구현·mock API 브라우저 검사와 이후 승인된 QA의 Android 실제 PDF HTTP는 완료했지만 실제 PostgreSQL **UI** 검사는 남았다. 기존 자료와 QA DB를 보존하고 파괴적 검사는 별도 테스트 DB에서만 실행한다.
- [ ] **네이티브 PDF 계정 연결**: 원본 bytes와 표시 본문을 검증한 뒤 별도 업로드·연결 UX 제공. PDF snapshot UUID로 기존 텍스트 S1/S2 API를 열지 않는다.
- [ ] **PDF 계정 물리 위치·메모 계약과 연동**: 실제 bytes hash와 디코더 문맥에 묶인 위치를 사용하고 화면 쪽 번호·wire pageCount로 추정하지 않는다.
- [ ] **EPUB 원본 보관·전달 형식·연결**: 원본 bytes 보존, 전체 proof, 앱·웹 교환 형식과 위치 대응. 파싱 뒤 원본을 버리는 경로와 ZIP v1 MIME 제약 해결. 새 읽기용 EPUB 생성은 이 원본 보존·계정 연결 작업의 완료를 뜻하지 않는다.
- [x] **S4c3a 분류 passive ZIP snapshot 계약·codec**: 정확한 DocumentIdentity, absent/present 및 명시적 빈 값, 폴더·태그 순서·즐겨찾기를 보존한다. 분류에 없는 deleted 상태를 만들지 않는다. PR #50, [검증](PORTABLE_ORGANIZATION_SNAPSHOT.md).
- [ ] **S4c3b 분류 최신 export·명시적 채택 전체 검증**: PR #55~63으로 웹/Android 구현, 2026-10-26 실제 서버 웹 채택 UI 확인. pending/queued/conflict·canonical/raw nonce 경계는 자동 검사와 구분하며 물리 Android는 남는다.
- [ ] **S1 최신 읽던 위치의 ZIP 통합**: passive 계약·codec·웹 GET-only reader/canonical composer와 committed issuer/옵션 연결 완료(PR #64~67). 이번 OS 파일 저장·독립 재개봉, Android 직접 GET/SAF 및 별도 채택 adapter/UI, nullable journal/정상 actor CAS 조정은 후속이다. 정확한 문단 ID·UTF-16 offset·원본 revision/hash를 유지하고 기기/대기/서버 최신 값을 구분한다.
- [ ] **S2 최신 북마크·메모·강조의 ZIP 통합**: 항목 ID·삭제 정보·범위를 보존하고 명시적 채택·충돌 처리를 연결한다.
- [ ] 최신 기록의 **앱 → 웹 → 앱 / 웹 → 앱 → 웹** 왕복 검증. 자격 증명·binding·CAS/outbox mutation을 ZIP에 넣지 않는다. 전체 extensions 256 KiB 초과는 절단 없이 거절한다.

## 이후 개발 큐

- [ ] **W1 웹 로컬 번역 캐시의 서버 업로드·복원** — 전체 문단·revision 검증, 미완성/제공자 충돌 처리, 앱 재조회.
- [ ] **W2 웹 진단 화면** — 민감정보 제거·크기 제한 로그, 오류 분류, 복사/내보내기·삭제, 작은 화면 페이지 탐색.
- [ ] **W3 번역 작업 제어·묶음 범위** — 일시정지/재개, 현재 20회차 제한 후속, 서버 큐·재시작·중복·취소 검증.
- [ ] **A1 이메일 인증·비밀번호 분실 복구** — 단회/만료 토큰, 계정 노출 방지, 메일 adapter, 앱·웹 화면. 실제 발송은 서비스 설정 필요.
- [ ] **A2 OAuth 로그인** — 공급자·redirect·계정 연결/충돌 계약과 앱·웹 구현. 실제 OAuth client 설정 필요.
- [ ] **L1 Android 언어팩 확장** — 고정 문자열 리소스화, ko/en 누락 검사, 새 언어 등록·fallback·화면 검증.
- [ ] **R1 Drive·FTP 연결** — 계정 연결·폴더 탐색·파일 열기. Drive OAuth와 테스트 서버 의존성 분리.
- [ ] **R2 Drive 자동 백업·복구** — R1 이후 정책·충돌·취소·재시도·미리보기·완전 복구.
- [ ] **C1 사용자 CSS 수집 규칙** — selector 실행·편집·미리보기·오류, 서버 검증, 공통 fixture.
- [ ] **C2 동적 사이트 수집** — 지원 사이트 범위, 필요한 JS 렌더링, 시간·자원·네트워크 한도.
- [ ] **D1 이미지 PDF OCR** — 이미지/텍스트 혼합, 페이지별 상태, 언어·취소·복구, 검색·번역 연결.
- [ ] **D2 EPUB 삽화·레이아웃** — 장별 첫 두 이미지 제한, 지원 형식, 페이지 처리·오프라인·ZIP 왕복.
- [ ] **P1 미니앱·스크립트 실행** — 실행 화면, 권한·자원·저장·오류 복구, 공통 plugin 계약.

## 검증·외부 의존성

- [ ] **V1 용어집 실제 화면** — JSON 파일 왕복, 별칭·조사·강조 선택, 종류/활성 편집 회귀.
- [ ] **V2 유료 번역 서비스** — 실제 DeepSeek·Google Cloud·OpenAI 호환 키로 연결·전체 번역·읽기·재시도. fixture 성공과 구분.
- [ ] **V3 Android 실기기** — 실제 PDF decoder와 생성 PDF의 한글·여백·전체 본문, 새 EPUB 가져오기·SAF 저장, 계정·ZIP·회전·프로세스 재생성·번역·동기화·전자잉크 키, 핫스팟·화면 꺼짐·절전. 계측 소스 컴파일만으로 체크하지 않는다.
- [ ] **V4 OS/브라우저 차이** — 생성 PDF 인쇄 렌더·전체 페이지·OS 저장, EPUB 다운로드·재가져오기, 디렉터리 권한·볼륨 키·시스템 바·전자잉크 변환·OS 공유의 대체 동작과 한계, 손상 항목 복구. 이번 브라우저의 Blob 미리보기 이동 차단과 준비 패널 검사를 구분한다.
- [ ] **V5 CI 검사 범위 확대** — 현재 PR CI와 별도의 로컬 전체 검사를 구분. workflow 권한이 확보되면 보존된 `portable-glossary-ci.patch`와 새 PDF PostgreSQL 검사의 CI 반영을 검토한다. 같은 권한 거절을 반복하지 않는다.

## 작업 운영

- [ ] 완료 단위마다 이 체크리스트·작업 큐·기능 대응표에 실제 증거를 함께 갱신한다.
- [ ] 검증된 작은 단위별 커밋·푸시·draft PR 갱신을 유지하고 최종 head CI를 확인한다. 자동 병합하지 않는다.
- [ ] 원본 `F:/workspace/PageTuner`의 PR34 기반 미커밋 변경, 미리보기 DB와 자격 증명을 계속 보존한다.

진행 가능한 개발 항목이 남아 있으므로 작업 루프는 계속한다. 외부 의존성만 남았을 때 필요한 조건을 기록하고 자동 실행을 일시정지한다.
