# PageTurner 남은 작업과 에이전트 루프

사용자 지시(2026-09-16): 남은 일을 목록으로 관리하고, 다음 작업을 매번 묻지 않고 에이전트 루프로 진행한다. 서버·공통 계약·Android·웹을 함께 완성하고 검증 가능한 커밋과 PR을 계속 만든다.

이 파일이 다음 실행의 작업 큐다. [기능 대응표](APP_WEB_FEATURE_MATRIX.md)는 기능 범위의 근거이며, 코드·테스트와 맞지 않는 오래된 계획은 그대로 완료 판단에 쓰지 않는다.

## 실행 규칙

1. 현재 브랜치·작업 트리·기존 PR·이전 실행 기록부터 확인한다. 진행 중인 변경을 먼저 마무리하고 사용자 변경을 덮어쓰지 않는다.
2. 아래 우선순위에서 의존성이 준비된 항목을 선택한다. 큰 항목은 공통 계약 → 서버 → 앱/웹 소비자 → 교차 검증으로 나누며, 독립적인 하위 작업은 에이전트에 맡긴다. 같은 파일과 Gradle 출력 디렉터리를 동시에 수정/빌드하지 않는다.
3. 구현 → 독립 검토 → 문제 수정 → 필요한 테스트와 화면 검증 → 논리적 커밋 → draft PR 생성/갱신 → 큐와 기능 대응표 갱신을 반복한다. 기존 `codex/` PR 체인을 확인해 적절한 base를 사용한다. PR을 자동 병합하지 않는다.
4. 다음 항목 진행이나 통상적인 Git 작업을 다시 승인받지 않는다. 사용자가 직접 해야 하는 인증·키 제공·실기기 등 실제 의존성이 있으면 구체적인 조건을 한 번 기록하고, 독립적으로 가능한 다른 항목을 계속한다. 키·설정의 존재만으로 실제 서비스 검증을 완료로 표시하지 않는다.
5. 성공 조건을 충족한 기능만 완료로 표시한다. 구현 완료, 자동 검사 완료, 브라우저 검사, 실제 기기/실서비스 검증을 구분한다. 플랫폼에서 불가능한 항목은 검증된 대체 동작과 한계를 명시한다.
6. 오래된 동일 상태 보고를 반복하지 않는다. 완료된 기능·새 PR·실패·사용자 행동이 필요한 변경이 있을 때 알린다. 진행 가능한 항목이 모두 끝나면 반복 실행을 중지한다. 외부 의존성만 남았다면 그 목록을 정리하고 자동 실행을 일시정지한다.

## 우선순위 큐

상태: `진행` / `대기` / `검증 대기` / `외부 검증 대기` / `완료` / `플랫폼 확인`.

| ID | 순서 | 상태 | 항목 | 완료 조건·의존성 |
| --- | --- | --- | --- | --- |
| H1 | 사용자 우선 | 완료 | 핫스팟에서 폰 서재를 웹으로 읽는 공유 모드 | 공통 읽기 계약·HTTP runtime·Android 공유 서비스·APK 공통 웹 리더 연결. 앱 416개·HTTP 20개·웹 360개 통과, 실제 사설 IP HTTP에서 WebCrypto/SW 없이 TXT/PDF 확인. 저장 원문·완성 번역·PDF/삽화의 읽기 범위. 실제 Android 핫스팟·화면 꺼짐은 V3. [범위·검증](LOCAL_LIBRARY_SHARING.md) |
| S1 | 1 | 완료 | 서버 원문·번역의 읽기 위치 자동 동기화 | 공통 API·앱/웹 연결·영속 대기열·충돌 선택·계정/지연 응답 검사 완료. 실제 브라우저+서버 복원/전송/복구 확인. Android 실기기는 V3, 독립 로컬/ZIP 대응은 S4. [검증](READING_PROGRESS_SYNC.md) |
| S2 | 2 | 완료 | 북마크·강조·메모 동기화 | 서버 문서의 항목 UUID/version·삭제 표식, 양방향 CRUD·충돌 보존·영속 대기열 검증 완료. 기존 로컬/ZIP 기록 보존, 새 서버 기록의 ZIP 통합은 S4, 앱 실기기는 V3. [검증](READING_NOTES_SYNC.md) |
| S3 | 3 | 완료 | 폴더·태그·즐겨찾기·용어집·읽기 설정 동기화 | S3a 계정 읽기 설정, S3b1 개별 서버 문서 분류([검증](LIBRARY_ORGANIZATION_SYNC.md)), S3b2a 서버 서재 전체 검색/필터, S3b2b 소스 책 즐겨찾기 완료. S3c 용어집까지 완료. 각 계정 범위·삭제/충돌 정책·앱/웹 화면 반영; 독립 로컬/ZIP 연결은 S4 |
| S3b2a | S3 하위 | 완료 | 서버 서재 전체 제목 검색·분류 필터 | 원문/번역 전체의 조건별 페이지·개수, 계정/종류 격리, 공통 계약과 앱/웹 검색 화면. 서버 152개·공통 7개·웹 367개·앱 424개 통과, 실제 30개 중 14개 일치·12개 서버 배치 경계와 390×844/844×390 편집기 검증. [범위](SERVER_LIBRARY_FILTERS.md) |
| S3b2b | S3 하위 | 완료 | 소스 책 즐겨찾기 계정 동기화 | 원래 provider/book 식별자, 명시적 기기 채택, CAS/삭제/불변 변경 이력, 앱·웹 영속 대기열. 서버 164개·공통 11개·웹 385개·앱 442개 통과. 실제 오프라인 재시작·다른 클라이언트와 충돌·선택 및 WTR-LAB 계정 추가/목차 재진입 확인. Android 실기기는 V3. [범위·검증](SOURCE_BOOK_FAVORITES_SYNC.md) |
| S3c | S3 하위 | 완료 | 용어집 계정 동기화 | 원래 책 식별자·언어 범위, 항목 ID/배열 순서/원문/번역/별칭/종류/대소문자/활성 상태 보존. 스냅샷 CAS·명시적 채택·삭제/충돌·영속 대기열, 앱/웹 편집과 실제 번역 연결. displayTerm/kind 변경과 번역 fingerprint 구분. 공통 15개·서버 177개·웹 404개·앱 460개 및 실제 웹/Google 번역 검증. 독립 로컬/ZIP 대응은 S4. [범위·근거](BOOK_GLOSSARY_SYNC.md) |
| U1 | 3a | 완료 | 화면에 맞춘 목록·본문 페이지 채움과 잘림 수정 | 양쪽 8행 제한 제거·실측 행/버튼·키 anchor·부족 공간 안내, 웹 DOM/폰트 재측정·도구 배치·가로 화면 개선, Android canonical ID를 유지한 별도 표시 페이지·고정 글꼴·정확한 서버 anchor 연결 완료. 웹 345개·앱 394개·공통 번역 42개와 실제 브라우저 검증 성공. Android 계측 실행은 V3, EPUB 이미지 누락은 D2. [검증](VIEWPORT_PAGINATION.md) |
| S4 | 4 | 진행 | 로컬·ZIP 문서의 동기화 식별자 | S4a 원본 식별·검증, S4b 명시적 서버 연결/anchor 대응, S4c 최신 S1~S3 기록의 ZIP 왕복으로 나눠 진행. 전체 내용·revision·언어 일치 없이 병합하지 않음 |
| S4a | S4 하위 | 완료 | ZIP 원본 식별자 보존과 서버 동일성 확인 | 공통 provenance/ordered 문단 digest와 원래 provider/book/chapter/revision 보존, 현재 계정 서버 문서의 읽기 전용 확인. 서버 185개·웹 416개·앱 469개와 실제 웹→공통 Android runtime→웹 ZIP 왕복 확인. 자동 연결은 S4b, 실기기는 V3. [근거](PORTABLE_DOCUMENT_IDENTITY.md) |
| S4b | S4 하위 | 진행 | 검증된 로컬·ZIP 문서의 명시적 서버 연결 | S4b1 텍스트 ZIP 연결 완료. PDF/EPUB 자산·물리 위치와 원본 proof 없는 기기 파일은 S4b2에 남김 |
| S4b1 | S4b 하위 | 완료 | 검증된 텍스트 ZIP의 계정 기록 연결 | 계정·origin·사본·전체 identity별 명시적 binding, 읽기 전 재검증, 로컬/계정 기록 분리, S1~S3 canonical reader. 서버 190개·앱 475개·웹 428개 및 실제 웹 보존/삭제 검증. [근거](PORTABLE_DOCUMENT_BINDING.md) |
| S4b2 | S4b 하위 | 진행 | 자산 문서와 독립 기기 파일의 안전한 연결 | S4b2a 공통 전체 내용 proof와 정확한 anchor 검증 완료. 서버 자산 저장·재검증과 앱/웹 명시적 연결은 S4b2b 후속. 현재 거절 범위는 유지하고 페이지로 추정하지 않음 |
| S4b2a | S4b2 하위 | 완료 | 전체 파일·본문·자산 proof와 위치 계약 | Kotlin/웹 동일 framed SHA-256, 전체 원본 bytes와 ordered 자산 참조, proof-bound UTF-16/PDF anchor. 순수 계약·검증 단위이며 계정 연결을 활성화하지 않음. [근거](PORTABLE_CONTENT_PROOF.md) |
| S4c | S4 하위 | 대기 | 최신 동기화 기록의 ZIP 내보내기·가져오기 | 현재 위치·메모·분류·원래 ID와 언어 범위가 있는 용어집의 무손실 스냅샷, 충돌/대기 상태 구분, 자격 증명·CAS/outbox mutation 이전 금지 |
| W1 | 5 | 대기 | 웹 로컬 번역 캐시의 서버 업로드·복원 | 전체 문단 검증, 원문 revision 대응, 미완성/다른 제공자 충돌 처리, 앱에서 재조회 |
| W2 | 6 | 대기 | 웹 진단 화면 | 민감정보 제거된 제한 크기 로그, 번역/수집/동기화 오류 구분, 복사/내보내기·삭제, 작은 화면 페이지 탐색 |
| W3 | 7 | 대기 | 번역 작업 제어와 묶음 범위 개선 | 앱·웹 일시정지/재개 동작 통일 가능 범위, 현재 20회차 제한의 서버 큐 확장, 재시작·중복 제출·취소 검증 |
| A1 | 8 | 대기 | 계정 이메일 인증·비밀번호 분실 복구 | 검증된 메일 주소 등록, 단회/만료 토큰, 계정 노출 방지, 발송 adapter와 앱/웹 복구 흐름; 실제 발송은 메일 서비스 설정 필요 |
| A2 | 9 | 대기 | OAuth 로그인 | 공급자·리디렉션·계정 연결/충돌 규격, 앱·웹 연결, 실제 OAuth client 설정 의존성 분리 |
| L1 | 10 | 대기 | Android 고정 문자열과 언어팩 확장 | 문자열을 리소스로 이전, ko/en 누락 검사, 추가 언어팩 단계별 등록·fallback·레이아웃 검증 |
| R1 | 11 | 대기 | Drive·FTP 연결 | 관련 클래스에서 계정 연결→폴더 탐색→파일 열기까지 실제 앱·웹 경로, OAuth/테스트 서버 분리 |
| R2 | 12 | 대기 | Drive 자동 백업·복구 | R1 이후 백업 정책·충돌·취소·재시도·복구 미리보기·완전 복구 검증 |
| C1 | 13 | 대기 | 사용자 CSS 수집 규칙 | 저장된 selector 실제 실행, 규칙 편집·미리보기·실패 안내, 서버 요청 검증, 앱·웹 동일 fixture |
| C2 | 14 | 대기 | 동적 사이트 수집 범위 | 지원 사이트를 명시하고 필요한 JS 렌더링 경로·시간/자원 제한·네트워크 검증; 임의 모든 사이트 지원으로 표시하지 않음 |
| D1 | 15 | 대기 | 이미지 PDF OCR | 이미지/텍스트 혼합 구분, 페이지별 상태, 언어·취소·복구, OCR 텍스트의 읽기·검색·번역 연결 |
| D2 | 16 | 대기 | EPUB 삽화와 레이아웃 보완 | 현재 장별 첫 두 이미지 제한·형식 지원 검토, 페이지 처리·오프라인/ZIP 자산 왕복 검증 |
| P1 | 17 | 대기 | 미니앱·스크립트 실행 | 실제 실행 화면·권한/자원 경계·저장·실패 복구, 공통 plugin 계약과 양쪽 지원 범위 확인 |
| V1 | 병행 | 검증 대기 | 용어집·별칭 실제 화면 검증 | CRUD·종류·활성·별칭·조사·강조 선택·JSON 왕복을 브라우저에서 확인하고 발견 결함 수정 |
| V2 | 병행 | 외부 검증 대기 | 유료 번역 실제 서비스 | DeepSeek·Google Cloud·OpenAI 호환의 실제 키로 연결·전체·읽기·재시도 검증; 현재 구현/fixture 검증 완료와 구분 |
| V3 | 병행 | 외부 검증 대기 | Android 실기기 검증 | 계정·ZIP·번역·동기화·전자잉크 페이지/키 동작; 테스트 장치 존재부터 확인 |
| V4 | 병행 | 플랫폼 확인 | 웹과 OS 기능 차이 | 디렉터리 접근·볼륨 키·시스템 바·전자잉크 변환·OS 공유의 브라우저 대체 동작과 지원 한계, 손상 항목 복구 앱 대응 |
| V5 | 병행 | 대기 | PR CI와 전체 검사 연결 | 매 PR 실제 CI 결과 확인·회귀 수정, 기존 전체 검사 workflow 활성화는 GitHub workflow 권한 의존성; 현재 로컬 전체 검사 유지 |

## 이미 연결된 기준 기능

WTR/NovelBuddy 수집·서버 번역·원문/번역 서재·Google 웹 번역 실호출, 4개 제공자 공통 구현, 표준 ZIP 교환, 회원가입·언어 설정·비밀번호 변경은 기존 PR 체인에 있다. 제공자 구현이 실제 유료 키 검증을 뜻하지는 않는다. 계정 비밀번호 변경은 PR #23, 기반 목록 페이지 수정은 PR #22다.

## 현재 실행 기록

- 2026-09-16: 큐 생성. 시작 브랜치 `codex/reading-progress-sync`, base `codex/account-password-change`(PR #23). 첫 단위 S1의 서버 공통 계약과 Android 연결 작업을 분담하고 웹 동기화 저장소·리더 연결을 진행한다.
- 반복 실행: automation ID `pageturner`, 상태 `ACTIVE`, 현재 작업에 연결된 매시간 실행. 2026-09-16 생성 결과와 저장 설정을 확인했다. 로컬 실행에는 컴퓨터와 데스크톱 앱이 켜져 있어야 한다.
- 2026-09-16: PR #23 Android CI 통과 확인. 미리보기의 현재 비밀번호 변경은 유지하고 S1을 이어간다.
- 2026-09-16: S1 구현·독립 검토·회귀 수정 완료. 서버 99개, 웹 249개, Android 313개 통과(기존 외부/실기기 opt-in 15개 제외). 웹 프로덕션 빌드·Android APK 빌드 성공. 실제 브라우저에서 원문/번역 위치 복원, 충돌 양쪽 선택, 원문·번역 왕복, 서버 중단 중 이동 후 리더 종료 상태의 자동 재전송, 390×844 화면을 확인했다.
- S1 draft PR: [#24](https://github.com/gongdongho12/PageTuner/pull/24), head `codex/reading-progress-sync`, base `codex/account-password-change`. 구현 커밋은 서버 `2964c71`, 웹 `9bb2bef`, 앱 `7c2b901`. 다음 단위 브랜치는 이 head를 기반으로 만들고 PR #24를 먼저 병합하지 않는다.
- S1 이후 계획: **S2 북마크·강조·메모**를 공통 항목 식별자·version·삭제 표식부터 진행했다. S1의 오프라인 대기열·명시적 복원·계정 격리 원칙을 재사용하고 ZIP 교환 모델과 Android 문단 매핑을 대조했다.
- 2026-09-16 02:38 KST 자동 실행: PR #24 최종 `f75375d` CI 통과 확인(3m11s), 작업 트리 깨끗함. `codex/reading-notes-sync`를 PR #24 기반으로 생성하고 S2를 시작했다. 서버·Android·웹 저장소를 나눠 진행한다. 기존 비UUID ZIP/로컬 메모는 그대로 보존하며 서버 문서 식별자 매칭은 S4로 유지한다.
- 2026-09-30 자동 실행: 중단돼 있던 S2 미완성 변경을 이어서 완료했다. PR #24 CI 성공 상태 재확인. 공통/서버 `baf4682`, 웹 `abda54b`, 웹 복구 보완 `bb06f12`, Android `219ccab`, 기존 ZIP 저장 API 23 호환성 `c991dd0`으로 분리했다.
- S2 검증: 서버 115개(2026-09-16 이후 코드 변경 없음), 웹 276개·8개 생성 계약·프로덕션 빌드, Android 337개 통과/기존 opt-in 15개 제외·APK·lint·instrumentation 소스 컴파일 성공. lint 오류 0/경고 75/힌트 4. 실제 웹/서버 양방향 변경·삭제·정확한 강조·원문/번역 격리·오프라인 충돌 유지와 복구·390×844 화면을 확인했다. 앱 실기기와 서버 기록의 ZIP 통합은 완료로 세지 않는다. [상세 근거](READING_NOTES_SYNC.md).
- S2 draft PR: [#25](https://github.com/gongdongho12/PageTuner/pull/25), head `codex/reading-notes-sync`, base `codex/reading-progress-sync`(PR #24). 최종 `c73a1ec` Android CI 성공(2m52s), 2026-09-30 21:57 KST 재확인. [실행](https://github.com/gongdongho12/PageTuner/actions/runs/36713343259).
- 2026-09-30 21:57 KST 자동 실행: 작업 트리와 PR #25 CI 정상 확인, `codex/reader-preferences-sync`를 생성했다. S3를 검증 가능한 세 단위로 나누고 첫 단위 S3a의 계정 읽기 설정 공통 계약·서버·Android·웹을 병행 구현한다. 폰트 크기·행간(정수 백분율)·여백·터치 방향·목록 표시를 공유하고 플랫폼별 글꼴·페이지 키와 번역 공급자 설정은 유지한다. 첫 연결은 기기/서버 설정을 직접 선택한 후 자동 동기화한다.
- S3a 구현·검토 완료: 서버 `592149e`, 웹 `c46965b`, Android `f2c6321`. 서버 128개, 웹 298개·9개 생성 계약·프로덕션 빌드, Android 358개 통과/기존 opt-in 15개 제외·APK·lint·계측 소스 컴파일 성공. lint 오류 0/경고 75/힌트 4. 독립 검토의 저장 오류 표시, 계정 전환 프레임, API 23 timestamp와 본문 측정 보완을 반영했다.
- S3a 실제 검증: 최초 GET이 기본값을 만들지 않고 명시적 기기 선택 후 저장됨을 확인했다. 별도 HTTP 클라이언트의 변경이 웹에 자동 반영됐으며 서버 중단 중 글자 29 변경 → 화면 종료·서버 재시작 → 원격 글자 21 변경 → 재로그인 후 충돌 유지 → 서버 값 선택을 확인했다. 390×844에서 양쪽 선택이 보이며 설정 재배치 후에도 문단 10과 기존 강조가 유지됐다. 앱 실기기는 V3에 남긴다. [상세 근거](READER_PREFERENCES_SYNC.md).
- S3a draft PR: [#26](https://github.com/gongdongho12/PageTuner/pull/26), head `codex/reader-preferences-sync`, base `codex/reading-notes-sync`(PR #25). 구현·로컬 전체 검사·웹/서버 실제 검증 완료. 원격 Android CI는 이 PR의 최신 head 결과를 확인한다.
- 다음 실행은 S3a PR의 최신 CI와 작업 트리를 확인하고 **S3b 폴더·태그·즐겨찾기 동기화**를 진행한다. 서버 문서와 소스 책의 식별자를 구분하고 기존 기기 분류를 보존하며 공통 계정 모델·삭제/충돌 정책부터 정의한다. 독립 로컬/ZIP 문서 대응은 S4로 유지한다. 이후 S3c 용어집을 진행하며 동일 파일 편집과 Gradle 빌드를 조정한다. 별도 진행 승인을 묻지 않는다.
- 2026-10-02: PR #26 head `1ed7c8a` Android CI 성공 및 새 리뷰 없음 재확인. `codex/library-organization-sync`에서 S3b1을 공통 계약·서버·Android·웹으로 구현했다. 일시적인 읽기 전용/Windows 명령 실행 오류가 해소된 뒤 웹 독립 검토의 늦은 409 덮어쓰기와 오래된 초안에 의한 재전송 정지 결함을 수정했다.
- S3b1 최종 검증: 서버 145개, 웹 332개·계약 10개·프로덕션 빌드, Android 375개 통과/기존 opt-in 15개 제외·APK·lint·계측 소스 컴파일 성공. 서버 동시 저장/삭제 8개 경쟁 시나리오와 웹 두 탭/지연 응답·편집기 종료 후 outbox 재전송 회귀를 포함한다. 실제 웹에서 최초 선택·원격 변경·오래된 초안 거절·오프라인 충돌 복원·기기 선택·명시적 초기화·쉼표 태그·긴 CJK 값/390×844 화면을 확인했다. [범위와 근거](LIBRARY_ORGANIZATION_SYNC.md).
- S3b1 서버 `cc43c49`, Android `c89fc18`, 웹 `761161d`, 검증 문서 `6a506ca`로 논리 단위를 분리했다. [draft PR #27](https://github.com/gongdongho12/PageTuner/pull/27)은 `codex/library-organization-sync` → `codex/reader-preferences-sync`(#26)이며 자동 병합하지 않는다. 원격 Android CI는 이 PR의 최종 head에서 확인한다. 모듈 경계 검사도 성공했다.
- 다음 단위는 **U1 화면 채움/잘림 개선**을 우선 진행한다. S3b2와 S3c가 끝났다는 뜻은 아니며 앱 실기기는 V3, 독립 로컬/ZIP 문서 연결은 S4에 남긴다.
- PR #27 최종 `1f36946`의 [Android CI](https://github.com/gongdongho12/PageTuner/actions/runs/36999648260)가 성공했다(2m43s). `codex/viewport-pagination`에서 U1을 시작하며 웹, Android 공통 목록, Android 본문 표시 페이지를 나눠 구현한다. 원본 canonical 페이지/segment ID·번역 캐시·동기화 identity는 보존하고 화면용 페이지를 분리한다.

## 검증 환경

- 작업 폴더 `F:/workspace/PageTuner`. 기존 미리보기 `127.0.0.1:8080`, 정적 파일 `web/dist`.
- 서버 자동 검사는 별도 PostgreSQL `pagetuner_test_20260915_rolling` 또는 새 격리 테스트 DB를 사용한다. 미리보기 `pagetuner_test`에 파괴적인 테스트 fixture 정리를 실행하지 않는다.
- 기존 `.gradle-home`의 JDK21/Android SDK/로컬 PostgreSQL 설정을 재사용하되 경로·프로세스를 실행 때 확인한다. 비밀번호나 키는 이 파일에 적지 않는다.
- 미리보기는 빌드 출력과 분리한 실행용 JAR 사본을 사용한다. 실행 중 JAR가 교체되면 클래스 로딩이 실패할 수 있으므로 재시작 전에 실제 프로세스 명령줄과 활성 작업 수를 확인하고 해당 미리보기 프로세스만 교체한다.
- 2026-10-02 U1 구현·독립 검토·회귀 수정 완료: Android 목록 `6976339`, 본문/서버 anchor `1d8552b`, 웹 `383f9e3`. 웹 345개·계약 10개·프로덕션 빌드, 앱 394개 통과/기존 opt-in 15개 제외, 공통 번역 42개·APK·lint·계측 소스 컴파일·모듈 경계 성공. lint 오류 0/경고 77/힌트 4. 실제 모바일/데스크톱/가로 화면, 최대 글꼴·행간·여백의 5쪽 무손실 이동, Google 번역 응답 중 편집 초안 유지, 동일 언어 요청 전 안내를 확인했다. [상세 근거](VIEWPORT_PAGINATION.md). 연결된 Android 장치가 없어 계측 실행은 V3로 유지한다.
- 다음 작업은 최신 PR CI와 작업 트리를 확인한 후 **S3b2 서버 서재 전체 분류 검색/필터·소스 책 즐겨찾기**, 이어서 **S3c 용어집 동기화**다. S4 식별자 설계 없이 기존 로컬/ZIP 문서를 서버 문서와 임의 병합하지 않는다. 진행 가능한 항목이 남으므로 반복 실행을 종료/일시정지하지 않는다.
- U1 draft PR: [#28](https://github.com/gongdongho12/PageTuner/pull/28), head `codex/viewport-pagination`, base `codex/library-organization-sync`(#27). 최종 head의 Android CI를 확인한다. 자동 병합은 하지 않는다.
- 2026-10-02 H1 사용자 우선 요청: PR #28 `22e49e2` CI 성공 확인 후 `codex/local-library-sharing`에서 구현. 공통/runtime `9fb480b`, 공통 웹 리더 공유 entry `63978d6`, Android 저장소/서비스/빌드 `4917e05`로 분리했다. [draft PR #29](https://github.com/gongdongho12/PageTuner/pull/29)의 base는 `codex/viewport-pagination`(#28)이며 자동 병합하지 않는다.
- H1 검증: 앱 416개 통과/기존 opt-in 15개 제외(102 suite), 공유 HTTP 20개, 웹 360개/48파일·생성 계약 11개, APK·lint·계측 소스 컴파일·모듈 경계 성공. lint 오류 0/경고 76/힌트 4. APK 웹 자산 189개(4,644,747바이트)가 소스 빌드와 SHA-256 일치. 실제 합성 서재에서 TXT/검색·PDF·EPUB 삽화·75권/50권 경계·연결 해제·ko/en·390×844/844×390 확인. 사설 IP HTTP의 `isSecureContext=false`, WebCrypto/SW 없음에서도 페어링/TXT/PDF 동작을 확인했다. 실제 폰 연결 장치는 없어 핫스팟/Doze 검증은 V3에 남기고 테스트 공유 서버는 종료했다. [상세](LOCAL_LIBRARY_SHARING.md).
- H1 이후 다음 실행: **PR #29 최종 head CI와 작업 트리부터 확인**, 의존성이 준비된 **S3b2 서버 서재 전체 분류 검색/필터·소스 책 즐겨찾기**, 이어서 **S3c 용어집 동기화**를 진행한다. H1 구현을 다시 만들지 말고 기기 검증을 구현 완료와 구분한다.
- 2026-10-02 21:49 KST 자동 실행: PR #29 최종 `7ff8964`의 [Android CI](https://github.com/gongdongho12/PageTuner/actions/runs/37008530726)가 성공했다(3분36초). 작업 트리와 리뷰가 비어 있음을 확인하고 `codex/server-library-filters`를 생성했다. S3b2를 문서 조회 S3b2a와 소스 책 식별자/동기화 S3b2b로 나눠 첫 단위의 공통 모델·서버·Android·웹을 병행 구현한다. 원문/번역 서버 서재 전체에 제목·정확한 폴더/태그·즐겨찾기를 적용하고 그 뒤 페이지를 계산한다.
- S3b2a 구현·교차 검토·브라우저 결함 수정 완료: 서버/공통 `f4c4561`, Android `3a3bbb6`, H1 셸 설치 실패 수정 `bc14d61`, 웹 `3400ce8`. [draft PR #30](https://github.com/gongdongho12/PageTuner/pull/30)은 `codex/server-library-filters` → `codex/local-library-sharing`(#29)이며 자동 병합하지 않는다. 서버 152개/25 suite·공통 모델 7개, Android 424개 통과/기존 opt-in 15개 제외(103 suite), 웹 367개/49파일·계약 11개 성공. APK·lint·계측 소스 컴파일·모듈 경계 성공(lint 오류 0/경고 77/힌트 4).
- S3b2a 실제 검증: 독립 8081 fixture의 원문/번역 각각 30개에서 첫 서버 페이지 뒤의 14개를 AND 조건으로 찾아 12개 배치 경계·새로고침·빈 결과·초기화·원문 읽기를 확인했다. 390×844 한/영과 844×390 필터/오류 표시를 실측했다. 공유 HTML 401로 오프라인 셸 업데이트가 실패하던 기존 결함을 수정하고 기존 캐시에서 새 셸 설치·다음 방문 활성화를 확인했다. 검증 서버/탭은 종료했고 미리보기 8080은 활성 작업 0개 확인 후 갱신했다. [상세 근거](SERVER_LIBRARY_FILTERS.md).
- 다음 실행은 **PR #30 최종 head CI부터 확인한 뒤 S3b2b 소스 책 즐겨찾기 계정 동기화**, 이어서 S3c 용어집을 진행한다. S3b2a를 다시 만들지 않는다. 물리 Android 검증은 V3, 독립 로컬/ZIP 대응은 S4에 남기며 진행 가능한 큐가 있어 자동 실행을 유지한다.
- S3b2b 시작: PR #30 최종 `93ecbaf`의 [Android CI](https://github.com/gongdongho12/PageTuner/actions/runs/37013357097) 성공(2분56초), 리뷰·작업 트리가 비어 있음을 확인하고 `codex/source-book-favorites-sync`를 생성했다. 서버/계약·Android·웹을 병행 구현한다. 기존 source API의 `sourceId`/`bookId`를 보존하고 Android 화면용 `remoteId`와 구분한다. 기기 즐겨찾기는 명시적으로 연결하며 계정별 삭제 표식·CAS 충돌·영속 대기열을 검증한다. 검증된 작은 단위마다 커밋·푸시하고 PR #30 위에 draft PR을 쌓는다.
- 2026-10-03 S3b2b 완료: 공통 `0f5068a`, 서버 `6869300`, URL 검증 보완 `d91fb2a`/`76c28e0`, 기존 메모 테스트의 IO/UI 경합 수정 `5107fc9`, Android `4ddd2a8`, 웹 `1dd1872`로 커밋·푸시했다. [draft PR #31](https://github.com/gongdongho12/PageTuner/pull/31)의 base는 `codex/server-library-filters`(#30)이며 자동 병합하지 않는다.
- S3b2b 검증: 서버 164개/26 suite·공통 모델 11개, Android 442개 통과/기존 opt-in 15개 제외(106 suite), 웹 385개/51파일·생성 계약 12개 성공. APK·lint·계측 소스 컴파일·모듈 경계 성공(lint 오류 0/경고 79/힌트 4). PR 초기 서버 head `6869300` CI 성공, 이후 `d91fb2a`에서 기존 메모 테스트의 비동기 UI 반영 경합을 발견해 수정했고 전체 앱 테스트를 다시 통과했다. 최종 head CI는 PR 검사에서 확인한다.
- S3b2b 실제 검증: 격리 DB의 54권/50+4 고정 watermark, mutation 재전송·삭제·409·복원 확인. 웹 오프라인 삭제 → 브라우저 종료 → 다른 클라이언트 수정 → 재로그인 충돌 → 기기 값 선택 → 서버 version 5 tombstone을 확인했다. 실제 WTR-LAB 책 한 권의 기기 저장·명시적 계정 연결·동기화와 1,539회 목차 재진입도 확인했다. 390×844 ko/en 관리 모달, 844×390 전체 행/페이지 버튼과 모달·Escape 복귀를 검증했다. [상세 근거·한도](SOURCE_BOOK_FAVORITES_SYNC.md).
- 다음 단위 **S3c 용어집**: PR #31 최종 head CI와 작업 트리부터 확인한다. 기존 `BookGlossaryShareCodec`는 ID를 재생성/절단할 수 있으므로 계정 동기화 직렬화로 재사용하지 않는다. 전체 ordered entries의 원본 ID·별칭·종류·활성·대소문자를 보존하는 계약부터 정의한다. Android 로컬 bookId는 원본 provider/book과 다르며, 서버 원문/번역의 실제 source identity를 우선 사용하고 독립 로컬/ZIP 매핑은 S4에 남긴다. 웹 workflow의 200개 제한과 앱 저장의 500개 한도 차이는 명시적으로 처리하고 조용히 잘라 저장하지 않는다. H1/Android 실기기 검증은 연결 장치가 없어 V3, 유료 API는 V2이며 자동 실행은 유지한다.
- 2026-10-03 S3c 시작: PR #31 최종 `40a6dd3`의 [Android CI](https://github.com/gongdongho12/PageTuner/actions/runs/37089327354)가 3분12초에 성공했고 리뷰·작업 트리가 비어 있음을 확인했다. `codex/book-glossary-sync`를 만들고 공통 계약/서버, Android, 웹을 나눠 구현한다. 원래 provider/book과 대상 언어별 전체 500항목 스냅샷을 보존하고 기존 workflow에는 명시적 항목 ID를 후방 호환 방식으로 전달한다. 계정 저장 범위와 번역 실행 한도를 구분하며 최초 선택·오프라인·삭제·충돌 및 표시 전용 수정의 fingerprint 불변을 검증한다.

- 2026-10-03 S3c 완료: 공통/서버 `bde4929`, 웹 `e17d1e4`, Android `eeb3501`, 좁은 화면/웹 outbox 회귀 `660dac2`로 분리해 커밋·푸시했다. [draft PR #32](https://github.com/gongdongho12/PageTuner/pull/32)의 base는 `codex/source-book-favorites-sync`(#31)이며 자동 병합하지 않는다. 서버 head `bde4929` CI 3분32초, 웹 head `e17d1e4` CI 3분34초, Android head `eeb3501` [CI](https://github.com/gongdongho12/PageTuner/actions/runs/37091787747) 2분41초 성공을 확인했다. 이후 문서/화면 수정 포함 최종 head의 CI도 PR 검사에서 확인한다.
- S3c 최종 로컬 검사: 공통 모델 15개, 서버 177개/27 suite, 웹 404개/54파일·생성 계약 13개, Android 460개 통과/기존 외부 opt-in 15개 제외(109 suite). APK·lint·계측 소스 컴파일·모듈 경계 성공(lint 오류 0/경고 79/힌트 4). 최종 작은 화면 CSS를 APK 공유 자산에 다시 묶었다. 독립 검토에서 크기 초과 대기열 고착, 웹 삭제/기기 복귀·최대 버전 ACK·과거 ID 누적, Android 반복 거절 초안 상태를 수정하고 회귀를 추가했다.
- S3c 실제 검증: 격리 8081 HTTP에서 500항목·100/2,000/200 CJK 원본 식별자를 손실 없이 왕복하고 재전송·삭제·409·언어 격리를 확인했다. Google Web으로 합성 원문 2문단을 번역해 원래 항목 ID와 fingerprint를 확인했다. 웹에서 명시적 계정 선택, 원문/번역 ko 범위, 별칭·종류·활성·대소문자 CRUD, 전체 삭제와 원격 복원, 오프라인 편집→탭 종료→별도 클라이언트 변경→재로그인 충돌 비교→기기 선택(version 6)을 확인했다. 계정→기기 모드 복귀, 390×844 한/영과 844×390 가로 배치도 검증했다. [상세 근거](BOOK_GLOSSARY_SYNC.md).
- 미리보기 8080은 활성 번역 작업 0개와 실행 JAR/PID를 확인한 뒤 새 서버로 갱신했고 health UP, 공유 HTML 200, V13 마이그레이션 성공을 확인했다. 연결 Android 장치가 없어 물리 핫스팟/Doze 검증은 V3, 유료 공급자 실호출은 V2다. V1의 계정 CRUD/별칭 검증은 진행했으나 파일 JSON·조사·강조 선택 전체 회귀는 아직 완료로 표시하지 않는다.
- 다음 실행: **PR #32 최종 head CI·미완성 변경을 먼저 확인한 뒤 S4 로컬/ZIP 문서 동기화 식별자**를 진행한다. 원문 revision/전체 내용 hash/언어를 바탕으로 명시적 동일성 검증을 설계하고 PDF/EPUB/번역·서버 기록/ZIP 재출력의 표현 범위를 구분한다. 제목이나 화면 페이지로 자동 병합하지 않는다. S3c를 다시 만들지 않고 진행 가능한 큐가 남으므로 반복 실행을 유지한다.

- 2026-10-03 사용자 확인: 전체 개발 완료가 아니라 S3c까지 완료한 상태임을 명확히 했다. PR #32 최종 `5dba8f7` [CI](https://github.com/gongdongho12/PageTuner/actions/runs/37092054617) 성공(3분35초)과 깨끗한 작업 트리를 재확인하고 `codex/portable-document-identity`에서 S4를 시작했다. S4a/b/c 완료 범위를 나누며 이번 첫 단위의 동일성 검증이 자동 동기화 완성을 뜻하지 않는다. 앱 ZIP의 원본 provider/book/chapter 누락과 웹/앱의 서로 다른 passive metadata 구조를 먼저 공통화한다.

- 2026-10-03 S4a 완료: 공통/runtime `c19fd24`, Android `9428039`, 웹 `861752c`, 세션/언어팩 `277d53e`, 서버 `927a4b2`, 작은 화면 문구 `1f1f9d1`로 나눠 커밋·푸시했다. [draft PR #33](https://github.com/gongdongho12/PageTuner/pull/33)의 base는 `codex/book-glossary-sync`(#32)이며 자동 병합하지 않는다. 서버 head `927a4b2` [CI](https://github.com/gongdongho12/PageTuner/actions/runs/37093995897) 성공(3분1초)을 확인했고 이후 최종 head CI는 PR 검사에서 확인한다.
- S4a 검증: core-backup 6개·backup-runtime 16개·격리 PostgreSQL 서버 185개/29 suite·웹 416개/56파일·계약 14개·Android 469개 통과/기존 opt-in 15개 제외(111 suite). APK·lint(오류 0/경고 79/힌트 4)·계측 소스 컴파일·모듈 경계 성공. 공유 웹 자산 189개/4,671,706 bytes의 SHA-256 일치. 실제 HTTP 원문/번역 확인, 불일치/없는 문서 거절, 웹 다운로드→앱 공통 runtime 왕복→웹 가져오기/원문·번역 재확인, legacy 읽기 보존·변경 본문 거절, 390×844 한/영·844×390 가로 배치를 확인했다. [범위와 후속 설계 경계](PORTABLE_DOCUMENT_IDENTITY.md).
- 전체 개발과 S4 전체는 아직 미완료다. 다음 실행은 **PR #33 최종 head CI와 작업 트리부터 확인한 뒤 S4b 명시적 연결/anchor 대응**, 이어서 **S4c 최신 계정 기록 ZIP 통합**이다. 성공한 identity 확인을 영속 binding이나 자동 동기화 권한으로 재사용하지 않는다. 기존 note bind의 자동 전송·마이그레이션 삭제, 문단 끝/빈 문단 anchor 한도, 언어가 없는 원문 dedup 키, ZIP 전체 JSON의 사본 hash와 원본 identity 차이를 먼저 해결한다. 제목/로컬 ID/페이지로 합치지 않는다. V2/V3 외부 검증 및 나머지 큐가 남아 있어 자동 실행을 유지한다.

- 2026-10-03 루프 재개: 최신 PR #34 `5ddc97f`의 [CI](https://github.com/gongdongho12/PageTuner/actions/runs/37096009317) 성공(3분49초)을 확인하고 `codex/portable-document-binding`에서 S4b1을 구현했다. 서버 point note 계약, Android, 웹을 분담하고 독립 검토 후 비동기 취소·오프라인 기기 읽기·용어집 언어·정확한 끝/빈 문단 메모를 보완했다. 기기와 서버 기록은 별도 canonical reader로 보존하며 연결 시 기존 기록을 업로드/삭제하지 않는다.
- S4b1 최종 검사: 서버 190개/30 suite, Android 475개 통과/기존 opt-in 15개 제외(112 suite), 웹 428개 통과/1개 제외(59파일), API 계약 14개·typecheck·production/sharing build·APK·lint·계측 소스 컴파일 성공. lint 오류 0/경고 80/힌트 4. 실제 격리 8081/임시 DB·390×844 웹에서 명시적 연결만으로 기록 행이 생성되지 않음, 새 계정 메모만 서버 저장, 가져온 기기 메모 별도 보존, 서버 문서 삭제 거절 이후 로컬 읽기/메모 보존을 확인했다. 연결 장치가 없어 실기기는 V3. [상세](PORTABLE_DOCUMENT_BINDING.md).
- 다음 실행은 이번 draft PR의 최종 head CI·작업 트리를 확인한 뒤 **S4b2 자산/독립 기기 문서의 proof·anchor 계약**을 진행한다. S4b1을 다시 구현하거나 텍스트 연결만으로 S4b 전체를 완료 처리하지 않는다. S4c 최신 계정 기록 ZIP 통합은 여전히 대기이며 독립적으로 가능한 범위를 병행할 수 있다. 기존 root의 `.local/`, `frontend/`, `scripts/`는 이전 로컬 배포 브랜치에서 남은 파일로 이번 PR에 넣지 않는다.
- 반복 실행 설정 갱신: 기존 `pageturner`를 이 채팅의 최신 개발 큐용 heartbeat로 갱신했다(매시간, ACTIVE). 기존 `pageturner-2` 로컬 개선은 별도 유지하며 같은 작업 트리의 변경을 보존한다. GitHub MCP 인증이 실패하여 이번 PR 조회/생성은 저장소의 기존 `gh` 인증으로 진행한다. 다른 자동화가 작성한 `.gitignore`와 `docs/LOCAL_IMPROVEMENT_LOG.md` 변경은 이번 커밋에서 제외한다.
- S4b1 draft PR: [#35](https://github.com/gongdongho12/PageTuner/pull/35), base `codex/source-language-and-position-identity`(#34). 서버 `5436d82`, Android `88f5980`, 웹 `e1a7b77`, 근거 `bd9a8ba`. 자동 병합하지 않는다. 최종 head CI를 확인하고 다음 실행에서 미완성 변경부터 점검한다. 임시 8081 서버·5173 웹·검증용 DB·테스트 탭은 종료했다.
- S4b1 CI 후속: `b46ca55`의 Android CI에서 읽기 위치 rate-limit 테스트가 가상 시간과 실제 IO 실행기의 경합으로 종료 시간 초과가 발생했다. 저장소 dispatcher를 주입하고 가상 시간 테스트 4개를 같은 scheduler로 실행하도록 수정했다. 해당 테스트와 Android 전체 단위 테스트 재검증 성공, 독립 검토에서 문제 없음. 실제 앱의 기본 IO 실행기는 유지하며 수정 head CI를 확인한다.

### 2026-10-03 사용자 후속 요청

S4b1 재구현 없이 공유 실제 로컬 책 확인, 공유 본문 선택 방지, 내장 폰트, Gemini 공통 제공자 지원을 진행했다. 일반 계정 메모 선택과 기존 사용자 데이터를 보존한다. Typesafe Jev는 텍스트 생성 API가 아니며 추가하지 않는다. S4b2/S4c는 기존 미완료 상태를 유지한다. 최종 검증 결과는 후속 기록에 남긴다.

후속 최종 자동 검증: 웹 432개 통과/1개 제외, 공유 HTTP 20개, 번역 runtime 45개, 서버 191개 통과, Android 475개 통과/15개 기존 opt-in 제외. APK·계측 소스·lint 빌드 성공. Gemini 추가에 따른 loopback 예상 호출 수와 raw HTTP framed-body 읽기 경합을 수정하고 재검증했다. 공유 폰트 라이선스도 실제 Android HTTP 200으로 확인했다. 수정 후 Chrome 터치/물리 볼륨 확인과 최신 CI는 별도 대기 상태다.

- 2026-10-03 17시 heartbeat: PR #35 최종 `54236c48`의 [Android CI](https://github.com/gongdongho12/PageTuner/actions/runs/37107551444)가 4분13초에 성공했다. 다른 채팅의 `.gitignore`, `frontend/`, `scripts/`, 로컬 개선 기록을 보존하고 `codex/portable-content-proof`에서 S4b2a 선행 계약을 분리했다. 공통 Kotlin·웹 구현과 독립 검토를 병행했고 같은 Gradle 출력을 동시에 빌드하지 않았다. 이전 공유 Chrome 터치/볼륨 확인은 답변이 없어 미검증 상태를 유지하며 만료된 공유 코드를 재사용하지 않는다.
- S4b2a 검증 완료: core-backup 14개, backup-runtime 16개, 웹 443개/60파일·계약 14개/typecheck/build, Android 475개 통과/15개 기존 opt-in 제외 및 APK/lint. 전체 원본 bytes·ordered 참조의 Kotlin/웹 증명을 독립 Python 벡터 3개와 대조했다. proof 자체 metadata 변조와 정확한 위치 계약을 독립 검토로 보완했다. 다음 S4b2b는 실제 서버 자산 보관·전체 재검증과 명시적 연결 UI이며 이번 단위로 연결 지원이 완료됐다고 표시하지 않는다.
- S4b2a [draft PR #36](https://github.com/gongdongho12/PageTuner/pull/36): `codex/portable-content-proof` → `codex/portable-document-binding`(#35). 공통/fixture `d1a0d2dd`, 웹 `823d127e`, 근거/CI `d759589b`를 분리해 push했다. 자동 병합하지 않는다. 최신 head CI를 확인하며 후속 S4b2b를 진행한다.
