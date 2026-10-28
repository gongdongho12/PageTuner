# 최신 계정 읽던 위치의 canonical ZIP 준비

2026-10-28 [draft PR #66](https://github.com/gongdongho12/PageTuner/pull/66), base `codex/fresh-reading-progress-reader`(#65), head `codex/canonical-progress-export`다. 구현·회귀 `1630a93`과 문서를 분리했다. 기존 `prepareAccountExchangeExport`의 선택적 `progress.reader`에 S1 직접 GET 값을 연결했다. **composer 기반만 완료**했으며 화면 선택·안전한 committed issuer·다운로드·Android 연결은 후속이다.

## 같은 문서에 누적하는 snapshot

production `choice.capture()`가 현재 저장소의 전체 문서·실제 자산 bytes·메모·위치·확장과 private 저장 시각을 다시 읽는다. 캐시 inventory나 `glossarySource.book`의 본문을 원본으로 사용하지 않는다. 가져온 text ZIP은 readonly `bindings.capture`로 전체 identity·명시 연결과 raw nonce를 검증하고 서버 보관 문서는 정확한 identity를 직접 verify한다. 로컬/자산 PDF를 text S1 UUID로 바꾸지 않는다.

`ProgressRegistry.freshReader`만 사용하며 controller/open/start/move/refresh/flush/drain을 열지 않는다. scope와 응답을 다시 검증하고 version0은 absent/null, 양수 version은 present/정확한 anchor로 `withReadingProgressSnapshot`에 넣는다. 실제 전체 text proof·문단 membership·UTF-16 끝/빈 문단0·surrogate 경계와 전체 DocumentIdentity를 검사한다. 같은 scope는 GET을 재사용하되 다른 전체 identity에 snapshot을 재사용할 수 없다.

용어집→분류→읽던 위치를 같은 document에 누적한다. 기존 `position`은 최신 서버 anchor로 옮기지 않는다. notes·legacy organization/glossary·다른 언어 scope·unknown sibling과 500개 용어집 항목의 모든 필드를 보존한다. 기존 own snapshot의 미지원/손상/identity 불일치와 extensions 전체256KiB·문서8MiB·기존 ZIP 한도 초과는 절단/덮어쓰기 없이 거절한다. progress 미선택 일반 ZIP은 account API 없이 passive 확장을 보존한다. binding·계정/origin·자격 증명·CAS/outbox는 새 snapshot에 넣지 않는다. absent는 삭제/reset이 아니다.

## 준비와 저장 경계

모든 reader/current/identity client·binding capture factory·encoder 참조와 선택 locator/capture callback을 첫 await 전에 고정한다. 첫 GET을 기다리는 중 options나 뒤쪽 선택의 source hint를 교체해 portable raw nonce 경로를 server verify 경로로 바꿀 수 없다. cached 본문/자산을 복사해 권한으로 사용하는 방식은 아니다.

GET 전후·인코딩 후와 `checkBeforeSave`에서 session/raw nonce를 확인한다. 최종 검사는 용어집/분류/progress의 모든 journal await 뒤 전체 canonical capture를 다시 읽고 마지막 동기 session/raw nonce guard를 검사한다. pending/queued/conflict와 version 역행/같은 version anchor·updatedAt 변경은 거절하며 기존 journal을 수정하지 않는다. 저장 소비자는 `await ready.checkBeforeSave(); ready.assertCurrent();` 뒤 await 없이 실제 저장해야 한다. 함수 오류는 고정 안내문만 전달하고 임의 transport body를 노출하지 않는다.

`current`는 실제 소유자가 닫는 terminal 세대 권한이어야 한다. 이번 수동 guard 회귀는 React 계정/origin/client/선택 ABA·unmount의 실제 issuer 연결 근거가 아니다. 다중 저장소 재조회는 전체 transaction이 아니며 동일 bytes·동일 private 시각 삭제/재삽입을 검출하지 못한다. GET 뒤 관찰되지 않은 서버 변경까지 동시점 snapshot으로 보장하지 않는다. alias journal scan과 큰 portable row 반복 검증 비용도 기존 한계다. 정상 앱 autodrain의 독립 PUT과 이 composer의 GET-only는 구분한다.

## 검증과 한계

- 신규 composer **40개**, 기존10개와 집중50개 통과. 전체 웹 **915개/86파일**, 생성 계약15개·typecheck·production·공유 웹 build 성공. APK/모듈 경계37초 성공(60 task 중58개 재사용). 새 Android 단위/계측 실행 근거가 아니다.
- production canonical adapter·readonly binding·실제 registry와 fakeIndexedDB/주입 API로 original/translation·absent·끝/빈0·500항목 동시 보존·ordinary ZIP API0·unsupported own snapshot·잘못된 anchor·삭제/메타 변경·같은 UUID nonce ABA·세션 만료·pending/queued/conflict·최종 highwater 변경·뒤쪽 선택/authority 참조 교체·중복 scope/다른 identity·한도/오류 redaction을 확인했다. 독립 검토 완료.
- 실제 production composer가 생성한 웹 ZIP **55,860bytes** → JVM typed read/proof/재부착/write **57,234bytes** → 웹 typed proof/read의 **8사례 전체 문서/metadata 동등성**을 확인했다. 문서마다 500개 용어집 전체 필드·다른 언어·legacy/sibling이 유지됐다. generation은 주입 verify8/progress GET8/분류 GET8/용어집 GET1, PUT0·외부 fetch0이다. JVM과 최종 웹 재조회에는 계정 요청0이다. 압축 bytes 자체 동일성을 주장하지 않는다.
- 교차 검사는 로컬 JVM 근거이며 현 원격 CI에 포함됐다고 표시하지 않는다. 새 브라우저/OS 다운로드·실제 HTTP 소켓/PostgreSQL·물리 Android/SAF 근거도 아니다. 이번 서버/DB/브라우저를 시작하거나 원본 PR34·기존 자료·자격 증명을 변경하지 않았다.
- 초기 미완성 구현 실행/fixture 오류와 마지막 추가 회귀의 mock 함수 타입 추론 오류를 고쳤다. 초기·최종 focused/typecheck/web/sharing/APK 및 교차 ZIP/JSON/Java 로그는 ignored `.gradle-home/canonical-progress-*`에 보존한다. 하위 에이전트 사용 한도 뒤 주 에이전트가 남은 검증과 독립 재검토를 마쳤다.

문서 포함 정확한 최종 head CI는 PR 본문/ignored `canonical-progress-final-ci.json`에서 대조한다. 선행 PR #65 `d51d4cd130e1a236179b1f6352878035dbd05740`/run40512392691 SUCCESS3분28초를 재확인했다. 체인 base를 보존하고 자동 병합하지 않는다.

다음은 고정 committed lease에 묶인 progress reader issuer·옵션·실제 다운로드와 브라우저 ABA/unmount 검증이다. 이후 Android 직접 GET/SAF와 별도 S1 채택(nullable journal/CAS·정상 actor cached write 보호), S2 계약/삭제 tombstone·feed 완결성, 네이티브 PDF·EPUB 원본 교환이 남는다. 파일 import/export는 계정 위치를 자동 채택할 권한이 아니다.

[GET 기반](FRESH_READING_PROGRESS_READER.md) · [passive 계약](PORTABLE_READING_PROGRESS_SNAPSHOT.md) · [기존 canonical capture](PORTABLE_EXPORT_CAPTURES.md) · [기존 계정 ZIP 화면](PORTABLE_ACCOUNT_EXPORT_UI.md)
