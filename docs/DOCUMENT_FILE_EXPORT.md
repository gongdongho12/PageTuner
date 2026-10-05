# 별도 문서 파일 내보내기

2026-10-05. [draft PR #48](https://github.com/gongdongho12/PageTuner/pull/48), base `codex/local-reader-start`(#47). ZIP 교환과 별도로 선택한 문서의 읽기용 TXT·Markdown 또는 보관된 원본 PDF를 저장한다. 서버 연결이나 계정 API 호출은 필요 없다.

## 사용 경로

- 웹: 첫 화면 **파일 내보내기**, 또는 **이 기기 보관 → 파일 내보내기**에서 책을 선택한다. **읽기 메뉴 → 읽기 도구 → 책 파일 내보내기**에서도 같은 패널을 연다. 형식을 선택해 파일을 준비한 뒤 **파일 저장**을 누른다.
- Android: **Local → ZIP → 책의 파일 내보내기**에서 형식을 고르고 시스템 파일 저장 화면에서 위치를 선택한다. ZIP과 번역 포함 ZIP도 같은 하위 패널에 유지한다.
- 웹 읽기 도구의 기존 메모·북마크 JSON/Markdown은 **읽기 기록 내보내기 · 공유**로 구분한다.

## 지원 범위

| 형식 | 내용 | 한계 |
| --- | --- | --- |
| TXT | 제목·회차 제목과 선택 문서의 전체 보관 본문, UTF-8/BOM 없음 | 한 회차 문서는 해당 회차만 저장. 다른 회차를 자동 수집·합치지 않음 |
| Markdown | 제목 heading과 본문. ASCII 문장부호를 escape해 본문을 문법으로 실행하지 않음 | 원래 Markdown/EPUB 레이아웃 복원이 아닌 읽기용 사본 |
| PDF | 내용 hash를 확인한 보관 원본 bytes 그대로 | TXT→PDF 변환이나 부분 추출 본문 export가 아님. 원본이 없거나 검증 실패 시 거절 |

TXT·Markdown은 삽화·메모·읽기 위치·계정·binding·동기화 대기열을 포함하지 않는다. EPUB는 읽기용 spine 본문만 포함하고 이미지/CSS/레이아웃은 포함하지 않는다. PDF는 삽화 등을 포함한 원본 파일 전체다. 기록·식별자를 옮길 때는 기존 ZIP을 사용한다. 웹은 원문·번역·로컬 파일·가져온 ZIP 보관함을 계정별로 읽으며 서버 전용 자료는 먼저 기기에 보관해야 한다. Android는 로컬 파일 또는 가져온 ZIP의 저장 본문을 내보낸다.

기존 파서가 보관 전에 변환한 웹 텍스트를 원래 업로드 파일 bytes와 같다고 주장하지 않는다. Android의 저장된 TXT/Markdown은 페이지 분할 전 UTF-8 본문을 사용하고, EPUB는 전체 spine을 페이지 분할 전에 읽는다. ZIP은 canonical 문단을 순서대로 사용한다. 양쪽의 동일 제목·문단 입력은 [공통 규격](../contracts/document-file-export-v1.md)과 JSON fixture로 같은 파일을 만든다.

## 크기·오류·대기 처리

- 제목 포함 입력 5,000,000 UTF-16 code unit, 100,000문단, 출력 12,000,000 code unit 이하. 잘못된 surrogate·지원하지 않는 제어문자·한도 초과는 자르지 않고 거절한다.
- 원본 PDF는 최대 32 MiB. PDF 서버 업로드의 decoded 4 MiB 한도와 별개다. 파일명은 경로·Windows 장치 이름·방향 제어 문자를 정리하고 최대 120 code unit stem을 사용한다.
- Android EPUB export는 원본 32 MiB, 전체 압축 해제 64 MiB, 512 entry, spine XHTML 합계 5,000,000 code unit 한도다. 누락/중복 경로와 초과 입력은 거절한다.
- 웹은 파일 준비 동안 문서/계정 화면 변경·닫기를 감지해 늦은 결과를 버리고 Blob URL을 해제한다. 손상된 ZIP 보관함이 다른 정상 보관함 전체를 막지 않으며 실패한 보관함을 표시한다.
- Android는 진행 중 reader 저장을 기다리고 최신 저장소에서 준비한다. SAF는 요청별 MIME·고정 bytes·메모리 ticket을 사용하고 선택 변경·취소·프로세스 재생성 후 오래된 결과를 거절한다. ZIP ticket 기본 MIME은 유지한다.

## 검증 근거

- 최종 로컬 웹 **613개/73파일**, 생성 계약15·TypeScript·production/sharing build 통과. Android **575개 통과/16 opt-in 제외**, core-content **9개**, APK/lint/계측 소스 컴파일/모듈 경계 검사 통과. 최초 전체 검사에서 새 목록의 benchmark fixture 누락 1건을 발견해 수정한 뒤 전체를 재실행했다.
- 공통 Kotlin JUnit과 웹은 동일 JSON fixture(본문12·파일명16·거절8)를 사용한다. Unicode·CRLF·빈 문단·긴 본문·Markdown 문법·예약 파일명·크기 제한을 검사한다.
- 웹 exporter는 실제 ZIP write/read → 읽기 문서 → PDF 선택 → 원본 bytes 동등성, 잘못된 hash/크기·원본 없음·취소·WebCrypto 없음·URL 정리와 계정/기록 미포함을 검사한다. 실제 IndexedDB 테스트로 네 보관함·계정 격리·손상된 ZIP 실패 분리를 확인한다.
- Android 회귀는 native 원문 whitespace/긴 Unicode, EPUB 전체 spine/확장자 없는 경로/누락·한도, ZIP canonical 본문, 원본 PDF bytes, SAF MIME·취소·선택 A→B→A·열기 중 선택 변경·재생성을 검사한다. 새 AdaptiveCollection 형식 패널의 계측 benchmark fixture를 추가했다.
- 실제 인앱 브라우저에서 서버 없이 시험 TXT 가져오기 → TXT/Markdown 파일 준비, native PDF 및 canonical 문단이 없는 ZIP PDF 원본 파일 준비, 읽기 도구 진입을 확인했다. ko/en, 390×844/844×390에서 행 내부 잘림 없음과 페이지 조작·44px 이상 버튼을 확인했다.
- 인앱 브라우저의 `downloadMedia`는 파일 경로 반환 대기에서 시간 초과였다. 실제 OS 다운로드·재가져오기 왕복을 성공으로 기록하지 않는다. 준비된 Blob bytes의 정확성은 별도 자동검사이며 Android 실기기 SAF·회전·재생성은 V3다.

최종 로컬 검사와 최종 head CI 결과는 [작업 큐](AGENT_WORK_QUEUE.md)의 이번 실행 기록 및 PR에 남긴다. PR은 draft로 유지하며 전체 S4/전체 프로젝트 완료를 뜻하지 않는다.
