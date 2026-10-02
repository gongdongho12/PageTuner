# 서버 문서 분류 동기화

2026-10-02, 작업 큐 S3b1. [공통 계약](../contracts/library-organization-v1.md), [작업 큐](AGENT_WORK_QUEUE.md).

## 구현 범위

같은 서버·계정의 원문 또는 번역 레코드 UUID별로 폴더, 태그, 즐겨찾기 표식을 공유한다. Android는 서버 서재의 각 문서 **분류**, 웹은 해당 문서 **읽기 도구 → 문서 분류**에서 편집한다. 원문과 번역본은 각각 별도 분류이며 제목으로 합치지 않는다.

이 단위는 개별 서버 문서의 분류 저장·조회·수정·명시적 초기화다. 서버 서재 전체 분류 검색/필터와 소설 책 즐겨찾기 연결은 S3b2로 남겨 둔다. 기존 기기 폴더·태그·즐겨찾기와 ZIP 메타데이터는 유지하며, 독립 로컬/ZIP 문서를 서버 문서에 대응시키는 작업은 S4다. 즐겨찾기 표식이 소설 책 즐겨찾기 화면에 이미 통합됐다는 뜻은 아니다.

## 계약과 보존 원칙

- `GET/PUT /api/v1/library-organization/{kind}/{recordId}`, kind는 `ORIGINAL` 또는 `TRANSLATION`이다. 소유하지 않은 문서와 없는 문서는 같은 404를 반환한다.
- 최초 GET은 version 0/null이며 기본값을 생성하지 않는다. 웹은 기기/서버 분류를 명시적으로 선택한 후 공유를 시작한다. Android는 현재 서버 값을 표시하며 **저장**이 최초 명시적 업로드다. 로그인만으로 기존 기기 분류를 옮기지 않는다.
- PUT은 expectedVersion과 mutation UUID를 사용한다. 정확히 같은 최신 요청은 같은 응답으로 재처리하고, 동시 편집은 409와 서버 값을 반환한다. 빈 폴더·빈 태그·즐겨찾기 해제도 version을 증가시키는 저장이므로 오래된 오프라인 요청이 자동 복원하지 못한다.
- 폴더 200 UTF-16 코드 단위, 태그 최대 32개·각 60 코드 단위다. 공통 ECMAScript trim 집합, 제어 문자·잘못된 surrogate 제외, 정확한 대소문자·순서·쉼표를 보존한다. exact 중복 태그는 거절하고 임의 NFKC 병합을 하지 않는다.
- 서버는 8KiB 본문 제한, 엄격한 JSON, 인증·CSRF·no-store와 계정당 분당 120회 PUT 제한을 적용한다. 계정/문서 단위 트랜잭션 잠금, GET snapshot, 소유 문서 삭제 경합과 cascade를 검증한다.
- 웹 IndexedDB와 Android AtomicFile은 계정·문서별 pending/queued·원격 version·충돌·Retry-After를 보존한다. 화면을 닫아도 계정 연결 동안 재시도하며, 재로그인하면 이전 대기열을 복구한다. OS가 프로세스를 종료한 동안 실행되는 백그라운드 작업은 포함하지 않는다.
- 오래된 응답은 최신 값을 되돌리지 않으며 충돌 선택 당시 값이 달라지면 재선택한다. 편집 중 원격 값이 바뀌면 웹은 기존 초안을 유지하고 저장을 거절해 다시 확인하게 하며, Android는 편집 시작 version으로 CAS하여 충돌 선택으로 연결한다.
- 웹은 429의 계정 공통 대기 기한을 클라이언트와 영속 journal에서 반영하고 PUT 전에 다른 문서·탭의 최신 기한을 확인한다. Android도 계정의 가장 늦은 대기 기한을 적용한다. 영구 오류는 명시적 재시도/재연결 전까지 자동 반복하지 않는다.

## 검증 기록

- 서버 최종 `:server:test`: **145개 통과**, 24개 suite, 실패·오류·생략 0. 분류 검사 17개가 소유권/원문·번역 분리, 기본값 비생성, CAS/최신 재시도/동시 생성, clear, Unicode/JSON/본문 크기, 요청 제한, 문서 삭제와 version 한도를 검증했다. 새 경쟁 검사 2개는 원문/번역 × 최초/갱신 × PUT/DELETE 선점의 8개 조합에서 실제 PostgreSQL 잠금 대기와 삭제 후 404·고아 행 없음을 확인했다. 격리 DB `pagetuner_test_20260915_rolling`만 사용했다. 앞선 `:server:bootJar` 성공 이후 서버 구현 변경은 없다.
- 웹 최종 `npm run verify`: **332개 통과**, 45개 파일, 생성 계약 10개·TypeScript·프로덕션 빌드 성공. 분류 검사 34개(API 10/store 5/controller 18/provider 1)가 계정/문서 분리, 오프라인 영속화, 종료 시 저장, 충돌·stale 편집/선택·늦은 ACK, 계정 공통 요청 제한을 포함한다.
- Android: **375개 통과 / 기존 opt-in 15개 생략**, 96개 suite, 실패·오류 0. 분류 관련 신규 17개, debug APK·lint·instrumentation 소스 컴파일 성공. lint 오류 0/경고 75/힌트 4. 최종 로그는 `.gradle-home/android-organization-verify-final.log`, `.gradle-home/android-organization-lint-final.log`이며 이후 앱 구현 변경은 없다.
- 검토에서 문서별 Retry-After를 계정 범위로 확장했고, 긴 CJK 분류 값을 별도 페이지에서 읽도록 했다. 390×844 비교 화면에서 제목·설명을 편집 탭으로 옮겨 양쪽 선택에 필요한 영역을 확보했다.
- 독립 검토에서 두 탭의 늦은 409가 local 필드는 오래된 값으로 남긴 채 remote version만 높이는 문제를 수정했다. 대기 변경과 충돌이 없는 연결된 문서는 필드와 버전을 함께 갱신한다. 새 대기 변경·충돌·편집 중 초안은 보존한다. 오래된 초안 거절(`choice-stale`)이 기존 수락된 변경의 자동 재전송까지 막던 문제도 수정하고 실제 IndexedDB 공유 컨트롤러와 provider 회귀 5개로 검증했다.

## 실제 웹·서버 확인

- 기존 검증용 원문 문서를 사용했다. 최초 조회는 version 0/null을 유지했고, 웹에 폴더·태그·즐겨찾기를 입력해도 명시적인 기기 선택 전에는 서버를 수정하지 않았다. 기기 선택 후 version 1을 확인했다.
- 별도 HTTP 클라이언트가 version 2로 바꾼 동안 편집 중인 웹 초안이 유지됐다. 오래된 초안 저장은 거절됐고 서버 값도 유지됐다. 쉼표가 포함된 `인물,별명` 태그를 하나의 태그로 보존했다.
- 활성 번역 작업이 없음을 확인한 뒤 검증용 서버 프로세스를 중단했다. 웹에서 오프라인 분류를 저장하고 화면을 닫았다. 서버 재시작 후 다른 클라이언트가 version 3을 저장했으며, 재로그인한 웹이 기존 대기 변경을 복구하여 양쪽 충돌을 표시했다. 최종 번들에서도 충돌을 다시 확인하고 기기 값을 선택해 version 4로 저장했다.
- 웹 편집기로 폴더·태그를 비우고 즐겨찾기를 해제하여 version 5의 명시적 초기화를 확인했다. 이후 `함께 읽는 책`/`앱·웹`, `인물,별명`/즐겨찾기 값이 양쪽에 같게 표시되고 별도 GET에서도 version 6으로 확인됐다.
- 최종 번들 `index-C3GB4_L7.js`, 390×844에서 기기/서버 선택 버튼이 모두 보였고 높이는 각각 44px였다. 문서 세로 넘침은 없었다. 200자 CJK 폴더와 60자 태그 2개를 상세 페이지로 나누어 확인했으며, 텍스트의 scrollHeight와 clientHeight가 같아 잘리지 않았다. 기존 문단 10 읽기 위치도 유지됐다.
- 화면 근거(로컬 검증 산출물): `.gradle-home/library-organization-mobile-conflict.png`, `library-organization-mobile-details.png`, `library-organization-mobile-synced.png`. 웹 전체 검사 로그는 `.gradle-home/library-organization-web-verify-final.log`, 서버 최종 로그는 `.gradle-home/library-organization-server-race-test.log`다.

Android 실제 기기 조작은 V3 검증 대기다. 실제 AtomicFile의 백업 복구·프로세스 재시작과 Compose에서 저장 직후 닫기/계정 전환은 실기기 검증에 남긴다. 앱을 오프라인에서 재시작하면 인증 연결이 없으므로 온라인 로그인 후 계정 분류 대기열을 복구한다. 유료 번역·Drive·OAuth 같은 외부 서비스 성공과는 별개의 검증이다. 일반 목록의 최대 8행 제한, 웹 번역 리더의 비어 있는 고정 영역, Android 본문의 고정 글자 수/잘림은 사용자 추가 요청 U1에서 처리하며 이번 분류 화면 확인만으로 해결됐다고 판단하지 않는다.
