# Android S1 nullable logical snapshot·guarded CAS 저장 기반

2026-11-06, draft PR #74. Base는 `codex/android-progress-export-screen`(#73), head는 `codex/android-progress-snapshot-cas`다. **STORE ONLY** 단위이며 정상 sync actor·별도 채택 command/UI는 아직 이 API를 사용하지 않는다. PR #69 strict 관찰과 PR #70~73 export를 재구현하지 않는다.

## 비교 토큰과 파일 보존

`ServerReadingProgressStore.captureSnapshot(canonicalTarget)`은 `ReadingProgressJournalSnapshot`을 반환한다. `canonical`은 정확한 lowercase target의 nullable row이며, `aliases`는 같은 account/kind/UUID의 모든 detached raw 별칭과 전체 `DeviceReadingProgress`다. canonical 부재는 scope 전체 부재가 아니다. 기존 빈 row와 부재를 구분하고 별칭 순서만 비교에서 제외한다. raw target/remote/conflict UUID 표기, anchor Unicode·공백·offset·updatedAt, pending/queuedAnchor/conflict/retry 기한은 정규화하지 않는다. 입력과 getter 사본을 바꿔도 토큰은 바뀌지 않는다.

`compareAndWrite(target, expected, value, guard)`는 같은 canonical-directory `ServerJournalFileLocks`에서 inventory/전체 scoped token을 비교하고 canonical 파일만 쓴다. 별칭을 lowercase/merge/migration/삭제하지 않는다. PR #69의 strict raw JSON/중복키·UTF-8·65,536bytes·schema/hash/path 및 `.bak` 직접 우선 무복구 관찰을 재사용한다. 모든 recognized journal을 검증하므로 관련 없는 손상 row도 전체 관찰/CAS를 막을 수 있고 비용은 directory 행 수에 비례한다.

새 snapshot/CAS/guarded IO의 default는 `UnsupportedOperationException`이며 legacy read/pending/unconditional write로 fallback하지 않는다. 정상 read의 부재 기본값 `DeviceReadingProgress()`, format1, 기존 write/pending과 AtomicFile backup recovery는 CAS 밖에서 그대로다.

## 취소와 잠금 경계

expected token과 next record/bytes는 외부 guard 전에 복사하고 scope/schema/Unicode/크기를 검증한다. typed 값도 strict integer grammar로 검사해 `retryAfterUntil=Long.MAX_VALUE` 같은 관찰 불가능한 값을 쓰지 않는다. safe integer 경계는 보존하고 기존 legacy write 계약은 변경하지 않았다.

directory lock 안에서 guard→전체 token 비교→guard→guarded IO를 실행한다. mismatch는 false이고 mkdir/recovery/staging/쓰기 없이 끝난다. 미지원 guarded IO도 directory를 만들지 않는다. production IO는 prestage guard→AtomicFile stage/write/flush/fd.sync→**마지막 memory-only guard**→finishWrite다. stage 뒤 실패는 failWrite의 **논리 AtomicFile 복구**이며 원래 base/.bak 물리 배치 동등성을 보장하지 않는다. commit 뒤 guard는 없으며 durable 성공을 후속 취소로 rollback 표시하지 않는다. true는 기기 의도 저장이며 서버 ACK가 아니다.

공유 CAS scope는 같은 directory/alias store의 재진입 legacy read/recovery/write/pending 및 nested CAS를 row IO 전에 거절한다. 예외 뒤 scope는 해제된다. scope registry의 짧은 mutex는 IO/외부 guard/directory 획득을 감싸지 않는다. 이는 협력하는 단일 앱 프로세스 journal API의 보호이고 직접 파일 조작/외부 프로세스를 막는 기능은 아니다.

후속 caller는 gate→account→library→binding→journal 순서를 유지하고 journal guard에는 메모리 종료/세대 검사만 넣어야 한다. 네트워크 await는 monitor 밖이다. 이 저장 API는 계정/선택 권한, 실제 canonical text/fullproof/raw nonce, 최신 서버값/새 mutation 정책을 자동 증명하지 않는다. 전체 저장소 원자성·동일 bytes 재삽입 영속 세대·GET 뒤 미관찰 서버 변경의 동시점 보장도 없다.

## 검증과 다음 단위

- 신규 JVM **30개**, 집중5클래스 **119개** 성공(1분18초). 실제 JVM 파일·production observer/monitor와 주입 inventory/raw/staged IO로 부재/빈 row/alias-only, 순서 독립·전체 raw 값·backup, alias 삽입/삭제/변경 mismatch, canonical-only 쓰기, unsupported/corrupt failclosed, 두 CAS 한 승자, 최종 guard 실패/논리 복구·durable 성공, 재진입 거절/예외 뒤 해제를 검증했다.
- 최종 앱 **926개 중910개 통과/16 opt-in 제외**, 실패/오류0. APK/lint/계측 소스 컴파일/모듈 경계 성공: **1분36초**, 83 task 중69 재사용. 초기 테스트 실패는 없었다. 독립 검토에서 unsafe retry 정수 범위를 보완했다.
- 실제 Android AtomicFile 부재/재시작·별칭 보존·backup prestage 거절·두 CAS 경쟁·post-fsync 마지막 guard 복구의 **계측5개는 컴파일만 했고 실행하지 않았다**. JVM 주입 IO를 실제 Android AtomicFile/물리 SAF 근거로 쓰지 않는다.
- 새 실소켓/DB/브라우저/VM 로그인/Compose/SAF 실행이 아니다. 서버/웹 production·원본 PR34·기존 자료/자격 증명은 변경하지 않았다. ignored `.gradle-home/android-progress-snapshot-cas-*` focused/full/counts와 최종 CI JSON/PR 본문에 근거를 남긴다. 선행 PR #73 정확한 `3d217a53c837f3caac4fd037593cabf4f33dfd7d`/run42059539087 SUCCESS4분01초를 재확인했다.

다음은 `ServerReadingProgressViewModel.kt` 안 정상 `ServerReadingProgressSync`의 cached unconditional persist/OutboxReceived read→write 보호다. nullable canonical과 전체 snapshot durable base를 UI 기본값과 분리하고 expected-base CAS mismatch는 오래된 transition/성공·retry·terminal 부작용을 버린 뒤 최신 row reload·session/network 세대 무효화로 처리한다. 같은 계산을 새 base에 재시도하지 않는다. Page/Resolve/Received의 쓰기 없는 early return과 pump dispatch/cooldown 전에 재조회하고, 별칭 pending/high remote/conflict를 무시하지 않는다. uncertain ACK 동일 mutation, queuedAnchor/conflict/기한과 외부 변경 없는 stale form expectedVersion을 보존한다. 그 뒤 직접 GET·canonical/raw nonce/session 최종 guard와 새 mutation의 별도 채택을 연결한다. absent snapshot은 정보만/PUT 없음이며 기존 move/choose/open/connect/retry로 채택하지 않는다.
