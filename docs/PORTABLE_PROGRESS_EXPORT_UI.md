# 최신 계정 읽던 위치의 웹 ZIP 선택

2026-10-30 [draft PR #67](https://github.com/gongdongho12/PageTuner/pull/67), base `codex/canonical-progress-export`(#66), head `codex/progress-export-screen`다. committed issuer `6f6def3`·화면/번역 `de056cd`와 문서를 분리했다. 웹 내보내기 설정에 **최신 계정 읽던 위치 포함**을 기본 해제로 추가했다. 최신 용어집·분류와 함께 선택할 수 있으며 기존 기기 위치/notes를 바꾸거나 계정 위치를 자동 채택하지 않는다.

## 발급과 최종 저장 검사

`useFreshReadingProgress(namespace)`는 현재 namespace의 registry와 committed `useExportSession` lease를 고정한다. 이전 begin은 나중 registry/ref/lease를 조회해 새 권한을 빌리지 못한다. cleanup/StrictMode 재설정·계정/client/origin 만료 뒤 이전 callback/reader를 거절한다. `ProgressRegistry.freshReader`만 호출하며 정상 controller/open/start/move/refresh/flush/drain을 내보내기에서 열지 않는다.

Workspace는 fresh progress session을 계정 session memo에, includeProgress를 exportOwner에 포함한다. 선택/옵션/언어/패널 변경·취소·계정/client 교체·unmount는 이전 작업을 만료한다. 선택했을 때만 reader를 발급하고, 오프라인의 이미 선택한 옵션은 해제할 수 있다. 준비는 [PR #66 composer](CANONICAL_PROGRESS_EXPORT.md)를 그대로 사용한다. 모든 journal await 뒤 canonical 전체 문서/실제 자산/기록을 다시 읽고 raw nonce/session을 검사하며 저장 직전 checkBeforeSave→동기 assertCurrent→link.click 사이 await가 없다.

전체 text identity·absent/null·정확한 paragraphId/UTF-16 anchor, 다른 언어 용어집/500항목 모든 필드·분류 Unicode/ordered tags·legacy/sibling과 기존 한도를 보존한다. unsupported/손상/불일치 확장이나 초과를 덮어쓰거나 자르지 않는다. PDF UUID를 text S1로 사용하지 않는다. 파일 import/export는 자동 계정 변경 권한이 아니다. 전체 저장소 transaction·동일 bytes/private 시각 재삽입 영속 세대와 관찰되지 않은 서버 변경의 동시점 보장은 없다.

## 검증

- 전체 웹 **915개/86파일**, 생성 계약15·typecheck·production·공유 웹 build, APK/모듈 경계 **50초** 성공(60 task 중54개 재사용). 이번 새 Android 단위/계측/실기기 검증은 아니다. 독립 검토에서 blocking finding이 없었다.
- 실제 Chrome의 StrictMode/production 컴포넌트와 실제 IndexedDB importPackage/binding.confirm을 사용했다. metadata/identity API는 **주입 모의 함수**다. 계정 ABA·client 교체·remount/root unmount의 이전 begin/reader·합성 origin lease 등 **12개 거절**, API0회를 확인했다. 합성 origin은 실제 다른 origin 로그인 이동이 아니다.
- 실제 UI의 held progress GET 후 A→B→A와 취소→책 선택/옵션 변경→늦은 응답 해제에서 link click0·PUT0을 확인했다. 오프라인에서 선택 해제 후 다시 선택은 disabled임을 확인했다. 정상 provider autodrain의 독립 PUT과 새 reader 유발 PUT은 구분한다.
- 세 옵션 동시 선택→verify1/각 metadata GET1/PUT0→준비 Blob **6,138bytes**를 production readExchange로 읽었다. 원래 전체 문서/문단/identity·legacy position0/notes·분류/ordered Unicode tags·서버 anchor p:1/UTF-16offset18·500개 용어집 전체 필드·ja scope/unknown sibling 동등성을 독립 Python으로 비교했다. **브라우저가 해석한 준비 Blob JSON 비교이며 OS ZIP 파일을 독립 재개봉한 근거는 아니다.**
- Chrome의 viewport override 요청은 실제3278×1606에 반영되지 않았다. 같은 Chrome 안에서 실제 문서를 **고정 크기 same-origin iframe390×844/844×390**으로 렌더해 ko/en 모든 옵션/설명 페이지의 overflow 없음·행 내용 경계·버튼/checkbox label44px 이상을 확인했다. native top-level viewport resize 검증으로 표시하지 않는다. 한국어/영어 세로 스크린샷을 보존했으며 영어 가로 screenshot API는 timeout이었다.
- 마지막 추가 readback의 Python 기본 cp949 오류는 UTF-8 지정으로 수정했다. 처음 push의 GitHub Internal Server Error는 동일 per-command credential helper 재시도로 성공했다. 전역 자격 증명 설정이나 토큰 출력은 없었다.

## 다운로드 확인 대기

준비 뒤 원래 native anchor click을 **1회 호출**했고 UI는 다운로드 요청 안내를 표시했다. 그러나 download 이벤트 대기가20초 timeout됐고 확인한 Downloads 폴더에는 이번 새 파일이 없었다. 다운로드 관리 화면 `chrome://downloads` 접근은 browser URL policy로 거절됐다. 다른 브라우저/표면/CDP/우회 방식으로 검사하지 않았다. 실제 OS 저장 완료/독립 ZIP readback은 **미검증**이며 사용자에게 파일 저장 여부/경로를 한 번 요청했다. 이전 PR #55의 OS ZIP5,899bytes 증거를 이번 S1 파일 저장 증거로 재사용하지 않는다.

ignored `.gradle-home/progress-export-screen-*`에 web/sharing/APK·lifecycle·held ABA·준비/expected/readback JSON/Python·layout/portrait screenshot을 보존한다. 취소/선택 변경 결과는 도구 history에도 있다. harness는 ignored `web/.gradle-home/progress-export-qa.*`와 `progress-viewport-qa.html`이며 배포에 넣지 않는다. 이번5174/QA 탭은 정리됐고8080/55433은 시작 시 listen하지 않아 서버/DB를 변경하지 않았다. 원본 PR34·기존 자료/자격 증명을 보존했다.

문서 포함 정확한 최종 head CI는 PR 본문/ignored `progress-export-screen-final-ci.json`에서 대조한다. PR #66 `5aa32094be9af40cf9741bc973e8b903ce2ab3c7`/run40685540340 SUCCESS3분52초를 재확인했다. 체인 base를 유지하고 자동 병합하지 않는다.

다음 독립 단위는 Android 최신 S1 직접 GET/ZIP/SAF ticket이며 기존 분류 export의 currentDocument/전체 proof/raw nonce/session 경로를 재사용한다. OS 파일 경로가 확보되면 이번 ZIP의 독립 파일 검증을 마친다. 별도 S1 채택은 nullable journal/CAS·정상 actor cached write 보호가 먼저이며 S2 계약/삭제 tombstone/feed 완결성·PDF 계정/EPUB 원본 교환도 남는다.
