# Android export ticket의 준비·게시 잠금

후속 [PR #73 Android S1 옵션/VM/SAF 코드 연결](ANDROID_PROGRESS_EXPORT_UI.md)이 실제 committed grant와 write Job을 연결했다. 아래는 PR #72 staging 근거이며 실제 Compose/물리 SAF 실행은 V3로 남는다.

2026-11-05 [draft PR #72](https://github.com/gongdongho12/PageTuner/pull/72), base `codex/canonical-android-progress-export`(PR #71), head `codex/portable-export-staging`다. 구현/17개 회귀 `89ac1e2`와 문서를 분리한 **메모리 SAF ticket 준비 경계**다. VM·Compose 읽던 위치 옵션 연결과 물리 SAF 실행은 후속이며 기존 canonical progress adapter/composer를 다시 만들지 않는다.

## 준비 예약과 고정 payload

`PortableExportTickets`는 NEW→PREPARING→READY→WRITING으로 진행한다. prepare는 짧은 ticket monitor에서 정확한 Pending 객체/요청/connection을 확인하고 한 번만 PREPARING을 예약한다. bytes 복사와 외부 source guard는 monitor 밖에서 실행한다. 입력 bytes는 외부 guard 호출 전에 복사하므로 guard가 caller의 배열을 바꿔도 게시할 값은 바뀌지 않는다.

guard 뒤 같은 Pending 객체와 PREPARING 상태·captured connection을 monitor 안에서 재검사한 경우에만 고정 bytes/guard 쌍을 한 번 게시한다. 대기 중 cancel/begin/connect/클라이언트 교체가 진행되면 이전 준비는 새 요청을 덮지 못한다. 실패는 자신의 Pending만 종료한다. 중복 prepare의 패배자는 예약을 얻지 못하므로 승자의 PREPARING/READY 상태를 정리하지 않는다. 실패한 요청에 빈 guard를 새로 넣거나 READY/WRITING의 bytes/guard를 바꾸는 재준비는 거절한다.

## 최종 관찰과 provider open

writer는 READY를 한 번 WRITING으로 바꾸며 정확한 payload 객체를 캡처한다. current/guard/open/OutputStream write·close callback은 모두 ticket monitor 밖이다. 저장 직전 check는 current/guard 호출 결과 뒤와 provider open 전후에 정확한 Pending/payload/state와 live connection을 재확인한다. source guard가 gate→account→library→binding→journal IO를 수행하는 동안 ticket monitor를 붙잡지 않는다. open 뒤 재검사 실패는 열린 stream을 닫고 bytes를 쓰지 않는다. 이전 writer의 finally도 교체된 새 요청을 종료하지 않는다.

이는 **저장 직전 preflight 검사**이며 provider의 실제 write와 계정/선택 종료를 원자화하지 않는다. 마지막 검사 뒤 이미 진행 중인 OutputStream.write의 취소·부분 파일 rollback/원자 파일 교체를 보장하지 않는다. prepare의 connection 인자는 캡처한 값이므로 앱 소유자의 connect 알림과 terminal source guard 없이 미관찰 A→B→A를 알아내지 못한다. actual text authority/선택·현재 write coroutine은 후속 VM/Compose 소비자가 연결해야 한다. 완료된 prepare job을 후속 SAF write 권한으로 재사용하면 안 된다.

## 검증

- 신규 JVM **17개**, 집중7클래스 **75개**가 2분10초에 통과했다. 최종 전체 앱149클래스 **868개 중852개 통과/16 opt-in 제외**, 실패/오류0. APK/lint/기존 계측 소스 컴파일/모듈 경계가 1분32초에 성공했다(83 task 중69개 재사용). 독립 검토 완료, 초기 테스트 실패 없음. 기존 nullable Java interop 경고는 유지한다.
- JVM latch/executor와 주입 OutputStream으로 guard 대기 중 cancel/begin/connect·관찰된 같은 account/client ABA·새 client, reentrant 교체·늦은 guard 실패의 새 요청 보존·중복 prepare 패배자·READY/WRITING 재준비 거절·guard 전 bytes 복사·PREPARING의 readiness/open 거절·실패한 준비의 빈 guard 재시도 거절·post-open 취소/0bytes/stream close·이전 writer 실패/finally의 새 요청 보존·단일 writer·모든 외부 callback의 Thread.holdsLock(false)를 확인했다.
- 기존 glossary/organization/progress canonical 준비·500항목/동시 ZIP·ticket5개 회귀도 집중 검사에 포함했다. 새 물리 Android SAF/provider·회전/재생성·실제 계정/화면·실소켓/DB 검증이 아니다. 계측 소스는 컴파일만 했으며 새 계측 실행은 없다. 서버/DB·웹 production·원본 PR34/기존 자료/자격 증명은 변경하지 않았다. ignored `portable-export-staging-focused.log/focused-counts.json/full.log/counts.json`에 근거를 보존했다.

문서 포함 정확한 최종 head CI는 PR 본문/ignored `portable-export-staging-final-ci.json`에서 대조한다. 원격 Android/core·서버 security·웹/shared proof와 로컬 위 검사를 구분한다. 선행 PR #71 `864ce25c1099d572fb132934a726e57aaa8a5bb1`/run41843239855 SUCCESS4분05초를 재확인했다. 체인 base를 보존하고 자동 병합하지 않는다.

## 다음 연결

PortableIdentityPanel에 기본 해제 includeProgress를 추가하고 실제 렌더 당시 authority/entry/verification session/identity·g/o/progress/언어를 캡처한 committed terminal issuer를 연결한다. 이전 callback이 새 getter/ref/authority를 빌리지 못하게 하며 계정/선택/옵션/언어/닫힘/unmount에서 정확한 옛 issuer를 먼저 종료한다. 이미 선택한 옵션은 offline/busy에도 해제 가능해야 한다.

VM은 실제 ticket과 PR #71 adapter·공통 composer를 연결하고 IO에서 currentDocument/encode 뒤 canonical/raw nonce/journal을 검사한다. reader.current에는 memory-only ticket 검사만 넣고 exportTickets.check를 gate/journal 안에서 재획득하지 않는다. SAF는 destination open 전후와 쓰기 전에 **현재 write job**을 검사한다. request ID 재생성만으로 메모리 payload/권한을 복원하지 않으며 부재 시 open/truncate 전에 거절한다. 실제 UI 변경은 EInk skill/AdaptiveCollection/paged/고정 row/44dp와 benchmark 실제 fixture 행 수를 따른다. S1 명시적 채택은 nullable CAS와 정상 actor cached persist 보호가 선행이다.
