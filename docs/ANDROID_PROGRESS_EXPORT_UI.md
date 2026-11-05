# Android 최신 읽던 위치 ZIP 옵션 연결

2026-11-06 [draft PR #73](https://github.com/gongdongho12/PageTuner/pull/73), base `codex/portable-export-staging`(PR #72), head `codex/android-progress-export-screen`다. committed 수명/쓰기 Job·19개 회귀 `c6aa406`, 화면/VM/admission·5개 회귀 `5f14bb8`와 문서를 분리했다. **화면→VM→기존 canonical adapter/composer→SAF 저장 경로의 코드 연결**을 완료했다. 실제 Compose 화면 실행·물리 SAF/회전/재생성은 V3로 남으며 S1 명시적 계정 채택도 아직 없다.

## 옵션과 committed 권한

기기 ZIP의 서버 원본 확인 패널에 기본 해제된 **최신 계정 읽던 위치 포함** 옵션을 추가했다. 용어집/분류와 함께 또는 읽던 위치만 선택할 수 있다. 끈 경우 S1 GET은 수행하지 않고 기존 passive snapshot을 보존한다. 별도로 선택한 용어집/분류 요청은 기존 의미를 유지하며 ordinary ZIP export는 계정 요청을 추가하지 않는다. import/export가 계정 위치를 자동 적용하거나 reset하는 권한은 아니다.

`PortableAccountExportSession`은 렌더 당시 실제 authority·전체 entry/검증 session/identity/record ID·옵션/언어에 고정되고 committed DisposableEffect에서만 실제 operation lease/ticket을 활성화한다. NEW/ACTIVE/CLOSED는 terminal이며 dispose 이전 activate·old begin/이전 grant·A→B→A·동일 key/client의 새 authority가 새 소유자를 빌리지 못한다. busy/status/presence는 key에 넣지 않아 자신의 export 시작으로 만료되지 않는다. 체크된 옵션은 offline/busy에도 끌 수 있고 옵션/언어/record 변경·Back/닫기·unmount는 정확한 옛 session을 먼저 종료한다. close는 Atomic CLOSED를 먼저 게시하고 gate 대기를 수행하며 retained grant 검사는 메모리만 읽는다.

MainActivity는 export 때 최신 readingConnection/authority를 새로 발급하지 않고 렌더의 실제 authority와 grant를 VM에 넘긴다. identity 패널의 다른 이전 callback도 실제 authority/session/entry/identity를 대조해 같은 값의 새 owner나 나중 선택을 닫거나 편집하지 못하게 했다. authority 객체가 바뀌면 VM presence를 초기화하고, effect 반영 전 첫 render에서도 이전 owner의 presence/status/error를 가린다.

## 최신 GET와 최종 guard

VM의 내부 metadata API는 실제 grant/ticket으로 PR #71 adapter와 공통 composer를 호출한다. reader 저장 완료 currentDocument·선택된 전체 문서/raw extensions/fullproof·strict readonly binding capture/raw nonce를 사용한다. 용어집/분류도 regular read가 아닌 strict capture를 사용해 combined export가 v1 binding을 먼저 migration한 뒤 S1을 여는 경로를 피했다. 기존 명시적 연결의 migration은 유지한다.

prepare await/인코딩 후에는 현재 작업 Job과 선택/요청을 검사한다. retained source guard는 완료될 prepare Job을 캡처하지 않는다. progress의 checkOperation에는 memory-only grant 검사를 넣어 session close가 gate를 기다리는 동안 게시한 CLOSED도 마지막 journal 검사에서 본다. 최종 gate→account→library→binding→journal 안에서 exportTickets/이전 선택 monitor를 재획득하지 않는다. 네트워크는 monitor 밖이며 controller/open/connect/retry/move/choose/PUT을 export 목적으로 열지 않는다.

picker 이벤트는 IO에서 retained canonical/source guard를 검사하고 UI 복귀 직후 exact request와 captured memory grant를 다시 검사한 다음 await 없이 chooser를 발급한다. memory ready holder의 제거는 정확한 요청에만 CAS로 적용한다. 오래된 event/callback은 새 요청이나 owner 상태를 지우지 않는다.

## 실제 write coroutine과 UI admission

`writePortableExport`는 **실제 write coroutine**의 context를 캡처해 source guard/provider open 전후·bytes 쓰기 직전에 취소를 확인한다. prepare coroutine이 끝났다는 이유로 SAF 쓰기를 거절하지 않는다. 이미 완료된 stream write 뒤에 취소 검사를 추가해 저장 성공을 false rollback으로 바꾸지 않는다. 권한/계정/client/ticket/payload는 메모리에만 있고 request ID만 재생성돼서는 destination open/truncate를 할 수 없다.

picker 결과가 producer의 busy 종료보다 먼저 도착하면 고정 URI/request ID/owner/ready guard를 viewModelScope에서 기다려 처리한다. 최신 요청으로 바꾸거나 callback을 버리지 않는다. 작업 시작 전 `checkReadyId`와 captured grant를 admission에서 검사하므로 이전 요청이 새 화면의 error/status/presence를 먼저 초기화하지 못한다. production admission은 StateFlow CAS 안에서 busy/권한을 재평가한다. 늦은 오류/성공 notice도 실제 captured owner에 맞을 때만 게시하며 저장 뒤 만료를 예외 rollback으로 만들지 않는다.

PR #72 sealed payload·one-shot writer와 open 전후 guard를 유지한다. 마지막 preflight 뒤 이미 진행 중인 provider write의 취소·부분 파일 rollback, 계정/library/binding/journal/서버/SAF 전체 원자성, 동일 bytes/private 시각 삭제 재삽입 영속 세대, GET 뒤 미관찰 서버 변경의 동시점 보장은 제공하지 않는다.

## EInk와 검증

- EInk skill/가이드에 따라 AdaptiveCollection·paged 기본·116dp×fontScale의 실제/estimated row 일치·44dp target을 유지했다. ko/en checkbox/absent/present 문구를 추가했다. benchmark는 실제 최대 translation 패널 **28행**(기본·옵션/언어17 + identity8 + status3), collection callsite33을 기록하며 새 source는 컴파일만 했다.
- 신규 **24개**(session9/write Job6/실제 JVM ZIP flow4/admission5), 집중8클래스 **71개** 성공. 최종 전체 앱 **892개 중876개 통과/16 opt-in 제외**, 실패/오류0. APK/lint/기존·갱신 계측 소스 컴파일/모듈 경계가 1분31초에 성공했다(83 task 중68개 재사용). 독립 검토 완료.
- production session/authority/ticket·adapter/composer·메모리 SAF tickets와 실제 JVM ZIP/binding 파일을 주입 API/journal/provider stream에 결합했다. uncommitted/이전 callback/ABA/다른 client·동일 key/client owner, held GET/최종 gate 중 session close, 500용어집 모든 필드/ja scope·분류/ordered Unicode·정확 anchor·legacy notes/position/sibling, 실제 write Job 취소/guard·provider 대기·stream close/0bytes·저장 뒤 취소 성공 보존·메모리 유실 재생성·오래된 callback의 새 UI 상태 보존을 확인했다. Android Context의 실제 VM이나 Compose runtime을 실행한 근거는 아니다.
- 최초 신규 테스트 컴파일은 JUnit 메서드의 inferred internal 반환 타입/관찰 fake의 필수 target read/write 누락을 수정했다. 다음 1개 실패는 서버 계약에 없는 폴더 앞뒤 공백 mock을 올바른 `Folder 中` 값으로 고쳤다. production 문자열 정규화를 도입하지 않았다. initial compile/fixture log·XML과 최종 focused/full/counts는 ignored `android-progress-screen-*`에 보존했다. 기존 nullable Java interop 경고는 유지한다.
- 이번 adb 조회에는 장치가 없었고 설정 SDK의 emulator binary도 없었다. 조회가 시작한 adb daemon은 종료했다. 실제 ko/en 세로/가로 시각·Compose callback/터치·물리 SAF·회전/재생성 실행은 V3이며 JVM/benchmark 컴파일을 대신 근거로 쓰지 않는다. 서버/DB·웹 production·원본 PR34/기존 자료/자격 증명은 변경하지 않았다.

문서 포함 정확한 최종 head CI는 PR 본문/ignored `android-progress-screen-final-ci.json`에서 대조한다. 원격 Android/core·서버 security·웹/shared proof와 위 로컬 범위를 구분한다. 선행 PR #72 `bc25260414280ab8818bd0d13765875e9bc9cc12`/run42010621764 SUCCESS4분13초를 재확인했다. 체인 base를 보존하고 자동 병합하지 않는다.

## 다음 단위

실기기가 확보되면 위 V3/실제 화면·파일 provider 검증을 수행한다. 독립 개발은 **S1 별도 채택의 nullable journal CAS**부터 작게 진행한다. ServerReadingProgressStore의 부재와 기본값을 구분하고 기존 strict alias 관찰·backup/format1·pending/queuedAnchor/conflict/retry 기한을 보존해야 한다. 마지막 취소 가능한 저장 guard와 기존 ServerReadingProgressSync의 cached unconditional persist 보호가 준비되기 전 move/choose/open/connect/retry로 채택하지 않는다. S2는 ID/정확 UTF-16 range/deleted tombstone/feed 완결성의 별도 snapshot 계약이며 legacy notes로 대체하지 않는다. PR #67 OS 파일 경로 질문은 반복하지 않는다.
