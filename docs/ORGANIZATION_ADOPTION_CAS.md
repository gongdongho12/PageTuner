# 분류 snapshot 명시적 채택의 웹 CAS 기반

후속 [PR #58의 웹 canonical 비교·별도 명시적 채택 화면](ORGANIZATION_ADOPTION_UI.md)이 이 기반에 연결됐다. 아래는 PR #57 당시 기반 구현의 범위이며 최신 잔여 작업은 작업 큐를 따른다.

2026-10-24, draft PR #57. Base는 `codex/android-organization-export`(PR #56), head는 `codex/organization-adoption-cas`다. 이 단위는 **웹 journal과 새 CAS 의도 생성 기반**이며 ZIP 비교 화면·계정 연결·Android 채택을 활성화하지 않는다. 전체 S4c3b와 S4는 미완료다.

## 저장과 채택 경계

- `createLibraryOrganizationStore.transactNullable(reducer, guard)`는 없는 journal을 `null`로 읽고, 비교·변환·쓰기를 하나의 IndexedDB readwrite transaction에서 수행한다. 없는 행을 미리 초기화하지 않는다. 입력·출력은 검증한 사본이며 계정/문서 종류/정확한 UUID 키를 유지한다.
- guard는 DB 열기 전후, reducer 실행과 put 직전, 마지막으로 취소 가능한 put 성공 콜백에서 실행된다. `close()`는 진행 중·대기 중 쓰기를 abort한다. transaction 완료 뒤의 취소나 알림 오류를 rollback으로 표시하지 않는다.
- `controller.adoptSnapshot(value, { record, remote }, guard)`는 비교 당시의 nullable 기기 journal 전체와 직접 GET 응답을 요구한다. 입력을 await 전에 복사하고 scope·version·내용·updatedAt을 검증한 뒤, 초기화/start/refresh/choose/update를 거치지 않고 직접 `api.get`을 다시 수행한다.
- 기존 pending/queued/conflict, 비교 후 달라진 local/remote/기한, 응답 version 역행과 같은 version의 다른 내용·timestamp는 거절한다. 행의 부재도 원자 비교하므로 두 탭이 동시에 채택하면 한 의도만 저장된다. 조회에서 관찰한 최고 version은 actor가 유지하며 최종 쓰기 경계에서도 재검사한다.
- 모순된 동일 version 또는 잘못된 scope 등 invalid-response를 관찰하면 해당 actor의 모든 채택을 영구 거절한다. 대기 중 쓰기도 마지막 취소 가능한 guard에서 거절하며 화면 refresh로 재활성화하지 않는다. 새 controller에서 직접 재비교해야 한다.
- 성공할 때는 최신 expectedVersion과 **새 mutationId**를 만든다. 같은 값도 조기 반환하지 않고, `folder=""`, `tags=[]`, `favorite=false`는 version 0에서도 새 빈 분류 PUT 의도가 된다. 기존 retryAfterUntil은 보존한다. 이후 정상 outbox가 전송하며 이 함수의 `true`는 서버 ACK가 아닌 **기기 의도의 durable commit**을 뜻한다.
- 시작하지 않은 controller는 다른 탭 journal 알림만으로 전송을 시작하지 않는다. 비교 거절로 기존 pending을 자동 전송하거나 지우지 않는다. 이미 시작한 별도 편집기의 동기화를 전역 중단한다는 뜻은 아니다. 저장 뒤 observer 예외는 성공을 실패로 바꾸지 않는다.

## 다음 소비자가 반드시 수행할 일

이 함수는 권한을 발급하는 API가 아니다. 필수 동기 guard를 실제 계정/origin/client/선택 세대 및 raw binding nonce와 결합해야 한다. ZIP snapshot의 전체 text DocumentIdentity·본문 proof·canonical 최신 문서/삭제 여부·최신 서버 GET·nullable 기기 base를 비교하는 별도 adapter와 terminal 화면 세션이 다음 작업이다. 현재 함수에 임의 값과 빈 guard를 전달하는 것만으로 안전한 ZIP 채택이 완성되지는 않는다.

`absent` snapshot은 정보만 표시하고 이 함수를 호출하지 않는다. `present-empty`는 별도 사용자 선택을 받아 호출한다. import/export 권한은 계정 쓰기 권한이 아니며, PDF 별도 binding/UUID로 text S1~S3를 열지 않는다. 기존 [snapshot 계약](../contracts/library-organization-snapshot-v1.md)의 Unicode/NFC/NFD/공백/ordered tags·legacy/sibling·extensions 256 KiB/문서 8 MiB 거절과 binding/자격 증명/CAS/outbox ZIP 제외 경계를 유지한다.

IndexedDB transaction은 해당 journal 한 행의 비교/쓰기만 원자화한다. 라이브러리·binding·계정·서버·저장을 하나의 transaction으로 묶거나 동일 bytes 삭제·재삽입의 영속 세대를 제공하지 않는다. 서버 version 확인과 실제 PUT 사이의 변경은 기존 CAS 충돌로 처리된다.

## 검증 근거

- 저장소·controller 대상 **3파일 64개**, 전체 웹 **81파일 781개**, 생성 계약15개·typecheck/production build·공유 웹/APK/모듈 경계 성공. fake-indexeddb·주입 API·지연 facade 검사는 실제 브라우저/계정 API 종단 검사와 구분한다. 오류 표시용 reload를 잡아 둔 동안에도 다른 대기 쓰기가 거절됨을 확인했다.
- 실제 PostgreSQL `127.0.0.1:55433/pagetuner_test_live_20261005`에서 `LibraryOrganizationIntegrationTest` **18개, 실패/오류/제외 0**. 새 회귀는 absent/version 0 → 명시적 빈 분류/version 1 → 동일 mutation 재시도 → 새 mutation/같은 빈 값/version 2와 DB 한 행을 확인했다. 기존 서버 production 동작·계약·스키마는 변경하지 않았다.
- 처음의 DB 사용자 설정 오류를 수정한 뒤 최종 실행이 통과했다. 테스트 DB만 사용했고 `pagetuner_qa_live_20261005`·미리보기 DB·원본 PR34 변경을 수정하지 않았다. 실제 DB를 쓴 Spring MVC 통합 검사이며 새 외부 HTTP/브라우저/Android 실기기 검증은 아니다.
- 현재 원격 CI는 Android/core·서버 security·웹/shared proof 범위다. 위 PostgreSQL 검사와 로컬 전체 검사 근거를 원격 CI에 포함됐다고 표시하지 않는다. 최종 문서 SHA의 CI는 PR 본문과 ignored `organization-adoption-cas-final-ci.json`에서 확인한다.
