# Android S1 정상 sync snapshot CAS·reload 보호

2026-11-06, [draft PR #75](https://github.com/gongdongho12/PageTuner/pull/75). Base는 `codex/android-progress-snapshot-cas`(PR #74), head는 `codex/android-progress-sync-cas`다. PR #74 STORE ONLY의 `captureSnapshot`/`compareAndWrite`를 정상 `ServerReadingProgressSync`에 연결한다. 별도 S1 채택 command/adapter/UI는 아직 없다. export의 PR #70~73 direct GET·canonical composer·옵션/SAF는 다시 만들지 않는다.

## durable base와 transition 경계

Session은 nullable exact canonical을 포함하는 전체 `ReadingProgressJournalSnapshot`을 durable base로 보관하고 UI 기본 `DeviceReadingProgress()`와 구분한다. open/recovery에서 strict snapshot을 읽고 실제 `ServerReadingDocument`의 모든 remote/pending/queued/conflict anchor를 검증한다. 정상 sync는 복구 가능한 legacy read나 unconditional write로 fallback하지 않는다. atomic API가 미지원이거나 관찰이 손상/불완전하면 DeviceError로 닫는다.

현재 정상 actor는 **비canonical UUID 대소문자 별칭이 하나라도 있으면 DeviceError로 닫는 보수적 가용성 제한**을 택한다. raw 별칭의 pending/high remote/conflict를 무시하거나 lowercase/merge/migration/삭제하지 않는다. outbox discovery가 과거 uppercase target을 반환하면 조회 scope만 canonical로 잡고 strict capture에서 별칭을 검출·거절한다. PR #74 저장 API의 전체 별칭 CAS 능력은 그대로다.

모든 persist는 계산 당시 durable base로 CAS한다. 실패하면 transition과 뒤의 성공·restore·retry·conflict·terminal 부작용을 버리고 최신 snapshot을 설치한 뒤 active/outbox network·timer 세대를 무효화한다. `JournalChanged`는 일반 저장 오류와 구분해 현재 상태를 별도 action에서 다시 평가한다. 오래된 계산을 새 base에 재시도하지 않는다. CAS 성공 base는 방금 쓴 값과 exact expected token에서 유도하며 후속 writer의 row를 재조회해 자신의 값인 것처럼 빌리지 않는다.

Page/Resolve/Received의 동일 값/쓰기 없는 early return·401/timeout 부작용 전에도 최신 base를 확인한다. send/fetch와 timer dispatch 전에도 확인한다. 외부 변경이 없는 기존 stale form의 expectedVersion·자체 queued edit·uncertain ACK의 동일 mutation 재시도는 유지한다. 분쟁의 local/server 선택은 기존 retry 기한을 보존해 수동 선택으로 cooldown을 단축하지 않는다.

## outbox와 종료 범위

`pending()`은 대상 발견에만 사용한다. cached known inactive scope까지 strict capture하고 terminal/attempted/cooldown/dispatch 결정을 한다. outbox 응답은 **dispatch 당시 전체 snapshot**을 고정하므로 pending mutation이 같아도 queuedAnchor/deadline 등이 바뀌면 오래된 ACK/오류를 적용하지 않는다. 활성 문서를 닫거나 다른 문서로 옮길 때 기존 durable base를 inactive 관찰에 넘겨, 외부 새 의도가 과거 terminal suppression에 가려지지 않게 한다.

변경된 scope의 억제 상태를 무효화하되 다른 활성 문서의 401 terminal 상태는 풀지 않는다. account key와 client 객체로 connection 교체를 구분하고 network/timer callback은 발급 당시 ticket을 고정한다. mutable Session의 새 ticket을 늦은 coroutine이 빌리지 않는다. 정상 actor의 serialized action 수명이며 실제 Compose 채택 권한 ticket은 후속 command/adapter에서 별도로 연결해야 한다.

snapshot 조회와 HTTP dispatch는 원자적이지 않다. 두 actor가 같은 mutation을 이미 선택해 전송할 수 있으며 서버 idempotency/CAS가 이 경계를 담당한다. known scope별 capture는 PR #69 전체 directory 검증을 반복하므로 대량 outbox의 scan 비용이 증가할 수 있다. 관련 없는 손상 journal도 관찰을 막는다. 전체 계정/library/binding/journal/서버 transaction, 동일 bytes 재삽입 영속 세대, 서버 최신값의 전역 동시점을 제공하지 않는다. 일반 서버 응답 version 단조성 정책의 확장은 이번 단위에 포함하지 않는다.

## 검증과 후속

- 신규 JVM **25개**와 기존26개, 최종 집중 **51개** 성공(34초). 초기22개+기존26개도2분17초에 통과했고 검토 후 outbox CAS 직전 패배/별칭 삽입·같은 client의 account key 교체·cooldown 뒤 실제 재전송을 보강했다. 초기 테스트 실패는 없었다.
- 전체 앱 **951개 중935개 통과/16 opt-in 제외**, 실패/오류0. APK/lint/기존 계측 소스 컴파일/모듈 경계 성공: **1분40초**, 83 task 중69 재사용. 기존 nullable Java interop 경고는 남으며 검사에 실패하지 않았다.
- 지연 GET 성공/429/conflict/401/timeout·old ACK/new pending, dispatch 뒤 같은 mutation의 queued/deadline 변경, 계산 뒤 CAS 패배, stale 동일 page/양쪽 conflict 선택, 두 actor 경쟁, inactive suppression/cooldown, 다른 활성 문서의 terminal 보존, 닫기 전 외부 변경, 별칭/미지원 failclosed와 uncertain ACK의 동일 mutation/logout 재시작을 검증했다. 주입 atomic fake는 전체 snapshot을 동기 비교하고 legacy read/write 호출은 실패시킨다. held Job은 테스트 동기화에만 사용하고 상태/journal/요청과 mutation을 검증한다.
- 독립 검토로 다른 inactive scope의 변경이 활성 문서의 401 제한을 풀지 않도록 수정했고, departing active durable base를 보존해 새 의도가 오래된 억제 상태에 가려지지 않게 했다. 변경 없는 기존 conflict 선택에서도 retry 기한을 유지한다.
- JVM 주입 journal·production HTTP decoder/모의 transport 근거다. 새 실제 AtomicFile/소켓/DB/Android Context VM/Compose/SAF/기기 실행이 아니다. 기존 계측은 컴파일만 했고 서버/웹 production·원본 PR34/자료/자격 증명은 변경하지 않았다. 로그·focused/full/counts는 ignored `.gradle-home/android-progress-sync-cas-*`에 있다. 문서 포함 정확한 최종 head CI는 PR 본문/ignored `android-progress-sync-cas-final-ci.json`에서 대조한다. 선행 PR #74 `a658ac90f3aa5fe565a8fdcf2798a4f721521d9d`/run42204937311 SUCCESS2분45초를 재확인했다. 자동 병합하지 않는다.

다음은 직접 최신 GET 뒤 canonical 전체 문서/fullproof·strict raw binding nonce·실제 account/client/선택 terminal ticket을 재검사해 새 mutationId/expectedVersion 의도를 만드는 별도 S1 채택 command다. nullable 전체 snapshot CAS와 정상 actor 보호를 재사용하고 move/choose/open/connect/retry/fallback으로 채택하지 않는다. 정상 actor가 아직 처리하지 못하는 비canonical 별칭 scope도 함께 failclosed해야 하며 별칭의 기존 pending과 경쟁하는 새 canonical 의도를 몰래 만들지 않는다. absent snapshot은 정보만/PUT 없음, present는 정확한 anchor를 별도 사용자 선택으로만 적용한다. legacy position과 계정 anchor를 혼동하지 않는다. durable commit 뒤만 captured account/client/session의 명시 인계를 검토하고, 인계 거절·취소를 저장 실패로 뒤집지 않는다.
