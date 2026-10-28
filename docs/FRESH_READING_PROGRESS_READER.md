# 읽던 위치의 GET-only 최신 조회 기반

후속 2026-10-28 [PR #66 canonical composer](CANONICAL_PROGRESS_EXPORT.md)에 fullproof/raw nonce·기존 용어집/분류와 누적 ZIP bytes·최종 canonical guard를 연결했다. 아래는 PR #65 당시의 GET-only 단위 범위다. 안전한 committed UI issuer/다운로드·Android·채택은 계속 후속이다.

2026-10-27, [draft PR #65](https://github.com/gongdongho12/PageTuner/pull/65), base `codex/reading-progress-snapshot`(#64), head `codex/fresh-reading-progress-reader`다. 응답 검증 `703c3c7`·GET-only 세션/회귀 `ee4b4ae`와 문서를 나눴다. 웹 `ProgressRegistry.freshReader(current)`와 `FreshReadingProgressReader`는 직접 `api.get`으로 계정 위치를 읽고 기기 journal을 읽기 전용으로 검사한다. 정상 reader의 `open/start/move/refresh/flush/drain`을 호출하지 않는다. `Device` pending이나 fallback 위치를 서버 최신 값으로 쓰지 않는다.

## 조회와 최종 검사

- 조회 전후와 `checkReady`에서 pending/queued/conflict를 거절한다. `checkReady`는 해당 scope의 성공한 GET 뒤에만 사용할 수 있으며, journal만 보고 관찰되지 않은 서버 값을 승인하지 않는다.
- 성공한 GET과 journal의 최고 관찰값을 메모리에 따로 보존한다. GET 실패나 journal 삭제 뒤에도 관찰을 낮추지 않는다. 낮은 version 또는 같은 version의 다른 anchor/updatedAt는 응답 불일치다. 겹친 GET 및 마지막 journal await 이후에도 관찰을 다시 비교한다.
- 현재 계정/선택 권한이 false 또는 예외, registry 종료, signal 취소를 관찰하면 세션은 영구 만료한다. 잘못된 응답도 해당 reader를 폐기한다. 새 registry/reader의 명시적 조회로만 재시도한다. 늦은 성공/오류는 이전 권한으로 돌려주지 않는다.
- 입력 scope·GET 응답·관찰값은 독립 사본이다. kind/UUID와 응답 전체 필드/anchor 필드, 정수 version/offset, Unicode와 zero/null을 검사한다. production HTTP client가 unknown 필드를 버리기 전에 거절하도록 공통 응답 검증도 보완했다. HTTP JSON의 중복 키 거절을 추가한 것은 아니다.

기존 progress journal 키는 UUID 대소문자를 구분한다. 새 읽기 전용 `snapshotAliases`는 같은 username index를 순회해 kind와 UUID의 대소문자 무시 일치 행을 모두 확인한다. 원래 key/행을 migration·수정·삭제하지 않는다. 별칭의 pending이나 더 높은 remote 관찰을 lowercase lookup으로 건너뛰지 않는다. 네트워크에는 캡처한 lowercase UUID를 보내며 원래 text 문단 ID·대소문자·공백·Unicode와 offset은 바꾸지 않는다. 비용은 이 계정의 journal 행 수에 비례하며 새 canonical index migration은 없다. 첫 IndexedDB open의 기존 schema 생성은 일어날 수 있으므로 DB schema 쓰기까지 전혀 없다는 주장이 아니라 **journal 행/계정 mutation 쓰기 없음**을 뜻한다.

## 권한과 한계

`current`는 소유자가 닫는 terminal 세대 권한을 제공해야 한다. 이 기반은 account/origin/client/선택의 실제 React lifecycle을 자동 관찰하지 않으며, 단순한 현재 값 equality만으로 관찰되지 않은 A→B→A를 검출하지 못한다. 안전한 committed UI 발급 hook은 후속이다.

아직 ZIP bytes 준비, canonical 문서/full text proof, raw binding nonce, 다운로드/UI, Android 연결이나 명시적 채택을 제공하지 않는다. 따라서 임의 UUID 조회에 성공한 것을 ZIP 채택/내보내기 권한으로 표시하면 안 된다. 최종 통합에서는 기존 canonical capture와 text binding 검증·메모리 lease를 결합하고 journal await 뒤 문서/nonce를 재검사해야 한다. 계정/library/binding/journal 전체 transaction과 동일 bytes 재삽입 영속 세대는 없다.

정상 provider의 autodrain은 별도로 기존 pending을 전송할 수 있다. 이 기반의 GET-only와 전체 앱 네트워크 PUT0 주장은 다르다. 채택을 추가하기 전 nullable journal/CAS와 정상 actor의 cached write 보호를 별도 구현한다. 기존 move/choose로 새 채택 의도를 대신하지 않는다.

## 검증

- 신규 fresh reader **44개**와 production HTTP adapter의 unknown root/anchor·Unicode·잘못된 streamed UTF-8 회귀 **3개**를 추가했다. 기존 정상 sync25개도 통과했다.
- 전체 웹 **875개/85파일**, API 생성물 확인·typecheck·production·공유 웹 build 성공. APK·모듈 경계42초 성공(task 재사용 포함). 이번 Android 단위/계측 실행을 새로 하지 않았으며 정확한 원격 CI 범위와 구분한다.
- fakeIndexedDB의 실제 readonly transaction/원래 행 보존, 주입된 GET/PUT transport 및 지연 facade로 pre/post/final pending/queued/conflict·GET실패/행삭제 highwater·UUID 별칭·scope 격리·겹친 GET/최종 await 역전·동일 version 내용변경·입력/응답 alias·abort/종료/만료를 검사했다. 수동 authority guard의 A→B→A 테스트는 실제 React 계정/선택 수명 발급 근거가 아니다.
- 독립 검토에서 미조회 scope의 checkReady를 거절하고 pending 거절 전 remote 관찰을 보존하도록 보완했다. 새 최종 검사 계약 이전의 fixture3개를 수정했으며 최종 집중 로그 `fresh-reading-progress-focused.log`와 전체 `fresh-reading-progress-web.log`, 공유 build·APK 로그는 ignored `.gradle-home`에 있다.
- 새 브라우저/실제 HTTP 소켓/PostgreSQL/Android 실기기 검증이 아니며 이번 서버/DB/브라우저를 시작하거나 기존 자료/원본 PR34·자격 증명을 변경하지 않았다. PR #64 최종 fa9b376/run40329852986 SUCCESS3분54초도 재확인했다.

문서 포함 정확한 최종 head CI는 PR 본문/ignored `fresh-reading-progress-final-ci.json`에서 대조하고 자동 병합하지 않는다. 다음은 canonical ZIP composer→안전한 UI issuer/다운로드→Android 직접 GET 경로이며 별도 채택의 nullable journal/CAS·정상 actor 보완과 S2도 미완료다.
