# 웹 ZIP 분류의 별도 명시적 채택

2026-10-24, draft PR #58. Base `codex/organization-adoption-cas`(PR #57), head `codex/organization-adoption-screen`. 세션/adapter `5927f06`, 화면 `68856ea`를 분리했다. 웹 분류 비교와 명시적 채택을 연결했으며 Android 채택·전체 S4는 미완료다.

## 사용 경로

ZIP 교환 → 가져온 책 → **계정 정보 채택** → **ZIP 분류 채택** → 원본 확인 후 비교 → 채택 확인 → **ZIP 분류 채택 확정**. 기존 용어집 채택은 같은 선택 화면에서 별도로 열 수 있다. 책 목록의 버튼 수는 늘리지 않았다.

비교 화면은 ZIP/조회한 서버/기기 계정 journal을 나눠 보여 준다. 계정·서버·문서 종류/UUID, 등록 여부, 폴더·태그 순서·즐겨찾기를 페이지별 읽기 전용 필드에서 확인한다. 긴 값은 코드 포인트 단위로 나눠 모든 내용을 표시한다. `absent`는 정보만 표시하고 확정 버튼이 없다. `present-empty`는 등록되지 않은 version 0에도 별도 선택 후 새 빈 분류 PUT 의도를 만든다. 완료 화면은 **기기에 저장한 의도**이며 서버 ACK를 의미하지 않는다. 진행 중에도 취소·닫기가 가능하다.

## 저장과 권한

- `OrganizationRegistry.adoptionSession`과 `usePortableOrganizationAdoption`은 controller를 시작하지 않는 직접 GET 비교 세션을 발급한다. committed terminal lease가 계정/origin/client와 호출자 선택 세대에 묶이며 unmount·A→B→A·이전 callback은 새 세션을 발급할 수 없다.
- `createPortableOrganizationAdoption.prepare(copyId)`는 inventory 사본 대신 현재 `get/exportDocument/get`으로 문서·실제 자산 bytes·독서 기록·private 저장 시각을 캡처한다. 전체 text identity/proof와 typed snapshot을 확인하고 readonly `bindings.capture`로 검증한 raw nonce 세대를 유지한다. 실패해도 기존 binding을 지우지 않는다.
- 비교는 nullable journal 전후를 검사하며 pending/queued/conflict를 보존한다. 확인은 single-use이고 표시 사본을 바꿔도 비공개 명령은 바뀌지 않는다. 확인 시 canonical/전체 proof·binding을 다시 읽으며 actor GET 전후와 nullable journal transaction 직전에 비동기 canonical 검사를 수행한다. 마지막 취소 가능한 IDB callback까지 계정/선택/신호/raw nonce와 관찰 version을 동기 검사한다.
- PR #57 `adoptSnapshot`으로 새 mutationId/expectedVersion을 저장한다. standalone actor는 PUT을 보낼 수 없으며 성공 뒤 actor/store를 닫고 계정 registry의 정상 outbox에 인계한다. 거절된 비교가 다른 pending을 전송하지 않는다. durable commit 이후 계정 전환/close는 오래된 UI만 폐기하며 이미 저장한 의도를 실패/rollback으로 표시하지 않는다.
- ZIP 자체·legacy 분류·다른 언어 용어집·unknown sibling은 수정하지 않는다. 500항목을 workflow의 200항목으로 줄이지 않는다. binding·계정/origin·자격 증명·CAS/outbox를 ZIP에 추가하지 않는다. PDF UUID로 text S1~S3 권한을 열지 않는다.

라이브러리·binding·journal·서버 전체의 원자 transaction은 없다. canonical 재조회 후 다른 저장소가 바뀌는 짧은 경계와, 동일 bytes/동일 private 시각으로 삭제·재삽입한 경우를 영속 세대로 검출하는 기능은 제공하지 않는다. 서버 GET과 후속 PUT 사이 변경은 기존 CAS 충돌로 처리한다. 한도·미지원 snapshot/full-proof 거절은 기존 계약을 따른다.

## 실행 근거

- 로컬 웹 **808개/83파일**, 생성 계약15개·typecheck/production build·공유 웹/APK/모듈 경계 성공. 새 세션/adapter/필드 회귀는 실제 IndexedDB가 아닌 fake IndexedDB·주입 API 검사다. 500개 모든 용어집 필드와 다른 언어·legacy/sibling·Unicode/NFC/NFD/공백·빈 값·nullable journal·nonce ABA·지연 응답·계정/선택 취소·canonical 삭제/메타 변경을 검사했다. 독립 검토에서 겹치는 GET 뒤 높은 관찰값을 마지막 journal 조회가 낮추는 경계를 보완했다.
- 실제 Chrome/React StrictMode와 production 컴포넌트/진짜 IndexedDB에서 비교 PUT0·journal 없음 → 별도 확정 PUT1·새 UUID/expectedVersion0 → ACK 후 v1, absent 확정 버튼 없음/PUT0, present-empty 새 PUT1을 확인했다. fixture는 production `importPackage`로 준비했고 파일 선택 UI import는 새로 실행하지 않았다. **계정 API와 원본 verify는 모의 함수**이므로 실제 PostgreSQL/HTTP UI 종단 근거가 아니다.
- held GET 도중 A→B→A 전환 후 이전 응답/발급 callback 거절·PUT0, hook unmount와 client 교체 후 이전 begin 거절을 확인했다. origin은 lease 코드/기존 단위 검사 경계이며 새 타 origin 로그인 이동 검증은 아니다.
- ko/en **390×844·844×390**에서 전체 높이 패널·버튼/필드 경계·44px 이상 버튼을 검사했다. 가로 화면의 모든 15개 분할 필드에서 원래 공백과 NFC/NFD 태그 순서를 읽고 최종 확인 진입 시 첫 페이지 복귀를 확인했다. 실제 보관함의 4버튼 행→용어집/분류 선택→분류 패널 진입을 확인했다.
- ignored 근거: `organization-adoption-screen-web.log`, `organization-adoption-screen-package.log`, `organization-adoption-screen-browser.json`, `organization-adoption-screen-ko.png`, `organization-adoption-screen-en.png`. 브라우저 harness는 managed worktree의 `web/.gradle-home/organization-adoption.*`이며 production build 입력에 포함하지 않는다.
- 서버/DB/Android production 코드는 변경하지 않았고 QA/preview DB·원본 PR34 변경은 보존했다. 새 서버 UI/실기기 SAF/회전/재생성 검증과 구분한다. 선행 PR #57 최종 `4cf7d99`/run40083779603 SUCCESS3분23초를 재확인했다. 정확한 최종 문서 SHA CI는 PR 본문과 ignored `organization-adoption-screen-final-ci.json`에서 대조한다.

다음은 Android의 nullable journal 비교와 actor 최종 guard·새 CAS 의도를 사용하는 별도 명시적 채택이다. 기존 open/connect/retry·update/choose를 비교/채택에 재사용하지 않는다. 이후 실제 서버 UI 종단과 S1/S2 최신 ZIP·PDF 계정 UX/API·EPUB 원본 교환을 이어간다.
