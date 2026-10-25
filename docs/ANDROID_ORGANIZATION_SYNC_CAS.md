# Android 분류 sync actor의 nullable CAS·명시적 인계

후속 [PR #61 text 전용 commit guard 기반](PORTABLE_TEXT_COMMIT_GUARDS.md)이 library/binding verified-block과 명시적 수명 primitive를 추가했다. 실제 ServerLibraryViewModel/Compose 소유자와 canonical 분류 adapter·화면은 여전히 후속이다.

2026-10-25, [draft PR #60](https://github.com/gongdongho12/PageTuner/pull/60). Base `codex/android-organization-adoption-cas`(PR #59), head `codex/android-organization-sync-cas`. 구현·회귀 `a8e76f4`와 문서를 분리했다. [독립 채택 command 기반](ANDROID_ORGANIZATION_ADOPTION_CAS.md)과 기존 정상 동기화 actor가 journal을 덮어쓰던 경계를 조정한다. ZIP 채택 adapter/화면은 아직 연결하지 않는다.

## 동작

`ServerLibraryOrganizationSync`는 UI의 기본 빈 값과 실제 nullable durable base를 분리한다. open/configure/복구는 `readNullable`로 읽고 모든 저장은 `compareAndWrite(expected, next, guard)`를 사용한다. 새 API를 지원하지 않는 구현체에 unconditional write로 fallback하지 않는다.

CAS가 실패하면 계산한 transition을 버리고 현재 journal을 다시 읽는다. session을 변경하고 이전 network/timer를 취소하며 오래된 UI 동작을 거절한다. 별도 제어 경로로 현재 자료를 다시 평가하므로 거절된 transition의 retry/terminal/fetch 성공 부작용을 새 의도에 적용하지 않는다. stale 값을 새 base에 재시도하지 않는다.

Edit/Choose/Received는 local 값의 동일성 비교나 GET/ACK/실패 처리를 하기 전에 현재 journal을 확인한다. 이전 401/timeout처럼 파일을 쓰지 않는 오류도 새 의도를 중단/지연시키지 않게 이 경계를 적용한다. 정상 actor가 자기 queued edit를 저장한 경우는 현재 durable base와 일치하므로 기존 ACK 병합을 유지한다. 외부 변경이 없는 오래된 편집 form의 원래 expectedVersion 동작도 유지한다.

pump는 이미 알고 있는 모든 문서를 읽은 뒤 계정 cooldown과 request를 선택한다. inactive 문서의 새 pending·queued/conflict·retry 기한도 보존한다. 독립 채택의 새 UUID를 다시 발급하거나 timeout 재시도의 mutation을 바꾸지 않는다.

## 명시적 postcommit 인계

`notifyCommitted(target, expectedConnection, expectedSession)`는 이미 설정된 actor의 account/client 객체/session과 target account가 모두 맞는 경우에만 현재 journal을 읽고 전달한다. 계정을 configure/connect하지 않는다. 비교·거절 경로에서는 호출하지 않으며 실제 새 의도가 commit된 뒤 별도 adapter가 호출해야 한다.

이 API는 durable commit을 수행하거나 성공을 판정하는 함수가 아니다. A→B→A/같은 계정의 client 교체/이전 session callback은 거절한다. 다른 변경으로 session이 무효화된 ticket도 거절하므로 모든 경우의 즉시 전달을 보장하지 않는다. 이미 저장한 의도는 유지되며 이후 정상 open/configure 또는 유효한 명시적 인계에서 다시 발견할 수 있다. UI는 queued 성공과 서버 ACK를 구분해야 한다.

## 원자성 범위와 후속

파일 CAS는 journal 한 행의 비교/쓰기만 보호한다. 마지막 읽기와 HTTP dispatch는 하나의 transaction이 아니며 요청을 이미 선택한 두 actor가 같은 mutation을 전송할 수 있다. 서버의 mutation idempotency/CAS와 기존 conflict 처리가 이 경계를 담당한다. account/library/binding/journal/서버를 하나의 원자 transaction으로 묶거나 동일 bytes 삭제·재삽입의 영속 세대를 제공하지 않는다.

다음은 currentDocument가 reader 저장 완료 뒤 다시 읽은 실제 ZIP/canonical 문서·full-proof·raw binding nonce·terminal account/origin/client/선택 세대를 조합한 Android adapter와 별도 비교/확인 UI다. 기존 text binding validate는 잠금을 반환 전에 해제하므로 실제 CAS까지 library→binding 잠금을 유지할 verified-block/facade가 선행된다. ServerReadingConnection은 단순 값이며 별도 lifetime lock을 제공하지 않으므로 text 계정/선택 세대의 만료 경계도 설계해야 한다. PDF 전용 connection/UUID를 재사용하지 않는다. 명시적 빈 분류는 v0에도 새 빈 의도이고 absent는 정보만 표시한다. import/export는 파일 권한뿐 자동 계정 변경 권한이 아니다. journal 안의 최종 guard가 앞 library/binding 잠금을 획득하지 않도록 gate→account→library→binding→journal 순서를 유지한다.

## 검증

대상 **2클래스21개**(기존11·신규10), 전체 앱 **676개 중660통과/16 opt-in 제외**, backup-runtime50개·APK/lint/계측 소스 컴파일/모듈 경계가 성공했다(전체 명령1분26초). 기존 shared web task도 확인했고 웹 production 소스는 변경하지 않았다.

신규 회귀는 지연 GET/PUT의 성공·429·conflict·401·timeout 뒤 새 전체 journal 보존, 동일 값의 stale edit/선택·CAS 실패 후 부작용 폐기, inactive cooldown, idle 명시 인계·동일 mutation timeout/재시작, account ABA/client/이전 session 거절, 두 actor 경쟁·미지원 store fail closed를 검사한다. 독립 검토로 기존 own queued edit와 stale form expectedVersion 동작도 유지함을 확인했다.

첫 대상 실행은 stale conflict 테스트의 virtual clock이 IO 대기 중 cooldown까지 진행해 timeout됐다. cooldown 고정이 필요한 경합 fixture를 clock 0으로 고정했고 별도 timer 회귀는 시간을 실제로 진행한다. 이후 대상21개와 전체 검사가 통과했다. JVM 모의 transport·주입 저장소 검사이며 실제 AtomicFile/소켓/DB·물리 Android 실행이 아니다. 기존 계측 소스는 컴파일만 했다. 이번 단위는 새 화면·실제 서버 UI 검증을 제공하지 않는다.

ignored 근거: `android-organization-sync-cas-focused.log`, `android-organization-sync-cas-full.log`, `android-organization-sync-cas-counts.json`. 정확한 최종 head CI는 PR 본문과 `android-organization-sync-cas-final-ci.json`에서 대조한다. 원격 Android/core·서버 security·웹/shared proof 범위와 로컬 runtime/lint/계측 컴파일 범위를 구분한다.

선행 PR #59 정확한 최종 `d47e3b51c3035e9bb86a2ad292da0745a7500a6d`의 CI run40144073971 SUCCESS3분09초를 재확인했다. 원본 PR34 변경과 기존 DB/자료/자격 증명은 보존한다.
