# ZIP PDF의 명시적 서버 보관과 기기 연결

2026-10-05, S4b2b2b. [PDF API](PDF_CONTENT_STORAGE.md), [손실 없는 준비·소비자](PDF_CONTENT_CLIENTS.md), [전체 내용 proof](PORTABLE_CONTENT_PROOF.md)를 소비하는 앱·웹 화면이다. PDF 연결은 별도 기기 메타데이터이며 text `ServerReadingDocument`, S1/S2/분류/용어집 동기화 권한을 만들지 않는다. 전체 S4는 미완료다.

## 사용 흐름

ZIP으로 가져온 PDF의 서버 보관·연결 진입에서 다음 작업을 별도로 선택한다.

1. 최신 ZIP의 canonical 문서와 실제 자산 bytes를 같은 저장소 읽기에서 준비한다. 원래 언어 대소문자, 빈 문단, ID/본문, ordered 자산 참조를 그대로 유지한다.
2. **새 서버 사본 보관**은 현재 계정으로 업로드한다. 결과가 불명확하면 열린 작업의 고정된 내용·동일 uploadId로 사용자가 재시도한다. 새 패널을 여는 것은 새 요청이며 이전 서버 사본을 자동 검색하거나 합치지 않는다.
3. **기존 서버 사본 연결**은 정확한 record UUID로 GET하고 실제 서버 bytes의 proof를 다시 계산·비교한다. 업로드 receipt만으로 연결하지 않는다.
4. **이 기기 연결 확정**은 별도 선택이다. 서버 GET과 최신 로컬 내용, 기존 binding nonce, 현재 계정/origin/client/선택 세대를 다시 검사한 뒤 저장한다.
5. **내용 재검증 후 읽기**도 서버 GET과 최신 로컬 내용·nonce를 확인한 뒤 기기의 PDF decoder로 읽는다. 기존 기기 읽기는 별도로 사용할 수 있다.
6. **기기 연결 해제**는 새 nonce의 tombstone을 남긴다. ZIP/서버 사본/독서 기록은 삭제하지 않는다. 다른 탭이나 오래된 화면의 연결 복구는 거절한다.

## 저장·수명 경계

- 웹 IndexedDB v4의 `pdfContentBindings`는 username/origin/copyId와 전체 proof를 묶는다. final readwrite transaction에서 최신 ZIP row와 binding row를 동시에 비교한다. 계정/선택 변경의 AbortSignal은 이미 열린 transaction도 취소한다. unlink tombstone으로 A→B→A 이력을 보존한다.
- Android는 기존 text binding과 별도 `.pdfbinding` 파일을 쓴다. bounded binary 형식의 strict UTF-8, 길이/nullable marker/EOF/full proof 검사를 적용한다. library lock 안에서 최신 ZIP bytes와 nonce를 검사하고 임시 파일을 atomic replace한다. 잠금 순서는 selection gate → account → library → binding이다.
- Android는 reader 쓰기 완료 후 준비하고, 요청·응답과 실제 버튼 callback의 session을 검사한다. client close/reconnect와 선택 세대 변경 이후 응답은 연결이나 읽기에 사용하지 않는다. PDF 캐시도 bounded read로 확인하고 atomic replace한다.
- binding, 계정 자격 증명, outbox/CAS mutation은 ZIP으로 옮기지 않는다. 서버 본문은 새 text 문서 UUID로 변환하지 않는다.
- 서버 JSON decoder도 트리 할당 전에 총 32,768개 값·배열 4,096개 항목을 제한한다. 기존 중복 필드/후행 token 검사와 byte 한도는 유지한다.

## 한도와 화면

PDF decoded 자산 합계 4 MiB, upload 8 MiB, GET 12 MiB, receipt/verify 2 MiB, 계약의 metadata/항목 한도를 그대로 적용한다. 큰 ZIP은 기기에 남으며 보관 준비만 명시적으로 거절한다. 모든 크기의 PDF 지원이 아니다. 기본 HTTP 8 MiB 제한을 전역 확대하지 않는다.

앱은 페이지형 안내·메타데이터·작업 탭을, 웹은 기존 PDF 행에서 별도 전체 높이 패널을 연다. 웹의 전체 값은 원문을 변경하지 않고 짧은 표시 조각으로 나누며 개행/CRLF/탭을 escape로 표시한다. 한국어·영어 문구를 제공한다.

## 검증 기록

앱/웹의 별도 binding, 업로드 재시도 고정 내용, 최신 bytes·nonce·세대 경합, 잘못된 원본/손상/한도 거절을 자동 검사했다. 웹 전체 554개/68파일, 계약 15개·typecheck·production build가 통과했다. Android 전체 558개 통과/16 opt-in 제외, core-backup 34개, backup-runtime 36개, APK·lint·계측 소스·모듈 경계가 성공했다. lint 오류 0개/경고 88개/힌트 4개이며 언어 리소스의 재구성 오류를 수정한 뒤 재검사했다. 서버 JSON 사전 할당 회귀 4개와 security 7개도 격리 DB 없이 통과했다. 계측 소스 컴파일은 실제 Android UI 실행 증거가 아니다.

실제 Chrome + **모의 API**에서 ZIP import → 응답 유실 → 같은 uploadId/content 재시도 → 연결 전 binding 없음 → 별도 연결 확정 → 서버 재검증 후 PDF.js 1쪽 렌더 → 해제 tombstone을 확인했다. 같은 PDF bytes라도 원래 언어가 다른 사본의 연결을 거절하고, 4 MiB 초과 ZIP을 보존하면서 서버 요청 없이 거절했다. 현재 계정의 영어 프로필로 재로그인한 화면도 검사했다. 한국어/영어 390×844·844×390의 28개 화면 단계에서 본문/버튼 경계와 44px 이상 버튼을 검사했고 브라우저 예외는 없었다. 이 증거는 실제 PostgreSQL/API 통합 검증이 아니다.

로컬 재현 자료는 `F:/workspace/PageTuner/.gradle-home/pdf-binding-browser-qa.mjs`와 `pdf-binding-browser-qa-result.json`, `pdf-binding-web-final.log`, `pdf-binding-full.log`, `pdf-binding-server-parser.log`에 있다. 브라우저 harness는 모든 `/api/**` 요청을 가로채며 알려지지 않은 요청은 mock 404로 끝낸다. 검증용 Vite proxy도 사용하지 않는 8082를 향해 있어 8080 미리보기 API에 전달하지 않는다.

이번 격리 PostgreSQL·QA 서버 시작 명령은 자동 승인 검토에서 `blocked by policy`로 거절되어 실행되지 않았다. 기존 PR42/43의 실제 API 근거를 이번 UI 통합의 실제 서버 검증으로 재사용하지 않는다. preview DB에는 접근하거나 파괴적 검사를 하지 않았다.

## 남은 범위

- 이번 UI와 실제 서버를 함께 사용하는 종단 검증, 물리 Android의 SAF/회전/재생성/decoder/절전은 미검증이다. 이번 `adb devices -l`에도 연결 장치가 없었다.
- PDF의 서버 동기화 anchor는 활성화하지 않는다. 물리 위치는 정확한 bytes를 실제 decoder로 연 `VerifiedPdfContext`가 필요하며 wire pageCount나 ZIP metadata로 추정하지 않는다.
- 네이티브 PDF 표시 본문과 원본 hash 연결, EPUB 원본 bytes·전달 형식, 최신 S1/S2/분류의 ZIP 통합은 후속이다.
- V1 용어집 JSON/조사/강조 화면, V2 유료 API, V3 실제 기기, V5 workflow 권한 의존성은 그대로 남는다.
