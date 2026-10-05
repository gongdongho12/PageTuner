# 문서 분류의 passive ZIP snapshot

S4c3a는 계정 분류를 손실 없이 담을 공통 계약과 Kotlin/JVM·웹 codec이다. ZIP 가져오기만으로 계정이나 기기 분류를 변경하지 않는다. 최신 계정 조회·내보내기 화면·명시적 채택은 후속 S4c3b이며, 이 단위가 전체 S4 완료를 뜻하지 않는다.

## 저장 범위

`extensions.libraryOrganizationSnapshot`은 완전한 `DocumentIdentity`, `absent` 또는 `present`, 폴더·순서 있는 태그·즐겨찾기를 담는다. 확장이 없는 파일, 계정에 값이 없는 `absent/null`, 명시적으로 비운 `present/{folder:"",tags:[],favorite:false}`를 구분한다. 분류 API에 없는 `deleted`를 만들지 않는다.

원래 provider/book/chapter·언어 대소문자·revision·본문 hash 및 번역 identity 필드를 보존한다. 태그는 대소문자·NFC/NFD·중간 공백·순서를 그대로 유지한다. 계정 분류의 폴더 200 UTF-16 단위·태그 32개/각 60 단위 제한을 넘으면 거절하며 정규화하거나 자르지 않는다. 더 넓은 기존 ZIP `organization` 값은 그대로 보존한다.

타입을 해석하는 document helper는 전체 문단 ID·순서·본문과 원문/번역 proof를 검사한다. sibling `documentIdentity`가 있으면 지원하는 정확한 동일 identity여야 한다. 없으면 embedded identity로 내용 일관성을 검사한다. 이는 계정 소유권·출처·최신 상태의 증거가 아니다. 특히 원문 revision은 본문 hash이므로 별도 계정 연결 없이 provider/book/chapter의 소유권까지 증명하지 못한다. 자산이 있는 PDF/EPUB를 text identity로 처리하지 않는다.

## 보존과 거절

- generic ZIP codec은 안전한 미지원 확장도 그대로 왕복한다. 명시적 typed 읽기/교체는 기존 snapshot이 미지원·손상·다른 내용이면 거절한다.
- 기존 용어집 snapshot·알 수 없는 sibling·legacy 분류를 유지한다. 전체 extensions 256 KiB와 기존 문서/ZIP 한도, metadata 깊이·Unicode·숫자·자격 증명 제한을 지킨다.
- strict JSON은 중복 키·잘못된 primitive·안전하지 않은 숫자·짝 없는 surrogate를 거절한다. 입력을 문자열이나 UTF-8로 바꾸면서 손상된 값을 정상 값으로 만들지 않는다.
- 계정 ID·origin·binding·CAS/outbox·대기/충돌·자격 증명을 snapshot에 넣지 않는다. 읽기/쓰기 helper는 네트워크나 계정 변경을 하지 않는다.

## 다음 연결 단위

S4c3b 최신 export는 controller를 시작하지 않는 직접 GET으로 조회하고 현재 계정/origin/client/세대·정확한 text binding을 재검증해야 한다. `DeviceLibraryOrganization.local`과 `LibraryOrganizationProvider`의 pending 값은 서버 최신 값이 아니다. 응답 이후와 ZIP 저장 직전에 같은 세대인지 검사한다.

채택은 별도 명시적 선택이다. 최신 서버와 기기 base, pending/queued/conflict를 재검증한 뒤 새 mutation을 만든다. `absent`를 삭제나 빈 분류로 자동 변환하지 않는다. 이 문서의 passive snapshot 자체는 채택 권한이 아니다.

[계약](../contracts/library-organization-snapshot-v1.md) · [공유 fixture](../contracts/fixtures/library-organization-snapshot-v1.json) · [작업 큐](AGENT_WORK_QUEUE.md)

## 후속 구현 시 재사용 경계

웹 `OrganizationRegistry.open`과 `useLibraryOrganization`는 controller를 시작해 기존 pending PUT을 보낼 수 있다. 최신 export에는 `BookGlossaryProvider.freshReader`처럼 controller를 열지 않는 직접 GET 세션을 만들어야 한다. export 목록의 문서 사본을 그대로 쓰지 않고 최종 작업 전 저장소 canonical 문서와 전체 proof를 다시 확인한다. ZIP text binding은 UUID 외에 raw nonce도 검사해 해제 후 같은 UUID 재연결을 검출한다.

기존 controller의 `update/choose`는 동일 값이면 반환하거나 기존 대기열을 재사용/삭제할 수 있으므로 새 명시적 채택의 저장 경로로 바로 쓰지 않는다. nullable 기기 상태의 원자적 비교·저장, 최신 서버 version/내용 및 binding/session 재확인 후 새 mutation을 생성한다. `present` 빈 값은 version 0 계정에도 실제 빈 분류 저장 의도가 필요하다. PDF 별도 binding에는 이 텍스트 경로를 적용하지 않는다.

## 2026-10-05 검증

[draft PR #50](https://github.com/gongdongho12/PageTuner/pull/50)은 PR #49를 base로 한다. 공통 `7ca17ed`·웹 `9411fe6`·runtime `8b0cbdf`로 나눠 커밋·푸시했다. 최종 문서 포함 head CI는 PR의 SHA와 대조하며 자동 병합하지 않는다.

- core-backup 45개(신규 모델 8개), backup-runtime 50개(신규 codec 14개), 모듈 경계 통과.
- 웹 660개/76파일·생성 계약 15개·typecheck·production build 통과.
- Android 601개 중 585개 통과/16 opt-in 제외, APK·lint·계측 소스 컴파일 성공. 물리 기기 실행 증거가 아니다.
- production 웹 codec으로 만든 ZIP 9,067 bytes를 JVM codec으로 읽고 증명·재출력한 ZIP 9,266 bytes를 웹에서 다시 읽었다. 공유 7사례 모두 전체 문서·identity·snapshot·legacy 분류·용어집 및 unknown sibling이 동등했다. ZIP 압축 bytes의 동등성이 아닌 해석한 전체 값의 동등성을 확인했다. 네트워크 API 요청 0회.
- absent/present-empty, 원문/번역, sibling identity 없음, 언어 대소문자, Unicode/NFC/NFD/공백/순서, 미지원·변조 snapshot의 typed 거절과 generic 보존, extensions 정확256 KiB/+1byte 및 문서 정확8MiB/+1byte를 검증했다. 검토에서 JSON 비문자열 강제변환·반환 목록 alias·문서 한도 검사 시점을 수정했다.

재현 명령은 `npm --prefix web run verify`, `gradlew -PbuildTarget=all -Pkotlin.compiler.execution.strategy=in-process :core-backup:test :backup-runtime:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin verifyModuleBoundaries`이다. 실제로는 core 검사 후 runtime/app 검사를 순차 실행해 Gradle 충돌을 피했다.

로그는 원본 저장소의 무시된 `.gradle-home/organization-snapshot-core.log`, `organization-snapshot-android.log`, `organization-snapshot-web.log`, `organization-snapshot-jvm.log`, `organization-snapshot-web-verify-organization-snapshot-jvm.zip.json`에 남겼다. 교차 실행 helper `organization-snapshot-cross-runtime.mjs`와 `OrganizationInterop.java`도 같은 임시 폴더에 있다. JVM runtime 검사와 실제 ZIP 교차 실행은 현재 PR CI에 없는 로컬 근거로 구분한다.

서버8080은 내려가 있어 계정 사용 흐름 재검증은 하지 않았다. 이전 PostgreSQL/QA 시작 거절과 PDF Blob 미리보기 정책 차단을 우회하지 않았다. 원본 미커밋 변경과 preview/DB는 보존했다. 신규 화면이 없는 계약·codec 단위이므로 화면·OS 다운로드·Android SAF가 검증됐다고 표시하지 않는다.
