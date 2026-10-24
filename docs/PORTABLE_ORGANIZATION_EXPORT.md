# 최신 계정 분류 ZIP export 준비

2026-10-24 후속 범위: 실제 웹 ZIP 다운로드는 [PR #55](PORTABLE_ACCOUNT_EXPORT_UI.md), Android 직접 GET/SAF 연결은 [PR #56](ANDROID_ORGANIZATION_EXPORT.md)에서 완료했다. [PR #57 웹 채택 CAS 기반](ORGANIZATION_ADOPTION_CAS.md)도 추가했으며 별도 identity/binding 비교 adapter·채택 UI·Android 채택은 남아 있다. 아래 준비 기록을 최신 미완료 목록으로 재사용하지 말고 작업 큐를 따른다.

S4c3b의 첫 단위는 웹의 **직접 GET 세션**이다. [draft PR #53](https://github.com/gongdongho12/PageTuner/pull/53)은 PR #52를 base로 하며 구현은 `73978d8`이다. 후속 [PR #54의 canonical 캡처·ZIP bytes/저장 guard](PORTABLE_EXPORT_CAPTURES.md)까지 추가했으며 실제 다운로드 화면·명시적 채택은 아직 연결하지 않았다.

## 현재 구현

`OrganizationRegistry.freshReader(current)`는 sync controller를 열지 않고 원래 client의 `get`만 호출한다. 기기 journal은 `snapshot()`으로 읽고 닫으며 초기화·저장·PUT·retry deferral을 수행하지 않는다. Provider의 기존 outbox drain 동작은 독립적으로 유지되므로 이 helper가 계정 전체 동기화를 정지시키는 것은 아니다.

- GET 전후와 최종 `checkReady`에서 pending·queued·conflict를 거절한다.
- 정확한 kind/record UUID 및 응답 계약을 검증한다. absent/null과 명시적으로 비운 present 값, Unicode·태그 순서를 유지한다.
- GET 전후의 journal, 이전에 성공한 응답, 겹쳐 실행된 GET의 최신 관찰과 version·내용을 비교한다. 낮은 version과 동일 version의 다른 내용/updatedAt을 거절하며 거절된 응답으로 관찰 값을 바꾸지 않는다.
- registry 종료, 호출자가 전달한 세대 guard의 실효 또는 abort 이후에는 같은 reader를 재활성화하지 않는다. 반환값과 관찰 사본을 복제해 화면/API 객체 변경이 검증 기준을 바꾸지 않게 한다.

이 세션은 계정 분류를 읽을 뿐 문서 identity·출처·binding·ZIP 저장 권한을 부여하지 않는다. UI 통합 시 호출자 guard에는 계정/origin/client/선택의 **변경 세대**를 넣어야 한다. 단순 현재 값 동등성으로 A→B→A를 허용하지 않는다. 사용하지 않는 hook은 독립 검토에서 발견한 이전 callback의 발급 수명 문제 때문에 이번 단위에서 제거했다. 실제 화면에서 unmount·재선택·늦은 callback의 reader 발급을 검증하며 연결해야 한다.

## 남은 실제 export 경계

다음은 현재 저장소 문서와 raw binding nonce를 보존하는 export builder다. `exchangeExportChoices`의 목록 사본과 `exportDocument(saved)`를 그대로 최신 문서로 간주하지 않는다. `portableBinding.open`의 UUID 반환값만 비교하지 말고 raw nonce ticket을 사용하며 늦은 실패가 새 binding을 지우지 않게 보완한다.

전체 text identity와 본문 proof·삭제 여부를 GET 전후 및 인코딩/저장 직전에 다시 검사한다. `LibraryOrganizationSnapshot`의 absent/present-empty, 기존 organization/형제 확장을 보존하고 extensions 전체256KiB·문서8MiB 초과나 미지원 확장을 자르거나 덮지 않는다. 분류에는 deleted가 없다. Android는 저장 완료 뒤 canonical ZIP 재조회·직접 GET·SAF 티켓을 연결해야 한다. 채택은 별도 비교·사용자 확정·새 CAS mutation 단위다.

## 2026-10-20 검증

- 대상 Provider 검사21개(기존 outbox1개와 새 조회20개), 전체 웹685개/76파일, 생성 계약15개·typecheck·production build 성공.
- production IndexedDB 저장소 adapter를 fake-indexeddb와 모의 API에 연결해 journal 무변경/PUT0회, pending·queued·conflict 전후 검사, absent/present-empty, Unicode, 이전 응답·동시 GET 역전, 잘못된 scope, 세션 종료/abort/늦은 성공·실패 및 반환 사본 변경을 검사했다.
- 독립 코드 검토의 hook 수명 결함은 미사용 hook 제거로 해결했다. 이 검증은 실제 PostgreSQL·브라우저 ZIP 다운로드·물리 Android 검증이 아니다.
- 로그: ignored `.gradle-home/fresh-organization-focused.log`, `fresh-organization-web.log`. 재현: `npm --prefix web test -- src/components/LibraryOrganizationProvider.test.ts`, `npm --prefix web run verify`.

선행 PR #52의 최종 `b220093b99c3af3cfed5fadd8cc0cfaf98fbaa8a` [CI run39422238326](https://github.com/gongdongho12/PageTuner/actions/runs/39422238326)은2분39초에 성공했다. PR #53의 문서 포함 최종 head CI는 큐와 PR에서 별도로 대조한다. 전체 S4c3b와 S4는 미완료다.

[작업 큐](AGENT_WORK_QUEUE.md) · [남은 체크리스트](REMAINING_WORK_CHECKLIST.md) · [passive snapshot](PORTABLE_ORGANIZATION_SNAPSHOT.md) · [Android 연결 세대](PORTABLE_BINDING_GENERATIONS.md)
