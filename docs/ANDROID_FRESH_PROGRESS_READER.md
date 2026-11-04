# Android 최신 읽던 위치 GET 전용 세션

2026-11-04 [draft PR #70](https://github.com/gongdongho12/PageTuner/pull/70), base `codex/android-progress-observations`(PR #69), head `codex/android-fresh-progress-reader`다. 구현/34개 회귀 `1785a74`·JVM 파일 결합6개 `006a7fb`·API23 호환 수정 `dff209e`와 문서를 분리했다. **내부 scoped GET-only 기반**이며 canonical ZIP adapter·실제 계정/Compose 선택 권한·SAF 옵션·명시적 채택은 후속이다. PR #69 strict 관찰과 기존 정상 sync를 재구현하지 않는다.

## 고정 scope와 관찰

`FreshServerReadingProgressReader`는 생성 시 accountKey/client/target과 필수 terminal current callback을 캡처한다. lowercase canonical UUID·account 일치를 IO 전에 검증하고, production 기본 query는 `HttpTranslationStore.readingProgress` 직접 GET만 한다. controller/open/connect/retry/move/choose/PUT을 열지 않는다. journal은 `observeAliases`만 쓰며 미지원/손상/불완전 결과를 기존 read/pending으로 대체하지 않는다. 정상 actor의 독립 auto-drain까지 중단하는 계약은 아니다.

UUID만 비교 scope에서 case-equivalence로 취급한다. alias batch의 remote/conflict 최고값을 순서 독립으로 관찰하고 anchor ID의 Unicode/NFC/NFD/앞뒤 공백/대소문자·offset·updatedAt은 그대로 비교한다. 같은 version의 다른 anchor/시간은 거절한다. API와 journal highwater는 분리하며 pending/queued/conflict 거절 전 최고 관찰을 보존한다. GET 실패·행삭제·대기 해제 뒤 낮은 관찰로 되돌리지 않는다. validated GET 최고값도 후속 journal의 pending 거절 전에 기억한다.

read는 GET 전후 journal을 검사하고 마지막 IO 복귀 뒤 다른 GET/journal이 앞선 관찰을 다시 검사한다. `checkReady(expected)`와 `checkBeforeWrite(expected)`는 **자신의 성공한 GET에서 반환한 고정 값**을 요구한다. 새 GET이 내부 latest 값을 갱신해도 과거 준비 bytes의 expected를 승인하지 않는다. 성공 GET 없는 최종 검사도 거절한다. pending·미조회·고정 값 변경 거절은 재비교할 수 있지만 invalid response/관찰 손상/취소/close/current 실패는 세션을 영구 폐기한다. 늦은 성공/오류가 새 계정 권한을 빌리지 않는다.

## IO와 권한 경계

비동기 read/checkReady의 journal IO는 주입 가능한 IO dispatcher에서 수행한다. 네트워크 await와 외부 current callback은 journal/memory monitor 밖이다. highwater/성공 GET 메모리만 별도 monitor로 보호한다. 동기 `checkBeforeWrite`는 **이미 storage IO dispatcher에서 실행 중인 후속 SAF writer**를 위한 소비 경계다. 이 함수가 main thread로부터 IO를 이동시키거나 완료된 read coroutine에서 현재 취소 권한을 추론하지 않는다. 호출자가 살아 있는 coroutine/ticket과 canonical 문서/raw nonce를 검사해야 한다.

이 단위의 manual current/test authority는 실제 PortableTextAccountLifetime·Compose owner/선택 연결 완료가 아니다. 단순 값 equality는 관찰되지 않은 A→B→A를 검출하지 못한다. 실제 account/selection owner가 종료하는 terminal ticket을 후속 adapter에서 연결한다. journal 관찰 이후 write/서버 변경을 막는 전체 transaction, 동일 bytes/private 시각 재삽입 영속 세대, GET과 최종 저장의 서버 동시점 보장은 제공하지 않는다. PR #69의 관련 없는 손상 journal도 전체 관찰을 막는 가용성 한계와 directory scan 비용을 유지한다.

## 검증

- 신규 **40개**(reader34/JVM 파일 결합6), 집중4클래스 **89개** 성공. 전체 앱146클래스 **826개 중810개 통과/16 opt-in 제외**, 실패/오류0. APK/lint/기존 계측 소스 컴파일/모듈 경계 성공. 집중2분22초, 최종 전체2분11초(83 task 중67개 재사용). 독립 검토 완료.
- deterministic dispatcher/주입 관찰로 alias 순서/같은 version 변경·pending/queued/conflict·실패/삭제 highwater·겹친 GET/마지막 IO 복귀 역전·성공 GET/고정 expected·취소/close/current 손실·미지원/손상 관찰을 검사했다. 실제 JVM 파일에 production observer와 production HTTP decoder/모의 transport를 결합해 원본/번역본 GET-only·raw 별칭 bytes 보존·backup pending/fixture 삭제·새 높은 별칭·중복 pending·HTTP await 중 directory monitor 해제를 확인했다.
- 최초 전체 lint는 `Map.putIfAbsent`의 API24 의존성(minSdk23)을 발견했다. 로컬 map 조회/대입으로 바꿔 같은 version 비교 의미를 보존했고 최종 전체 검사는 통과했다. 초기 실패를 ignored `android-fresh-progress-initial-lint-failure.log`, focused/full/counts와 함께 보존했다. 기존 nullable Java interop 경고는 이번 수정 범위가 아니다.
- Android stat/AtomicFile 실행·실소켓/DB·브라우저·계정 UI·SAF·물리 기기 증거가 아니다. 기존 계측 소스는 컴파일만 했고 새 계측 실행을 추가하지 않았다. 서버/웹 production·원본 PR34·기존 자료/자격 증명은 변경하지 않았다.

문서 포함 정확한 최종 head CI는 PR 본문/ignored `android-fresh-progress-final-ci.json`에서 대조한다. 원격 CI의 Android/core·서버 security·웹/shared proof 범위와 로컬 위 검사를 구분한다. 선행 PR #69 `93b5cc0487152ef1df40edc59e365a5fe94e6cb5`/run41442023480 SUCCESS4분06초를 재확인했다. 체인 base를 보존하고 자동 병합하지 않는다.

## 다음 단위

reader 저장 완료를 기다린 `PortableLibraryViewModel.currentDocument`로 실제 전체 ZIP bytes/document/자산/notes/position/extensions/fullproof를 다시 읽는다. strict text binding raw nonce·실제 terminal account/client/선택 ticket·direct verify/GET·`PortableExportTickets.checkBeforeWrite`에 이 reader의 고정 expected 검사를 연결한다. 용어집/분류/progress를 같은 문서에 누적하고 legacy position을 움직이지 않는다. v0는 absent/null, 양수는 present/정확 anchor이며 typed membership/본문 끝/빈 문단0/surrogate 경계와 전체 identity/한도를 검사한다. PDF 별도 binding/UUID는 text S1~S3에 사용하지 않는다.

그 뒤 VM/SAF 기본 해제 옵션과 committed 선택 수명을 연결한다. 별도 S1 채택은 nullable CAS와 정상 ServerReadingProgressSync의 cached unconditional persist 보호부터 진행하며 기존 move/choose/open/connect/retry를 재사용하지 않는다. PR #67 OS 파일 경로 질문은 반복하지 않고 실제 경로가 오면 독립 ZIP 재개봉을 보완한다.

후속 잠금 검토: 기존 prepareAccountMetadataExport의 PortableAccountExportLease/readingConnection equality만으로 실제 text account lifetime을 증명하지 않는다. 새 adapter는 렌더 당시 실제 authority의 PortableTextOperationTicket을 필수로 받는다. reader.current에는 ticket.check와 메모리 종료/취소 검사만 연결하고 exportTickets.check 같은 앞 monitor 재획득 callback을 넣지 않는다. PortableExportTickets.prepare는 자체 monitor 안에서 guard를 호출하므로 export-ticket→gate 순서와 역순 중첩을 먼저 검토한다. 최종 IO guard는 ticket.withCurrent→text verified→reader.checkBeforeWrite(expected)로 gate→account→library→binding→journal을 유지하며 그 안에서 exportTickets를 다시 호출하지 않는다.
