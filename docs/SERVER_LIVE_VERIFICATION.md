# 실제 서버·Android HTTP 검증

2026-10-05 검증 시작, 2026-10-11~13 중단된 검증 재개. 사용자가 **격리 DB·검증 서버 시작을 명시적으로 승인**한 뒤 새 PostgreSQL 클러스터와 localhost 서버를 시작했다. 이전 시작 거절은 이 승인 전의 기록이며 현재 검증을 막는 조건으로 재사용하지 않는다. 원본 작업 폴더의 PR34 기반 미커밋 변경과 기존 미리보기 DB는 건드리지 않았다. 이 문서는 실제 외부 사이트/HTTP, 로컬 자동검사, 물리 Android 검증을 구분한다.

## 환경과 재현 범위

- 작업 브랜치 `codex/server-live-verification`, base [PR #50](https://github.com/gongdongho12/PageTuner/pull/50), [draft PR #51](https://github.com/gongdongho12/PageTuner/pull/51). 시작 코드 `71a7604`의 최종 CI run37295593847 성공을 확인했다.
- 새 PostgreSQL 16.15는 `127.0.0.1:55433`에만 바인딩했다. 파괴적인 통합 검사는 `pagetuner_test_live_20261005`, 실제 HTTP/브라우저는 **별도** `pagetuner_qa_live_20261005`를 사용한다. QA DB에 테스트 테이블 초기화를 실행하지 않는다.
- Spring Boot 실제 서버 `http://127.0.0.1:8080`, 현재 `/web` production 자산 포함. 16개 Flyway 마이그레이션 적용과 health UP을 확인했다. 2026-10-13 최종 확인 시 QA 서버 8080은 실행 중이고 개발 웹 5174는 꺼져 있다. 프로세스 재시작 후에는 다시 확인한다.
- 임의 생성한 QA 계정·암호는 ignored `.gradle-home/live-verification-20261005`에만 저장한다. 저장소·PR·로그에 암호/Basic 헤더를 넣지 않는다. 아래 검증 자료의 경로도 이 디렉터리 기준이다.

## 통과한 실제 동작

아래 첫 여섯 행은 2026-10-05의 실제 실행 근거다. 2026-10-12 재실행한 범위는 뒤에 별도로 명시한다.

| 검사 | 실제 실행과 확인 범위 |
| --- | --- |
| PostgreSQL 서버 검사 | 최초 전체 229개, 실패/오류/제외 0. 실제 DB 통합과 MVC/단위 검사 합계이며 전체가 외부 HTTP 검사라는 뜻은 아니다. `server-tests-build.log`. |
| 계정·개인 서재 | 실제 HTTP 97요청/8그룹: 회원가입, Basic 인증, UI/번역 언어, 미래 언어 fallback, 원문 전체 필드/중복/계정 격리, 읽던 위치 CAS/Unicode 경계, 강조/북마크/삭제/충돌, 분류/태그 순서/전체 필터, 용어집 전체 필드/빈 목록/삭제, QA 비밀번호 변경 후 이전 암호 거절·소유 자료 보존. 이어서 2 GET으로 메모와 용어집 보존 재확인. `account-library-report.json`, `post-password-readback-report.json`. |
| NovelBuddy + Google 웹 번역 | 실제 검색→목차→Shadow Slave 첫 회차 import→서버 Google 작업 완료→번역 재조회. 91문단/원문 10,380자/번역 5,198자, 원래 문단 ID·순서·sourceRevision·payloadHash·제목·완료 작업 중복 방지를 확인했다. `novelbuddy-google-report.json`. 무료 Google 웹 제공자의 실행 당시 결과이며 상시 가용성 보장이 아니다. |
| Android 실제 HTTP | production `HttpTranslationStore`를 실제 서버에 연결했다. 신규 계정/언어 갱신, 실제 Google 2문단 작업 생성·완료·읽기·중복 방지, 번역 캐시 서버 저장→파일 복원→재전송 중복 없음의 3개 검사 통과. 캐시 fixture 번역문은 외부 번역 결과와 구분한다. `android-http.log`. |
| Android 실제 소설 번역 읽기 | 위 91문단의 서버 원문/번역을 Android adapter로 조회하고 리더 모델 재구성, 문단 ID·revision·제목·한국어 본문 일치 검사 1개 통과. `android-novel-read.log`. 물리 Android 화면 테스트가 아니다. |
| Android ZIP PDF HTTP | 가져온 ZIP의 canonical 문서/실제 bytes를 준비→실제 PDF 업로드→같은 uploadId 재시도→GET bytes/full proof→verify의 opt-in 1개 검사 통과. 실제 PDF 화면/SAF/계정 연결 UI 검증과 구분한다. `android-http.log`. |
| 2026-10-12 CSRF 실제 HTTP | CSRF 없는 회원가입 POST, 프로필 PATCH, 비밀번호 변경 POST가 모두 **403**을 반환했다. 아래 오분류 수정이 실행 JAR에 반영됐음을 확인했다. `repaired-server-report.json`. |
| 2026-10-12 실제 웹 읽기·보관 | QA 계정 로그인 → 기존 Shadow Slave 번역 작업 **91/91문단 완료** → 한국어 리더 **29쪽** → **이 기기에 보관** 성공 표시를 확인했다. 이후 새 탭에서 재로그인 → 기기 보관함 → 같은 한국어 번역문 재열기를 확인했다. 네트워크 단절 후 읽기는 별도 미검증이다. |

## 2026-10-12 로컬 회귀와 커밋

- WTR 최종 경로 보완 후 전체 회귀: 웹 **665개/76파일**, `source-runtime` **52개**, 서버 **237개** 실패/오류/제외 0. 서버 전체는 격리 테스트 DB에서 실행한 통합·MVC·단위 검사의 합계이며 전부 실제 외부 HTTP라는 의미는 아니다.
- Android **602개 중 586개 통과·16개 opt-in 제외**, APK·lint·계측 소스 컴파일·모듈 경계 성공. 물리 장치 실행과 별개다. 로그는 `repaired-web-tests.log`, `final-full-gradle.log`에 보존한다.
- CSRF 수정 `9c34628`, 웹 오프라인 셸 업데이트 대기 안내 `703629e`, WTR 응답 처리 `6f79dec`를 분리해 커밋·푸시했다. `703629e`의 [CI run38226910728](https://github.com/gongdongho12/PageTuner/actions/runs/38226910728)은 성공했다. 후속 WTR/문서 커밋을 포함한 **정확한 최종 head CI는 PR #51에서 다시 확인**하며 이 중간 CI를 최종 결과로 간주하지 않는다.

## 실서버에서 발견한 결함

1. **CSRF 거절의 401 오분류**: 기본 `sendError(403)`이 보호된 `/error`로 재디스패치되어 401이 됐다. 앱·웹 비밀번호 변경 화면에서는 인증 실패로 잘못 판단해 연결 해제/새 비밀번호 확인 안내를 할 수 있었다. 직접 403 + `Cache-Control: no-store`를 반환하도록 수정했다. 실제 embedded Tomcat HTTP 4개 + 기존 MVC 7개 통과. 누락/잘못된/다른 세션 CSRF는 거절하고 정상 CSRF의 쓰기·잘못된 Basic의 401·쿠키만으로 인증 불가를 유지한다. 현재 CI의 MVC 선택 검사와 새 실제 HTTP 검사의 실행 범위를 구분한다.
2. **WTR-LAB 회차 본문 응답 변경**: 2026-10-05 실제 카탈로그/검색/다음 원격 목록/198회차 목차는 성공했으나 회차 import가 502였다. 사이트가 정상 성공 응답에서 직접 본문 대신 `content_url`을 돌려주는 형식으로 바뀌었다. 공통 parser와 앱/서버 transport가 검증된 동일 HTTPS 원본의 정확한 경로만 읽도록 수정했다. redirect 거절·5 MiB 한도·서명 URL 진단정보 제거를 유지하고 회귀 검사도 추가했다. 2026-10-12 정상 공개 Node HTTP의 POST 및 content GET은 200, **129문단·7,120자**였지만 **실행 중인 Java 서버의 카탈로그와 회차 import는 502**였다. 후속 실제 Java 검사에서 root-relative 경로와 콘텐츠 hash/revision/expiry/signature의 전체 경로를 반영했다. 최종 production transport 직접 회차 검사(`wtr-reader-final.log`, 2026-10-13)는 129문단/용어 치환 후 7,307자로 통과했다. 앞선 전체 카탈로그 검사에는 외부 SocketTimeoutException이 남았으므로 모든 소스 경로의 상시 성공을 주장하지 않는다. CAPTCHA/로그인 요구를 우회하지 않았다.
3. **구버전 오프라인 웹 유지**: 실제 원문 리더에서 번역 메뉴를 열 때 HTTP 200 응답 이후 구버전 클라이언트 검증 오류가 발생했다. 브라우저가 실행한 `index-sLJ7Wzvy.js`와 실제 서버의 최신 manifest가 달랐다. 최신 production parser를 같은 실제 응답으로 실행하면 5개 제공자 모두 통과했다. 일반 새로고침도 활성 worker가 유지하는 구버전 화면을 다시 열었다. 새 버전 대기 안내와 모든 같은 주소의 탭을 닫고 다시 여는 절차를 추가했다. 현재 탭 강제 교체나 IndexedDB 삭제는 하지 않으며 이 안내는 이미 캐시된 옛 코드에 소급 표시되지는 않는다. 2026-10-12 새 페이지의 로그인·완료 번역 읽기·기기 보관을 확인했다.

## 최종 실제 WTR API 결과

2026-10-13 최신 JAR/격리 QA DB에서 같은 회차 import가 **HTTP 200, 129문단**으로 성공했다. 원문 record `be30c2c0-96f2-4a8a-b480-3e7e05deda60`와 sourceRevision을 응답에서 확인했다(`repaired-server-report.json`). CSRF 없는 계정 쓰기 3경로도 모두403이었다. 이는 앞선 중간502 결과를 해소한 최종 회차 import 증거다. 모든 작품·카탈로그의 상시 가용성이나 이 WTR 회차의 추가 Google 번역을 검증한 것은 아니다. 경로 보완 커밋은 `f7ca005`이며 최종 문서 head CI는 PR #51의 정확한 SHA로 확인한다.

## 검증하지 않은 범위

- `adb devices -l`에 장치가 없고 설치된 AVD도 없었다. 실제 폰 앱 실행·PDF 글꼴/여백·SAF·회전·프로세스 재생성·핫스팟·화면 꺼짐·절전은 **V3 미검증**이다. 기존 APK/lint/계측 소스 컴파일 성공을 실기기 동작으로 표시하지 않는다.
- 유료 제공자 실호출, OAuth/메일, 용어집 파일/조사/강조 화면, 생성 PDF OS 저장은 기존 V1/V2/V4 경계를 유지한다.
- 이번 PDF 실제 HTTP 검사는 ZIP PDF 업로드/연결 **브라우저 UI 종단 검사**의 완료를 뜻하지 않는다.
- 전체 프로젝트/S4와 최신 S1/S2/분류 ZIP 통합은 완료되지 않았다. 다음 개발 단위는 [체크리스트](REMAINING_WORK_CHECKLIST.md)를 따른다.
