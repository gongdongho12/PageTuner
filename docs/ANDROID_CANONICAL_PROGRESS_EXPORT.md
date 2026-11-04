# Android canonical 읽던 위치 ZIP 준비

2026-11-04 [draft PR #71](https://github.com/gongdongho12/PageTuner/pull/71), base `codex/android-fresh-progress-reader`(PR #70), head `codex/canonical-android-progress-export`다. exact owner 검사 `f769d60`·adapter/17개 회귀 `d0c213a`·composer/8개 회귀 `1dcedf0`와 문서를 분리했다. **실제 production authority/ticket 타입을 필수로 받는 canonical adapter와 공통 composer**를 완료했다. VM·committed Compose 옵션·SAF UI 연결과 S1 명시적 채택은 아직 후속이다.

## 고정 권한과 최신 자료

`PortableAccountReadingProgressExport`는 captured entry·`PortableTextAccountAuthority`·`PortableTextOperationTicket`을 필수로 받는다. `authority.check(ticket)`는 account key/client 값뿐 아니라 ticket의 private lifetime 객체가 해당 authority 소유자인지 메모리로 확인한다. 두 ACTIVE authority가 같은 key/client를 공유해도 서로의 ticket을 빌릴 수 없다. 앞 monitor를 획득하지 않아 최종 journal guard 안에서도 사용할 수 있다. 이 추가 검사가 기존 모든 consumer를 자동으로 바꾼 것은 아니다.

production `currentDocument` callback은 reader 저장을 기다리고 실제 저장 ZIP을 다시 읽는다. adapter는 cached entry.document를 본문 원본으로 삼지 않고, 선택된 전체 document의 메타데이터·본문/순서·outline·notes·position·legacy 분류/용어집·raw extensions를 복사한다. asset 없는 text/full DocumentIdentity와 기존 owned snapshot·8 MiB 문서/256 KiB extensions/기존 ZIP 한도를 검증하고 strict readonly v2 binding capture의 account/local key/identity/raw nonce를 고정한다. 실패가 binding을 migration/쓰기/삭제하지 않는다.

계정 요청은 `verifyPortableIdentity`와 `HttpTranslationStore.readingProgress` 직접 GET뿐이다. PR #70 reader의 strict alias journal·별도 API/journal highwater·pending/queued/conflict 거절·고정 expected readiness를 재사용하며 controller/open/connect/retry/move/choose/PUT을 열지 않는다. verify/read/checkReady 뒤마다 canonical 문서를 다시 읽는다. adapter는 single-use이며 새 prepare로 기존 준비 handle과 별도 highwater reader를 동시에 발급하지 않는다. 실패/취소/종료는 해당 작업을 폐기한다.

## 최종 IO와 snapshot

최종 동기 `checkBeforeWrite`는 **IO dispatcher의 후속 writer**가 호출해야 한다. `ticket.withCurrent → textBindings.verified → reader.checkBeforeWrite(pinnedView)`로 gate→account→library→binding→journal을 유지한다. reader.current는 adapter 종료와 exact owner/ticket의 메모리 상태만 검사한다. 네트워크 await나 exportTickets.check 등 앞 monitor 획득을 이 callback에 넣지 않는다. 준비 중 async 검사와 마지막 canonical 재조회도 이 최종 경로를 재사용한다.

v0는 absent/null, 양수는 present/정확 paragraphId와 UTF-16 offset이다. typed 부착은 실제 문단 membership·본문 끝·빈 문단0·surrogate 경계 및 전체 text proof를 검증한다. legacy position/notes를 서버 anchor로 이동하거나 reset/deleted를 만들지 않는다. 반환 문서와 private canonical/source의 목록은 별도 사본이다. 준비 객체의 표시 문서를 변경해도 실제 canonical 검증 대상을 바꾸지 않는다.

이 guard는 저장 전 관찰이며 전체 account/library/binding/journal/서버/SAF의 원자 transaction이 아니다. 선택된 전체 문서를 검증하지만 ZIP 압축 bytes·container createdAt·다른 비선택 문서의 동일성, 동일 bytes/private 시각 삭제 재삽입 영속 세대, GET 뒤 미관찰 서버 변경의 동시점 보장은 제공하지 않는다. 완료된 prepare coroutine에서 이후 SAF write job의 취소 상태를 추론하지 않는다. 실제 writer가 현재 job과 committed 선택을 유지해야 한다.

## 한 문서의 동시 metadata

`combineAccountMetadataExports`는 glossary→organization→progress를 같은 canonical 문서에 누적하며 progress-only도 허용한다. source key·전체 canonical document·account key/client 객체·full binding(record/identity/nonce)이 모든 준비 객체에서 같아야 한다. production glossary/organization도 detached source를 보존한다. canonical source는 private 값이고 getter는 새 사본을 주므로 반환된 source/document 목록을 함께 변조해 원본을 바꿀 수 없다. 고정 source에서 각 준비 문서를 재구성·대조하고 모든 source guard를 저장 때까지 유지한다.

기존 synthetic glossary/organization-only 호출은 호환되지만 progress 조합에는 provenance 없는 준비 객체를 사용하지 못한다. provenance는 내부 자료이며 ZIP에는 binding/account/origin/client/자격 증명/CAS/outbox를 넣지 않는다. 용어집500개 전체 필드·다른 언어 scope·분류 ordered Unicode tags/공백·favorite·legacy notes/position·unknown sibling을 보존한다. 개별 snapshot이 유효해도 합친 extensions/문서/ZIP 한도가 넘으면 절단/제거 없이 거절한다.

## 검증과 한계

- 신규 **25개**(adapter17/composer8). 집중6클래스64개가 1분39초에 통과했고, 추가 합산 크기 경계 사례와 combined legacy note를 포함한 최종 전체 앱148클래스 **851개 중835개 통과/16 opt-in 제외**, 실패/오류0. APK/lint/기존 계측 소스 컴파일/모듈 경계가 1분59초에 성공했다(83 task 중68개 재사용). 독립 검토 완료, 초기 테스트 실패 없음. 기존 nullable Java interop 경고는 유지한다.
- 실제 JVM ZIP/binding 파일과 production authority/lease/ticket에 주입 journal/query/transport를 결합했다. reader 저장 대기·cached 본문 무시·전체 문서/legacy 보존·absent/끝/빈0/잘못된 UTF-16·문서 삭제/메타 변경·같은 UUID nonce ABA·held GET의 계정/선택 종료·같은 key/client의 다른 ACTIVE owner·pending/queued/conflict·늦은 응답/취소·최종 journal 중 binding writer 대기를 확인했다. default HTTP adapter는 CSRF fixture를 포함한 주입 transport이며 identity verify POST/progress GET만 확인했다.
- production glossary/organization 준비와 progress composer를 ZIP write/read에 결합해 용어집500항목 모든 필드·ja scope·분류/ordered Unicode·정확 anchor·full identity·legacy notes/position·sibling을 확인했다. 다른 source key/동일 proof의 다른 메타데이터·account/client/record/nonce 혼합, provenance/output 동시 변조, 최종 guard 만료와 개별 유효/합산256 KiB 초과를 거절했다.
- 실제 VM 로그인/Compose 실행·SAF 파일 provider/회전/재생성·Android stat/AtomicFile 실행·실소켓/새 DB/브라우저 근거가 아니다. 계측 소스는 컴파일만 했다. 이번 서버/DB·웹 production·원본 PR34/자료/자격 증명은 변경하지 않았다. ignored `canonical-android-progress-focused.log/focused-counts.json/full.log/counts.json`에 근거를 보존했다.

문서 포함 정확한 최종 head CI는 PR 본문/ignored `canonical-android-progress-final-ci.json`에서 대조한다. 로컬 위 검사와 원격 Android/core·서버 security·웹/shared proof 범위를 구분한다. 선행 PR #70 `b2a5e7ca65910d517b9b776fb9ed091ba2dc3b3a`/run41833211545 SUCCESS4분13초를 재확인했다. 체인 base를 보존하고 자동 병합하지 않는다.

## 다음 단위

기존 VM/identity 패널에 기본 해제 includeProgress와 실제 captured authority/committed operation ticket을 연결한다. 현재 prepareAccountMetadataExport의 panel lease/readingConnection equality만으로 실제 수명을 증명하지 않는다. 옵션/언어/선택/계정/client/닫힘/unmount의 terminal 세대를 발급하고 이전 callback은 새 getter/ref를 빌리지 못하게 한다.

PortableExportTickets.prepare는 자신의 monitor 안에서 guard를 호출하므로 export-ticket→gate 순서와 역순 중첩을 먼저 조정한다. 새 adapter current에 기존 exportTickets.check closure를 전달하지 않고, gate/journal 안에서 exportTickets를 다시 획득하지 않는다. 실제 SAF destination open 전후와 쓰기 직전 고정 bytes/살아 있는 job·canonical/raw nonce/journal/ticket을 검사한다. UI/기기 검증은 V3이며 이번 adapter 근거로 대신하지 않는다. S1 별도 채택은 nullable journal CAS와 정상 progress actor의 cached unconditional persist 보호부터 진행한다. PR #67 OS 파일 질문은 반복하지 않는다.
