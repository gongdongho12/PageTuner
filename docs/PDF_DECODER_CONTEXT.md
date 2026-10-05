# 같은 원본 bytes의 PDF 디코더 문맥

2026-10-05, S4b2b2c. [전체 내용 계약](../contracts/portable-content-proof-v1.md)과 [PDF 명시적 연결](PDF_CONTENT_BINDING.md)의 후속이다. 실제 디코더에 전달한 원본 bytes, 보정 전 페이지 수, 그 디코더의 표시 본문을 같은 읽기 수명에 묶는다. PDF의 계정 위치·메모 동기화 API를 추가하거나 기존 text binding/S1~S3 권한을 열지 않는다.

## 입력과 페이지 수

- 공통 `PdfDecoderInput`은 크기를 검사한 뒤 입력을 복사하고 전체 SHA-256을 계산한다. 호출자의 배열과 디코더에 건넨 배열을 변경해도 보관한 원본은 바뀌지 않는다. 이 도우미 자체는 PDF 디코더가 아니며 임의의 wire pageCount를 검증해 주지 않는다.
- Android는 보관한 bytes를 private 임시 파일에 쓰고 해시를 확인한 뒤 read-only descriptor를 열어 파일 이름을 unlink한다. `PdfRenderer`가 실제 반환한 페이지 수만 사용한다. 렌더링도 원래 URI를 다시 열지 않고 같은 불변 입력에서 새 descriptor를 만든다. 0쪽을 1쪽으로 보정하지 않는다.
- 웹은 같은 불변 입력에서 PDF.js worker용 복사본과 원본 해시를 만든다. 살아 있는 PDF.js handle의 실제 페이지 수로 이동·범위를 검사한다. worker의 buffer transfer는 원본을 detach하지 않는다. 취소·문서/계정 변경·close 이후의 문맥과 늦은 렌더는 사용할 수 없다.
- 제한은 앱 원본 32 MiB/50,000쪽/추출 문자 5,000,000 UTF-16, 웹 원본 32 MiB/2,000쪽/추출 문자 5,000,000 UTF-16이다. 계정 PDF API의 decoded 4 MiB/upload 8 MiB/GET 12 MiB/receipt 2 MiB 제한은 별개로 유지한다. 모든 크기 PDF 지원이 아니다.

## 표시 본문과 저장 기록

Android `PdfDecodedSnapshot`은 원본 hash와 실제 페이지 수, 페이지/segment ID·본문·순서와 추출 가능 상태를 묶는다. API 35 미만의 추출 미지원이나 추출 예외를 성공적으로 읽은 빈 페이지와 구분한다. 기존 title/URI 기반 reader ID는 호환용 식별자로 남으며 원본 proof가 아니다. 닫기·reader 교체 시 이전 snapshot은 무효화한다.

네이티브 Android PDF export는 저장소의 bounded 원본을 읽어 디코딩하고, 다시 읽은 bytes와 저장된 원본 hash·페이지 메타데이터를 검사한다. export 준비 때문에 책을 열거나 비례 위치 보정을 실행하지 않는다. 원본이나 저장 기록이 다르면 기록을 보존하며 명시적으로 거절한다. 번역 ZIP은 새로 확인한 디코더의 텍스트 추출이 Complete인 경우만 허용한다. Partial/Unavailable 페이지를 제외한 일부 캐시를 완성 번역으로 내보내지 않는다. 원본 PDF export와 읽기는 가능하다.

웹 네이티브 PDF는 같은 원본에서 재추출한 문단 ID·본문과 페이지별 추출 상태가 저장본에 정확히 대응할 때만 기존 기기 읽기 도구를 사용한다. ZIP canonical 문단은 별도 보존한다. canonical 문단이 0개나 3개인 2쪽 PDF도 실제 2쪽을 읽으며 `pdf-view:i` 같은 가짜 텍스트 anchor를 만들지 않는다. 해당 문맥은 메모리에만 존재하며 ZIP으로 내보내지 않는다.

폰 공유의 일반 HTTP에서도 PDF를 읽을 수 있도록 WebCrypto 없는 SHA-256 경로를 제공한다. 공유 문서는 실제 Blob의 원본 hash·페이지 수로 읽지만 이를 계정 identity나 텍스트 동기화 권한으로 사용하지 않는다.

## 검증과 남은 경계

core-backup 37개, backup-runtime 36개, Android 567개 통과/16 opt-in 제외, 웹 564개/70파일이 통과했다. 웹 API 생성 검사·typecheck·production build, Android APK·lint·계측 소스 컴파일·모듈 경계도 성공했다. lint 오류 0개/경고 87개/힌트 4개다. 새 Android decoder JVM 검사는 9개이며 실제 PdfRenderer를 사용하는 계측 소스 2개는 컴파일만 했다. 초기 계측 소스의 Closeable 타입 오류는 명시적 finally close로 수정했다.

실제 Chrome/PDF.js에서 독립 생성한 814-byte/2쪽 PDF를 열었다. 0개/3개 canonical 문단과 잘못된 99쪽 ZIP metadata가 있어도 실제 2쪽을 탐색하고 원래 ZIP 저장 row는 전후 동일했다. 네이티브 파일 가져오기·읽기 도구도 확인했다. caller 배열 변경, 0쪽 PDF, 범위 밖 위치, close 후 문맥을 거절했으며 Node SHA-256과 원본 digest가 같았다. 390×844·844×390의 4개 화면 경계를 검사했다.

별도 공유 웹에서 WebCrypto를 제거한 상태로 실제 2쪽 PDF를 읽고 최초 2쪽 복원 → 도구 화면 왕복 후 2쪽 유지 → 1쪽으로 이동 → 책 재열기 후 1쪽 유지를 확인했다. 이 검사는 모의 공유 HTTP 응답을 사용하는 실제 브라우저 검사이며 물리 핫스팟 검증은 아니다. Android가 공유 문서 ID를 opaque ID로 바꿔도 원래 hash-bound pN projection을 읽는 회귀는 실제 PDF.js 단위 검사에 포함했다. 일반 ZIP은 이 방문 내 매핑을 사용하지 않는다.

기존 PDF 계정 연결 브라우저 회귀도 재실행했다. **모의 API**에서 응답 유실→같은 uploadId/content 재시도→별도 연결→재검증 후 읽기→해제, 다른 언어·4 MiB 초과 거절, ko/en 28개 화면 경계가 통과했다. 이번 단위에서 실제 PostgreSQL UI 검증이나 Android 실기기 실행을 한 것은 아니다.

재현 자료는 원본 checkout의 ignored `.gradle-home/pdf-decoder-{core,android-targeted,full,web-verify}.log`, `pdf-decoder-browser-qa.mjs`, `pdf-decoder-browser-result.json`, `pdf-decoder-sharing-qa.mjs`, `pdf-decoder-sharing-result.json` 및 기존 `pdf-binding-browser-qa.mjs`에 있다. 브라우저 API는 모두 mock으로 종결했고, 검증용 Vite proxy는 사용하지 않는 8082를 향한다. 미리보기 DB를 테스트하지 않았다.

[draft PR #46](https://github.com/gongdongho12/PageTuner/pull/46)은 PR #45의 `codex/explicit-pdf-binding` 위에 쌓았다. 공통 `c9dad4a`, 체크리스트 `d6bbfa7`, 웹 `95e6708`, Android `e4429e7`을 각각 커밋·푸시했다. 문서를 포함한 마지막 head CI는 PR에서 확인하며 자동 병합하지 않는다.

PDF snapshot UUID를 받는 계정 위치/메모 API, 네이티브 PDF의 별도 계정 업로드·연결 UX, EPUB 원본 bytes/전달 형식, 최신 S1/S2/분류 ZIP 통합은 남는다. S4b2b2b 실제 서버 UI 종단 검증은 이전 실행의 서버 시작 정책 거절 이후 미실행 상태이며 우회하지 않았다. V1/V2/V3/V5와 전체 S4는 완료되지 않았다.
