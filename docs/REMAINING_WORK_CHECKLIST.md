# PageTuner 남은 작업 체크리스트

갱신: 2026-10-05 (한국 시간). 실행 순서와 상세 근거는 [작업 큐](AGENT_WORK_QUEUE.md), 플랫폼별 범위는 [기능 대응표](APP_WEB_FEATURE_MATRIX.md)를 따른다. **전체 프로젝트와 S4는 아직 미완료다.** 구현 완료와 실기기·실서비스 검증 완료는 별개로 기록한다.

## 현재 진행 단위

- [x] **B2 별도 문서 파일 내보내기**: 웹·Android TXT/Markdown 및 검증된 PDF 원본, 기존 ZIP/읽기 기록 내보내기 분리. [draft PR #48](https://github.com/gongdongho12/PageTuner/pull/48), [범위·검증](DOCUMENT_FILE_EXPORT.md). OS 다운로드 왕복과 Android 실기기 SAF는 검증 대기로 구분한다.
- [x] **S4b2b2c — 동일 PDF 원본 bytes·실제 디코더 페이지 수·표시 본문 연결** 구현·자동검사. [draft PR #46](https://github.com/gongdongho12/PageTuner/pull/46), 공통 `c9dad4a`·웹 `95e6708`·앱 `e4429e7`. 물리 Android 실행은 V3에 남는다.
- [x] PDF 검토 결함 수정: Android 계측 소스 컴파일, 부분 추출 PDF 번역 export 거절, 캐시 표시 전 원본 문맥 검사.
- [x] 공유 웹 PDF의 WebCrypto 없음·읽던 위치·도구 왕복을 실제 Chrome/PDF.js와 모의 공유 응답으로 검사. 물리 핫스팟은 별도 미검증.
- [x] 앱567 통과/16 제외·웹564·core37/runtime36, APK/lint/계측 소스/경계·실제 브라우저 결과 기록. [상세](PDF_DECODER_CONTEXT.md)
- [x] PR #46의 문서까지 포함한 **마지막 head `bdb2e0b`** [CI 성공](https://github.com/gongdongho12/PageTuner/actions/runs/37281711919) 확인(3분35초).
- [x] **B1 기본 로컬 사용 흐름**: 첫 화면의 계정 없는 파일·ZIP 진입, 독립 보관함·로컬 설정, 페이지 방식 시작 화면, 실제 PDF 보관·재열기 검증. 웹566·저장소/ZIP 격리 및 왕복 검사2개. [draft PR #47](https://github.com/gongdongho12/PageTuner/pull/47), [기본 사용 안내](QUICK_START.md).
- [ ] **기본 서버 사용 흐름의 재검증**: 현재 8080 서버가 내려가 있다. 서버를 사용할 수 있는 환경에서 회원가입→웹소설→Google 웹 번역→보관·읽기를 다시 확인한다. 이전 정책 거절을 우회하지 않는다.

## S4 — 앱 ↔ 웹 ZIP 및 원본 연결

- [ ] **S4b2b2b 실제 서버 UI 종단 검증**: ZIP PDF 업로드 → 별도 연결 → 재검증 → 읽기 → 해제. PR #45 구현·mock API 브라우저 검사는 완료했지만 실제 PostgreSQL UI 검사는 남았다. 이전 서버 시작 정책 거절은 우회하지 않는다.
- [ ] **네이티브 PDF 계정 연결**: 원본 bytes와 표시 본문을 검증한 뒤 별도 업로드·연결 UX 제공. PDF snapshot UUID로 기존 텍스트 S1/S2 API를 열지 않는다.
- [ ] **PDF 계정 물리 위치·메모 계약과 연동**: 실제 bytes hash와 디코더 문맥에 묶인 위치를 사용하고 화면 쪽 번호·wire pageCount로 추정하지 않는다.
- [ ] **EPUB 원본 보관·전달 형식·연결**: 원본 bytes 보존, 전체 proof, 앱·웹 교환 형식과 위치 대응. 파싱 뒤 원본을 버리는 경로와 ZIP v1 MIME 제약 해결.
- [ ] **S4c3a 분류 passive ZIP snapshot 계약·codec**: 정확한 DocumentIdentity, absent/present 및 명시적 빈 값, 폴더·태그 순서·즐겨찾기를 보존한다. 분류에 없는 deleted 상태를 만들지 않는다.
- [ ] **분류 최신 export·명시적 채택**: 직접 GET, 계정/origin/세대와 binding 재검증, pending/conflict 보존, 사용자 선택 후 새 mutation. 기존 로컬 organization과 분리한다.
- [ ] **S1 최신 읽던 위치의 ZIP 통합**: 정확한 문단 ID·UTF-16 offset·원본 revision/hash를 유지하고 기기 값·대기 중 값·서버 최신 값을 구분한다.
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
- [ ] **V3 Android 실기기** — 실제 PDF decoder, 계정·ZIP·SAF·회전·프로세스 재생성·번역·동기화·전자잉크 키, 핫스팟·화면 꺼짐·절전. 계측 소스 컴파일만으로 체크하지 않는다.
- [ ] **V4 OS/브라우저 차이** — 디렉터리 권한·볼륨 키·시스템 바·전자잉크 변환·OS 공유의 대체 동작과 한계, 손상 항목 복구.
- [ ] **V5 CI 검사 범위 확대** — 현재 PR CI와 별도의 로컬 전체 검사를 구분. workflow 권한이 확보되면 보존된 `portable-glossary-ci.patch`와 새 PDF PostgreSQL 검사의 CI 반영을 검토한다. 같은 권한 거절을 반복하지 않는다.

## 작업 운영

- [ ] 완료 단위마다 이 체크리스트·작업 큐·기능 대응표에 실제 증거를 함께 갱신한다.
- [ ] 검증된 작은 단위별 커밋·푸시·draft PR 갱신을 유지하고 최종 head CI를 확인한다. 자동 병합하지 않는다.
- [ ] 원본 `F:/workspace/PageTuner`의 PR34 기반 미커밋 변경, 미리보기 DB와 자격 증명을 계속 보존한다.

진행 가능한 개발 항목이 남아 있으므로 작업 루프는 계속한다. 외부 의존성만 남았을 때 필요한 조건을 기록하고 자동 실행을 일시정지한다.
