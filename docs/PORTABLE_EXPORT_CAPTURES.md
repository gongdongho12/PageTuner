# 현재 기기 문서와 서버 연결을 검증하는 ZIP 준비

[draft PR #54](https://github.com/gongdongho12/PageTuner/pull/54)은 PR #53을 base로 한다. 연결 검증 `b057743`, canonical 저장소 `1fdd4b6`, 분류 ZIP 준비 `3a3249f`를 나눠 커밋·푸시했다. 이 단위는 실제 ZIP bytes를 만드는 함수까지이며 화면 다운로드·선택 수명·Android 계정 분류 export·명시적 채택은 후속이다.

## 원본과 연결의 독립적인 캡처

`createExchangeLibrary.get(id)`는 현재 계정의 저장소 row를 다시 읽어 문서 ID·전체 checksum·실제 자산 bytes/MIME·참조 순서·원래 읽기 기록의 연결 목록을 검증한다. `exportDocument(saved)`도 인수의 본문이나 자산을 믿지 않고 현재 row와 읽기 기록을 조회한다. 사용자가 변경한 메모·위치와 웹 편집 한도를 넘는 원래 native 메모를 함께 보존한다.

원문·번역본·로컬 파일·가져온 ZIP의 production 선택 항목은 모두 최신 저장소를 다시 읽는다. `choice.capture()`는 detached package와 `assertCurrent()`를 제공하며 ZIP 원본의 경우 최신 `portableBook`도 제공한다. guard는 전체 문서·ordered 자산 참조/실제 bytes·메모·위치와 private 저장 시각을 다시 비교한다. 매번 바뀌는 출력 ZIP의 생성 시각은 비교에서 제외한다. 목록의 제목·본문·자산이나 `glossarySource.book` 사본을 수정해도 내보낼 원본이 바뀌지 않는다.

번역본의 `notes.list`는 `serverProgress`가 있으면 기존 sync binding/outbox를 만들 수 있었다. passive export는 해당 필드 없이 로컬 기록만 조회하며 `noteSync`·`noteSyncDocuments`를 만들지 않는다. 기존 계정 리더의 정상 동기화는 유지한다.

`bindings.capture(book, client)`는 정확한 전체 text identity와 실제 verify 응답을 검사하고 immutable recordId/kind/identity 및 raw binding nonce의 `assertCurrent()`를 제공한다. 성공·실패 모두 연결을 저장하거나 해제하지 않는다. 기존 `open`은 손상·소실 원본의 관찰한 연결만 조건부 해제하며, 기다리는 중 같은 UUID로 재연결한 새 연결을 지우지 않는다. 이 연결 검사는 호출자가 준 문서의 검증이다. 기존 계정 읽기 화면의 canonical 재조회는 별도로 연결해야 한다.

## 계정 분류 ZIP 준비

`prepareFreshOrganizationExport`는 production canonical capture가 있는 선택 항목만 받는다. 서버 보관 문서는 정확한 원래 identity를 서버에서 확인하고, 가져온 ZIP은 별도로 명시적으로 연결된 계정 binding을 확인한다. 제목·UUID 힌트·Android 로컬 ID를 근거로 임의 병합하지 않는다. 자산 문서는 text API로 열지 않는다.

controller 없는 직접 GET 세션으로 현재 분류를 읽고 `libraryOrganizationSnapshot`에 넣는다. version0은 absent/null, 양수 version은 빈 값도 포함하는 present다. 폴더·Unicode/NFC/NFD·ordered tags·favorite·원래 identity·언어 대소문자 및 기존 organization/형제 확장을 보존한다. 미지원·불일치 기존 snapshot, extensions 전체256KiB·문서8MiB·기존 ZIP 한도 초과를 자르지 않고 거절한다. deleted 상태, 계정/origin·binding·자격 증명·CAS/outbox mutation은 넣지 않는다.

반환값은 `{ bytes, checkBeforeSave }`다. 화면은 실제 저장 직전에 `checkBeforeSave()`를 호출하고 선택·계정 세대도 동기적으로 검사해야 한다. guard는 journal 검사 뒤 canonical 문서를 다시 읽고 마지막으로 raw binding nonce와 세대를 검사한다. journal await 중 문서 삭제도 거절한다. pending/queued/conflict를 보내거나 지우지 않는다.

## 범위와 남은 경계

- 여러 저장소의 재조회·비교는 원자적인 다중 DB transaction이 아니다. 파일 삭제 뒤 동일 bytes·동일 private 시각으로 재삽입하는 변화는 비교만으로 검출하지 못한다. 화면은 오래 보관한 bytes를 재검사 없이 저장하면 안 된다.
- portable row 검증은 기존 ZIP writer를 사용해 자산·한도를 확인하므로 큰 파일의 반복 검증 비용이 있다. 모든 최대 크기 파일의 성능을 검증했다고 표시하지 않는다.
- 실제 분류 ZIP 선택/다운로드 UI와 unmount·계정A→B→A·origin/client/선택 변경 세대의 reader 발급을 연결하고 브라우저에서 검증해야 한다. 기존 일반 ZIP·용어집 export의 최종 guard 연결도 확인한다.
- Android 직접 GET/SAF, 별도 분류 채택, 최신 S1/S2 ZIP, PDF 계정 UX와 EPUB 원본 교환은 미완료다.

## 2026-10-20 검증

웹 전체720개/78파일·생성 계약15개·typecheck·production build가 통과했다. 공유 웹 build와 `:app:assembleDebug verifyModuleBoundaries`도 성공했다. production 저장소 adapter와 fake-indexeddb/모의 API로 캐시 사본 위조·변경/삭제·계정 격리·raw nonce 재연결·늦은 실패·실제 응답 불일치·outbox0건·absent/present-empty·Unicode/태그 순서·미지원 snapshot·전체 확장 한도 초과·인코딩/저장 대기 경합을 검사했다. 독립 검토에서 발견한 최종 journal await 중 원문 삭제 경합을 수정하고 회귀를 추가했다. 실패한 readonly binding capture도 기존 연결을 보존하도록 보완했다.

로그는 ignored `.gradle-home/canonical-portable-focused.log`, `canonical-portable-web.log`, `canonical-portable-apk.log`다. 실제 PostgreSQL 조회·브라우저 다운로드·물리 Android SAF 검증과 구분한다. 최종 문서 포함 PR head CI는 PR과 자동 실행 기록에서 대조한다. 선행 PR #53 최종 `6712522d4169cbdc8edc154552b3134c6e217144`의 [CI run39423233127](https://github.com/gongdongho12/PageTuner/actions/runs/39423233127)은2분59초에 성공했다.

[작업 큐](AGENT_WORK_QUEUE.md) · [남은 체크리스트](REMAINING_WORK_CHECKLIST.md) · [분류 GET 세션](PORTABLE_ORGANIZATION_EXPORT.md)
