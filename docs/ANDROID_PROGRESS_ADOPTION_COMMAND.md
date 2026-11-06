# Android S1 직접 GET·별도 채택 command 내부 기반

2026-11-06, [draft PR #76](https://github.com/gongdongho12/PageTuner/pull/76). Base는 `codex/android-progress-sync-cas`(PR #75), head는 `codex/android-progress-adoption-command`다. **아직 미연결 내부 기반**이다. 실제 ZIP canonical 권한 adapter·Compose 선택 소유자·별도 확인 UI·정상 actor handoff는 후속이며, 빈 guard와 임의 UUID fixture를 실제 계정/선택 권한의 증거로 쓰지 않는다.

## 비교와 별도 의도

`ServerReadingProgressAdoption`은 캡처한 connection/account/client/target과 필수 terminal memory guard·canonical callback에 묶인다. `compare`는 직접 `HttpTranslationStore.readingProgress` GET과 strict 전체 snapshot 관찰만 한다. 정상 controller/open/connect/retry/move/choose·PUT·legacy read/pending/write를 열지 않는다. 부재와 기존 빈 canonical row를 구분하고 전체 raw alias token을 보존한다. 표시 사본과 private command를 분리하고 재비교/확정 토큰을 single-use로 다룬다.

`adopt(comparison, nonNullAnchor)`는 직접 GET 재비교·동일 서버값/기기 전체 base·canonical/종료 검사를 거쳐 새로운 UUID/expectedVersion 의도를 저장한다. 같은 anchor도 새 의도이며 retry 기한을 보존한다. pending/queuedAnchor/conflict는 거절·보존하고 기존 mutation을 새 의도로 재사용하지 않는다. 정상 actor가 지원하지 못하는 비canonical rawtarget 별칭도 failclosed하며 파일을 lowercase/merge/migration/삭제하지 않는다. 기존 raw view UUID 표기는 비교 scope 안에서만 case-equivalence로 다룬다.

여기서 non-null anchor는 후속 adapter가 검증한 **present ZIP snapshot의 사용자 선택값**이다. ZIP absent/null은 정보만/적용·reset 없음이다. present ZIP anchor는 서버 GET이 v0/null이어도 expectedVersion0으로 명시 적용할 수 있다. legacy `document.position`을 바꾸거나 최신 계정 anchor로 표시하지 않는다. 구조적 wire 검증과 실제 전체 text identity·문단 membership/UTF-16 끝·빈 문단0·surrogate 경계 검증은 구분한다.

## 관찰과 commit 경계

API와 journal highwater는 별도로 유지한다. journal 전체 remote/conflict batch의 version/anchor/updatedAt을 관찰한 뒤 dirty/별칭을 거절하므로 pending 거절·GET 실패·행 삭제가 최고 관찰을 낮추지 않는다. 오래된 낮은 journal 자체를 최신 GET의 대체물로 사용하지 않고, 최신 GET이 고수위보다 낮거나 같은 version의 다른 내용/시각이면 폐기한다. invalid response/손상·미지원 관찰/취소/current 실패/close는 terminal이며 늦은 응답이 새 권한을 빌리지 않는다.

최종 저장은 PR #74 전체 snapshot CAS다. 최종 guard는 journal 안에서 메모리 세대/종료/관찰/현재 job만 검사한다. 네트워크·canonical await는 account/library/binding/journal monitor 밖에서 한다. 실제 adapter는 `ticket.withCurrent → textBindings.verified → journal CAS`, gate→account→library→binding→journal 순서를 유지하는 store facade를 제공해야 한다. callback이 있다는 사실만으로 이 잠금과 권한이 실앱에 연결됐다고 표시하지 않는다.

true는 durable 기기 의도이며 서버 ACK가 아니다. commit 뒤 dispatcher 복귀 취소·close 등으로 이미 저장한 의도를 false rollback 표시하지 않는다. 후속 store facade도 durable block 뒤 추가 guard나 observer 예외로 성공 반환을 바꾸면 안 된다. 이 command는 정상 outbox를 깨우거나 즉시 전달을 보장하지 않는다. 동일 bytes 재삽입 영속 세대·전체 저장소/서버 transaction·GET 뒤 미관찰 서버 변경의 동시점 보장은 없다.

## 검증과 후속

- 신규 **41개**(command35/파일 결합6), 대상4클래스 **96개** 성공:43초. 전체 앱 **992개 중976개 통과/16 opt-in 제외**, 실패/오류0. APK/lint/기존 계측 소스 컴파일/모듈 경계 성공: **1분24초**, 83 task 중69 재사용. 독립 검토 완료.
- v0/같은 anchor의 새 UUID, nullable 부재/빈 row, private 비교 객체/복사·조작 거절과 single-use, pending/queued/conflict·기한 보존, GET 실패/삭제/dirty 거절 뒤 별도 고수위, 같은 version 다른 anchor/시각, 비최고 batch 불일치, raw view UUID 비교 projection, 지연 응답/취소·mutex 대기 취소/terminal, 최종 guard·CAS mismatch·두 command 한 승자와 postcommit 취소 성공을 확인했다.
- 실제 JVM 파일/production observer·공유 directory 잠금에 raw/guarded IO와 HTTP transport를 주입했다. 직접 GET만 호출하고 비교 중 파일 쓰기/backup 복구가 없으며, final guard 종료 거절·alias bytes 보존·중복 JSON 손상 후 repair로 부활 불가와 네트워크 대기 중 다른 thread의 snapshot 진입을 확인했다. 이 guarded IO는 실제 AtomicFile/stage/fsync 구현이 아니다.
- 첫 집중 실행은 파일 결합 테스트가 internal 비교 타입을 public 테스트의 반환형으로 노출한 compile 오류가 있어 Unit 반환으로 수정했다. production은 이때 컴파일됐고 이후 집중/전체 모두 성공했다. 초기 compile 로그와 최종 focused/full/counts는 ignored `.gradle-home/android-progress-adoption-command-*`에 보존한다. 기존 nullable Java interop 경고는 남는다.
- 새 실제 Android AtomicFile/소켓/DB/Android Context VM/Compose/SAF/기기 검증이 아니며 기존 계측 소스는 컴파일만 했다. 서버/웹 production·원본 PR34/자료/자격 증명은 변경하지 않았다. 문서 포함 정확한 최종 head CI는 PR 본문/ignored `android-progress-adoption-command-final-ci.json`에서 대조한다. 선행 PR #75 `16e59e8442b2b36c9242943d86e8b058dc2fecaa`/run42214484536 SUCCESS3분58초를 재확인했다. 체인 base 보존/자동 병합 금지.

다음은 reader 저장 완료 뒤 `PortableLibraryViewModel.currentDocument`가 읽은 실제 전체 ZIP 문서/bytes/assets/notes/position/raw extensions·full identity/fullproof, strict readonly v2 binding raw nonce, 실제 text account authority/선택 ticket을 연결하는 canonical adapter다. 표시 사본/비공개 command·single-use 비교 token을 분리한다. 정상 actor account/client/session ticket은 commit 전에 캡처하고 성공 뒤 still-current 권한에서만 명시 notify한다. 비교/거절이 idle actor를 깨우거나 configure/connect로 새 계정을 열면 안 되며, 인계 거절이 저장 성공을 취소하지 않는다. 그 뒤 별도 paged 비교/확인 UI를 연결한다.
