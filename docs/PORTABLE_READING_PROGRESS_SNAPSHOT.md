# 읽던 위치의 passive ZIP snapshot

후속 PR #65의 직접 GET reader와 [PR #66 canonical ZIP composer](CANONICAL_PROGRESS_EXPORT.md)가 완료됐다. 아래는 PR #64 당시 passive 계약 범위이며, 실제 committed UI issuer/다운로드·Android·명시적 채택은 계속 후속이다.

2026-10-26, [draft PR #64](https://github.com/gongdongho12/PageTuner/pull/64), base `codex/android-organization-adoption`(#63), head `codex/reading-progress-snapshot`의 S1 계약·codec 단위다. 계약/모델 `0427cfb`·JVM `94eb0ee`·웹 `c4403a2`·별도 PDF 렌더 수정 `2a60817`로 나눠 커밋했다. 정확한 문서 포함 최종 head CI는 PR 본문/ignored `reading-progress-snapshot-final-ci.json`에서 대조하고 자동 병합하지 않는다.

`extensions.readingProgressSnapshot`에 전체 text DocumentIdentity와 absent/null 또는 present/정확한 문단 ID·UTF-16 offset을 보존한다. 확장 없음은 관찰값 없음이며 absent는 계정 위치 미등록이다. deleted/reset을 만들지 않는다. 기존 `position`·notes·분류·용어집과 형제 확장은 그대로 유지한다.

문단 끝과 빈 문단의 offset0은 유효하다. 실제 문단에 없는 ID, surrogate pair 중간 offset, 200 UTF-16 단위를 넘는 계정 문단 ID는 거절한다. ID를 trim·정규화·절단하거나 화면 페이지/가까운 문단으로 치환하지 않는다. typed 문서 읽기/교체는 전체 본문 proof·sibling identity·기존 own snapshot을 검증하며 자산 PDF/EPUB를 text identity로 열지 않는다. generic ZIP의 안전한 미래 확장 보존과 typed 거절을 구분한다.

extensions 전체256KiB·문서8MiB와 기존 ZIP/JSON 한도를 입력/출력에 적용하고 초과 시 다른 metadata를 버려 맞추지 않는다. 반환값은 입력 목록/객체를 공유하지 않는다. 계정·origin·binding·서버 version·mutation/outbox는 넣지 않는다. 내용 proof는 계정 소유권이나 최신성의 증거가 아니다.

## 검증

- core-backup **51개**, backup-runtime **61개** 통과(신규 모델6·JVM11). 웹 **828개/84파일** 통과(신규20), typecheck·production·공유 웹 build 성공.
- Android **742개 중726개 통과/16 opt-in 제외**, APK·lint·계측 소스 컴파일·모듈 경계 성공. 최종 앱 실행3분03초, task 재사용 포함. 최초 실행의 존재하지 않는 `:shared:compileKotlinJs` task를 제거하고 실제 공유 웹 `build:sharing`을 별도 실행했다. 초기 로그도 보존했다.
- 공유 **8사례**의 production 웹 ZIP **10,624bytes** → JVM typed 읽기/proof/재부착·ZIP 쓰기 **10,807bytes** → 웹 typed 읽기/proof. 전체 문서·metadata·위치/notes·legacy 분류·용어집/분류 snapshot·unknown sibling 동등성 및 계정 API0회를 확인했다. 압축 ZIP bytes 자체의 동일성을 주장하지 않는다.
- original/translation·언어 대소문자·sibling 없음·absent·offset0/끝/빈 문단·공백/Unicode ID·잘못된 primitive/중복 키/미지원 버전/본문 변조·surrogate 경계·입력 alias·정확256KiB/8MiB와 +1byte를 검사했다. 큰 기존 snapshot을 작게 교체해 입력 초과를 숨기는 것도 거절한다. 독립 검토 완료.
- ignored `.gradle-home/reading-progress-snapshot-*`의 Kotlin/웹/Android/공유 build·counts·교차 스크립트/ZIP/JSON에 근거를 남겼다. 교차 harness의 초기 경로/classpath 오류를 수정한 최종 결과이며 JVM runtime 교차검사는 현재 원격 CI 범위와 구분한다.

최신 계정 직접 GET export·별도 채택 adapter/UI는 **미구현**이다. 파일 import/export가 계정 위치를 자동 변경하지 않는다. 다음은 controller를 열지 않는 최신 GET과 canonical/raw nonce/terminal 세대 guard, 그 뒤 nullable journal/정상 actor CAS 조정과 별도 채택이다. S2 메모·북마크·강조/삭제 정보도 후속이다. 실제 서버의 분류/PDF UI 확인은 [별도 기록](LIVE_ORGANIZATION_PDF_VERIFICATION.md)이며 이 S1 기능의 실서비스/물리 Android 증거가 아니다.

[계약](../contracts/reading-progress-snapshot-v1.md) · [공유 fixture](../contracts/fixtures/reading-progress-snapshot-v1.json)
