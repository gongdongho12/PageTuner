# Android 텍스트 채택의 잠금·수명 기반

2026-10-25, [draft PR #61](https://github.com/gongdongho12/PageTuner/pull/61). Base `codex/android-organization-sync-cas`(PR #60), head `codex/text-adoption-commit-guards`. binding `ae00dae`·수명 `909ddf9`·결합 회귀/clock `d888ff4`를 분리했다. 이 단위는 동기 journal commit callback 동안 canonical 문서·text binding·명시적으로 활성화한 계정/선택 수명을 유지하는 **내부 기반**이다. 실제 계정 소유자/Compose lifecycle과 분류 채택 adapter·화면은 아직 연결하지 않는다.

## canonical 문서와 raw binding

`PortableTextBindingAccess.verified(accountKey, entry, snapshot, current, block)`는 외부 guard 전에 caller의 전체 문서 목록을 분리하고 기존 ZIP 한도와 전체 text proof를 검증한다. library의 현재 문서를 다시 읽어 제목·본문·outline·위치·notes·legacy organization·glossary·raw extensions까지 정확히 비교한다. 문서 identity만 같은 메타데이터 변경도 거절한다. 자산이 있는 PDF/EPUB 경로는 text 권한을 열지 않는다.

그 뒤 `PortableServerBindingStore.verified`가 binding 잠금을 유지하며 v2의 전체 snapshot/raw nonce·account/localKey/record/identity를 비교하고 block을 수행한다. `readStored`로 읽으므로 실패한 확인이 v1을 migration하거나 연결을 쓰거나 지우지 않는다. 기존 일반 readSnapshot의 v1→v2 migration은 유지한다. 부재·tombstone·손상·미지원·잘못된 UTF-8·64 KiB 초과는 연결로 인정하지 않으며 bytes를 보존한다. 같은 UUID로 해제/재연결한 경우도 다른 nonce로 거절한다.

서로 다른 인스턴스와 canonical 디렉터리 alias도 같은 library/binding 잠금을 사용한다. verified 범위의 읽기는 허용하되 재진입 save/remove/migration/import/update는 shared verified-depth로 거절한다. JVM monitor가 재진입 가능하다는 이유로 같은 thread의 callback이 자기 canonical 문서나 binding을 바꿀 수 없게 한다. block은 다음 journal 잠금만 획득해야 하며 앞 계정/library 잠금을 재획득하면 안 된다. 직접 파일을 바꾸는 외부 프로세스를 막는 장치는 아니다.

## text 전용 계정/선택 수명

`PortableTextAccountLifetime`과 `PortableTextOperationLease`는 각각 NEW→ACTIVE→CLOSED이며 close 뒤 재활성화하지 않는다. 소유자는 committed 계정/선택마다 새 객체를 만들고 logout/account/client·선택/옵션 변경·패널 해제에서 이전 객체를 종료해야 한다. 이전 callback은 이전 lease만 캡처하며 현재 객체를 찾아 세대를 빌려 쓰면 안 된다.

issue는 정확한 accountKey/client 객체/selectionKey를 확인하고 고정 ticket을 반환한다. `ticket.check()`는 volatile 상태의 **메모리 확인만** 하므로 journal의 마지막 guard에서 monitor를 다시 획득하지 않는다. 실제 저장은 `ticket.withCurrent { connection -> ... }`로 감싸 gate→account monitor를 유지한 채 library→binding→journal에 진입해야 한다. check만 전달하면 저장 중 계정 close를 직렬화한 것이 아니다.

동시 close는 synchronous commit callback이 끝날 때까지 기다린다. 같은 thread에서 commit 전에 close하면 마지막 memory guard가 거절하며, commit 뒤 close는 이미 반환할 성공을 rollback으로 바꾸지 않는다. block 뒤에는 후속 guard가 없다. 네트워크 await는 이 잠금 범위 밖에서 해야 한다. PDF 전용 connection/UUID를 사용하지 않으며 이 primitive의 connection 값 자체가 ZIP/계정 권한을 부여하지 않는다.

## 다음 연결

실제 `ServerLibraryViewModel` 소유자에서 text lifetime을 일관된 connection과 함께 발급하고 store 교체/계정 입력·비밀번호 세션 변경/disconnect/onCleared 전에 이전 수명을 종료해야 한다. 현재 readingConnection은 store와 state에서 값을 만드는 lookup이며 이 primitive를 자동으로 갱신하지 않는다. 실제 lifecycle을 연결하기 전 수동 소유자 테스트를 앱의 실계정 ABA 방어 완료로 기록하지 않는다.

그 뒤 reader 저장을 기다린 currentDocument·typed 분류 snapshot·직접 verify/GET·nullable journal·PR #59 command를 연결한다. 최종 compareAndWrite facade는 ticket.withCurrent→text verified→journal 순서로 감싸고, durable 성공 뒤 캡처한 PR #60 notifyCommitted ticket으로만 인계한다. absent는 정보만 표시하고 명시적 빈 값도 v0에서 새 의도다. import/export는 자동 계정 변경 권한이 아니다. 별도 paged 비교/확인 화면과 실제 기기 검증은 후속이다.

## 범위와 검증

한 앱 프로세스 안의 협력하는 저장소 API에 대한 잠금이다. 라이브러리/binding/journal/서버를 묶은 전역 transaction, package createdAt 전체의 동일성, 동일 bytes 삭제·재삽입의 영속 세대를 제공하지 않는다. 기존 extension 256 KiB·문서 8 MiB·ZIP 한도와 binding/계정/자격 증명/CAS/outbox ZIP 제외를 유지한다.

대상 **5클래스47개**, 신규3클래스31개(binding15·수명12·결합4)와 cooldown 단독 회귀가 통과했다. 전체 앱 **707개 중691통과/16 opt-in 제외**, backup-runtime50개·APK/lint/계측 소스 컴파일/모듈 경계도 성공했다(최종 전체 명령30초, 기존 task 재사용 포함).

실제 JVM ZIP/binding 파일·Thread.BLOCKED 동시성·readonly alias 재진입·raw nonce ABA·손상/한도 보존·전체 문서/notes/위치·세대 만료를 검사했다. PR #59 command를 실제 중첩 잠금과 결합해 stage 중 계정/선택 종료·library update·binding remove가 대기함과 commit 뒤 성공 유지, 재진입 종료 전 commit 거절을 확인했다. **journal과 계정 query는 주입된 모의 객체**다. 실제 Android AtomicFile/HTTP/PostgreSQL/계정/Compose UI·물리 기기 실행 근거가 아니다. 기존 계측 소스는 컴파일만 했다. 서버/DB/웹 production은 변경하지 않았다.

독립 검토와 첫 실행에서 facade의 첫 guard가 binding readonly 범위 밖에 있어 재진입 remove가 가능함을 확인했다. 이 조기 guard를 제거해 최초 외부 guard를 library/binding 양쪽 readonly 안으로 옮겼고 최종 대상47개가 통과했다. 전체 검사 중 기존 Retry-After 테스트의 virtual clock/실제 IO 대기 timeout도 확인해 논리 clock을 분리했다. 119999/120001 경계와 timer 진행·최종 두 요청 검증을 유지했고 이후 전체 검사가 통과했다.

ignored 근거: `text-adoption-guards-focused.log`, `text-adoption-guards-cooldown.log`, `text-adoption-guards-full.log`, `text-adoption-guards-counts.json`, 초기 실패 `text-adoption-guards-initial-failure.log`·`text-adoption-guards-first-full-failure.log`. 정확한 최종 head CI는 PR 본문과 `text-adoption-guards-final-ci.json`에서 대조한다. 선행 PR #60 최종 `2e76f6d9073917be36b2efad0d4bba4ebe596c6b`/run40148067823 SUCCESS3분55초를 재확인했다. 원본 PR34 변경과 기존 DB/자료/자격 증명은 보존한다.
