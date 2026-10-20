# 최신 계정 용어집·분류의 웹 ZIP 내보내기

[draft PR #55](https://github.com/gongdongho12/PageTuner/pull/55)은 PR #54를 base로 한다. 세션 발급 `ab33a20`, 공통 ZIP 준비 `3d684b0`, 화면 연결 `746f42e`를 나눠 커밋·푸시했다. 전체 S4c3b/S4 완료를 뜻하지 않는다.

## 현재 사용 방법

ZIP 내보내기에서 기기에 보관한 책을 선택하고 **내보내기 설정**을 연다. **최신 계정 용어집 포함**과 **최신 계정 분류 포함**을 각각 선택할 수 있다. 용어집은 선택한 언어, 분류는 폴더·순서 있는 태그·즐겨찾기를 서버에서 새로 읽는다. 가져온 ZIP은 먼저 정확한 서버 원본과 명시적으로 연결해야 한다. 계정 연결이 없으면 두 옵션을 끄고 일반 ZIP으로 내보낼 수 있다.

일반 ZIP·용어집·분류·동시 선택 모두 `prepareAccountExchangeExport`를 사용한다. production `choice.capture`로 현재 canonical 문서·실제 자산·읽기 기록을 준비하고 계정 정보를 포함할 때만 정확한 전체 text identity와 readonly raw binding nonce를 확인한다. 화면 목록이나 `glossarySource.book`의 사본은 원본으로 쓰지 않는다. 두 snapshot을 같은 문서 사본에 적용하므로 기존 언어 scope·legacy organization/glossary·형제 확장을 보존한다.

용어집의 absent/deleted/present-empty와 분류의 absent/present-empty를 구분한다. 분류에 deleted는 없다. 전체 항목 ID·순서·공백·표시 별칭·종류·활성·대소문자, Unicode/NFC/NFD와 원래 identity를 유지한다. extensions 전체256KiB·문서8MiB·기존 ZIP 한도와 미지원 확장은 자르거나 덮지 않고 거절한다. 파일 가져오기는 계정 채택 권한이 아니며 계정/origin·binding·자격 증명·CAS/outbox mutation은 ZIP에 넣지 않는다.

## 수명과 저장 직전 검사

`useExportSession`은 layout effect가 확정될 때마다 새 terminal lease를 발급한다. cleanup 뒤 이전 `begin`이나 작업 callback은 새 lease를 채택할 수 없다. StrictMode의 cleanup/setup도 새 객체이며 계정 A→B→A·client·origin·선택/옵션 변경과 unmount가 이전 작업을 무효화한다. `useFreshLibraryOrganization`와 보완한 `useFreshBookGlossary`는 controller를 열지 않는 직접 조회 reader만 발급한다. 용어집도 이전/겹친 GET의 version 역행·동일 version 내용 변경을 교체 전에 거절한다.

준비 단계와 실제 다운로드 직전에 journal→canonical 문서→동기적 raw nonce/session 검사를 수행한다. 최종 `assertCurrent`와 link click 사이에는 await가 없다. pending/queued/conflict를 보내거나 지우지 않으며 실패하면 이전 bytes를 내려받지 않는다. 전송 중 취소·계정 전환도 늦은 응답을 폐기한다.

가져온 책의 일반/계정 읽기도 `storage.get`으로 현재 문서를 조회하고 위치 조회 뒤 canonical checksum/private 가져온 시각을 다시 비교한다. 계정 읽기는 raw binding ticket도 최종 검사하며 계정/client 변경 시 열려 있던 읽기·확인·채택 화면을 닫는다. 다중 DB 재조회는 원자 transaction이 아니고 동일 bytes·동일 private 시각 재삽입은 검출하지 못한다는 [기존 한계](PORTABLE_EXPORT_CAPTURES.md)는 유지한다.

## 2026-10-21 검증과 한계

- 웹740개/80파일·생성 계약15개·typecheck/production build, 공유 웹 build·APK·모듈 경계가 통과했다. 최종 문구 수정은 typecheck 및 공유 웹/APK 재빌드로 별도 확인한다. Android Kotlin 동작을 변경하지 않았으며 물리 기기 실행 증거는 아니다.
- 실제 인앱 브라우저의 React StrictMode에서 이전 organization/glossary begin·reader, A→B→A·client 교체·unmount와 origin lease 만료10사례를 확인했다. API 요청0회였다. origin 검사는 합성 origin 입력에 대한 lease 검사이며 실제 다른 origin으로의 로그인 이동 검증은 아니다. 선택 변화는 lease 회귀와 실제 설정 화면 전환으로 확인했다.
- 실제 UI에서 용어집/분류 동시 선택→준비→브라우저 download 이벤트→OS 다운로드 파일을 확인했다. 파일5,899bytes, SHA-256 `397f093d39df70e78c6b46d365d323c5a48b77ca6690550f15bef56ccbf35a53`. 독립 Python ZIP readback으로500항목 전체 필드, 원래 문단/identity, 분류·태그 순서/NFC/NFD/중간 공백을 비교했다. 원래 Downloads 파일은 보존했다.
- GET을 멈춘 상태에서 계정 A→B→A 후 응답을 풀어도 다운로드0회·PUT0회였다. 계정 연결을 끈 뒤 이미 선택한 옵션을 해제할 수 있음을 실제 UI에서 확인했다. 이번 계정 API는 모의 응답이며 PostgreSQL UI 종단 결과로 표시하지 않는다.
- ko/en390×844·844×390에서 DOM 가로 넘침 없음, 옵션 설명의 실제 높이가 행 안에 들어옴, 보이는 버튼 및 checkbox label44px 이상을 측정했다. 스크린샷 API가 두 차례 시간 초과되어 새 시각 캡처 근거는 없다. 다른 브라우저/프로세스로 우회하지 않았다.

ignored `.gradle-home/organization-export-screen-web.log`, `organization-export-screen-apk.log`, `organization-export-screen-download.json`에 근거를 보존한다. 브라우저 검증 harness는 managed worktree의 ignored `web/.gradle-home/export-qa.*`이며 배포 코드에 포함하지 않는다. 이번 localhost5174 검증 서버는 종료했다.8080/55433은 시작 시 listen하지 않았고 이번에 DB/서버를 변경하지 않았다.

다음은 Android의 reader 저장 완료 뒤 currentDocument 재조회·분류 직접 GET·SAF 대기 ticket 검사다. 이어서 웹/Android 분류의 별도 명시적 채택을 새 mutation/expectedVersion으로 구현한다. 최신 S1/S2 ZIP, PDF 계정 UX/물리 위치·메모 API, EPUB 원본 bytes 교환과 V1~V5 미검증 범위는 유지한다.

[작업 큐](AGENT_WORK_QUEUE.md) · [남은 체크리스트](REMAINING_WORK_CHECKLIST.md) · [분류 계약](../contracts/library-organization-snapshot-v1.md)
