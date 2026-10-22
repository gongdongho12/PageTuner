# Android 최신 계정 분류·용어집 ZIP 내보내기

[draft PR #56](https://github.com/gongdongho12/PageTuner/pull/56)은 PR #55를 base로 한다. journal 잠금 `f86445e`, 직접 조회/ZIP 준비 `1b4ef33`, 화면/SAF 연결 `786b0ba`를 나눠 커밋·푸시했다. 명시적 계정 채택과 전체 S4c3b/S4는 미완료다.

## 사용 범위

가져온 텍스트 ZIP 책의 **서버 원본 확인** 화면에서 정확한 계정 원본과 명시적으로 연결한 뒤 **최신 계정 용어집 포함**, **최신 계정 폴더·태그·즐겨찾기 포함**을 각각 선택한다. 용어집은 지정한 언어만 새로 읽고 분류는 연결된 원문/번역본의 정확한 UUID와 kind로 직접 조회한다. 두 옵션을 함께 선택해도 다른 용어집 언어 scope·legacy 분류/용어집·알 수 없는 sibling 확장을 유지한다. native PDF나 EPUB 원본 계정 업로드를 새로 연 기능은 아니다.

`currentDocument`는 대기 중인 reader 저장을 기다린 뒤 현재 저장소 ZIP을 다시 읽는다. `PortableAccountOrganizationExport`는 전체 문서와 binding nonce를 캡처하고 `verifyPortableIdentity` 및 `HttpTranslationStore.libraryOrganization`만 호출한다. sync controller·open/connect/retry·PUT·journal 쓰기는 호출하지 않고 `DeviceLibraryOrganization.local`도 사용하지 않는다. 분류 GET과 기존 용어집 query의 결과는 엄격하게 검증하고 사본으로 보존한다.

전체 text DocumentIdentity·언어 대소문자·문단 ID/순서/본문, 폴더·ordered tags·favorite·Unicode/NFC/NFD/중간 공백, 용어집500항목 전체 필드를 유지한다. 분류 absent/null과 present-empty를 구분하고 deleted를 만들지 않는다. 기존 미지원/불일치 snapshot·extensions 전체256KiB·문서8MiB·기존 ZIP 한도 초과는 자르거나 덮지 않고 거절한다. 계정/origin·binding nonce·자격 증명·CAS/outbox mutation을 ZIP에 넣지 않는다. 가져오기는 별도 계정 채택 권한이 아니다.

## 파일 저장 대기와 늦은 응답

패널은 확정된 선택마다 새 `PortableAccountExportLease`를 만들고 DisposableEffect에서 활성화한다. 옵션/언어 변경·닫힘·계정/선택 전환은 이전 lease를 영구 종료하며, 오래된 callback은 새 선택의 grant를 발급할 수 없다. MainActivity도 렌더 당시 verification session/entry를 고정해 다른 책의 최신 선택을 대신 사용하지 않는다. ViewModel의 계정 metadata export에는 선택 guard가 필수다.

메모리 ticket은 하나의 request ID와 고정 bytes, 계정/client, UI 선택 guard를 보존한다. GET 전후·ZIP 인코딩 후·SAF 발급 전과 실제 저장 직전에 현재 계정/연결 nonce/전체 canonical 문서·journal을 확인한다. `checkBeforeWrite`는 guard 전후에 live connection을 다시 조회하며 destination open 전후 모두 검사한다. 계정 A→B→A·client 교체·같은 UUID 해제/재연결·문서 변경/삭제·pending/queued/conflict·version 역행/동일 version 내용 변경은 거절한다. 요청 취소나 프로세스 재생성 뒤 새 bytes를 이전 SAF callback에 전달하지 않는다.

두 snapshot을 합칠 때 각 준비 단계의 source guard를 모두 유지한다. 용어집 기존 경로도 조회 전 journal 최고 관찰 값을 기억하고, journal I/O 뒤 canonical 문서/binding을 다시 읽는다. 삭제되거나 낮아진 journal로 이전 고버전 기록을 잊지 않는다. 출력 snapshot에는 서버 version/updatedAt을 넣지 않는다.

## journal 파일 보존

기존 Android AtomicFile 저장소는 인스턴스별로 별도 생성돼 export의 read/targets와 actor의 write가 겹칠 수 있었다. [AtomicFile의 외부 동기화 요구](https://developer.android.com/reference/android/util/AtomicFile#openRead())에 따라 분류·용어집의 read/write/targets가 canonical 디렉터리별 동일 JVM monitor를 공유하도록 보완했다. 활성 임시/backup 파일을 다른 인스턴스의 읽기가 복구하거나 지우는 경합을 막는다. 파일 형식·기존 pending/queued/conflict와 계정 격리는 바꾸지 않는다.

잠금은 같은 앱 프로세스의 해당 journal 디렉터리 범위다. library/binding/journal/SAF 전체가 하나의 원자 transaction은 아니며 저장 직전 재조회·비교를 제공한다. 동일 문서 bytes로 삭제 후 재삽입하는 변화의 영속 generation을 새로 추가한 단위도 아니다. 모든 최대 크기 데이터의 성능이나 물리 저장 장치 동작을 검증했다고 표시하지 않는다.

## 2026-10-22 검증

- 대상6개 클래스38개 JVM 검사 통과. combined ZIP500항목 전체 필드, 다른 언어 scope·legacy/sibling, absent/present-empty, 번역 identity 언어 대소문자, Unicode 태그 순서·공백·반환 객체 alias, 미지원/크기 초과, GET/SAF 대기의 계정/nonce ABA·취소·삭제/본문 외 변경·journal 경합·최고 version 보존을 검사했다.
- 실제 production HTTP adapter에 주입한 transport의 요청은 identity verify POST와 분류 GET뿐이었다. 이는 실제 소켓/서버/DB 종단 검증이 아닌 JVM adapter 검사다. ZIP 쓰기/읽기와 OutputStream guard도 실제 OS SAF 실행과 구분한다.
- Android 전체641개 중625개 통과/16 opt-in 제외, backup-runtime50개 통과. APK·lint·계측 소스 컴파일·모듈 경계 성공. 웹/서버 production 소스는 변경하지 않았다. 최초 중단된 빌드는 재시작했고 새 test fixture의 타입 추론/문법 오류는 최종 검증 전에 수정했다.
- 실제 Android AtomicFile에 대해 duplicate store의 read/targets/write를 commit 도중 차단하는 계측 소스를 추가하고 컴파일했다. canonical alias 잠금/예외 뒤 해제의 JVM 검사는 실행했지만 AtomicFile 계측은 실기기에서 실행하지 않았다.
- `adb devices`에 연결된 장치가 없었다. 이번 조회로 시작된 adb daemon은 종료했다. Compose 화면 시각 검사, 실제 SAF·회전·프로세스 재생성은 V3다. 서버/DB·기존 자료·원본 미커밋 작업은 변경하지 않았다.

재현: `gradlew -PbuildTarget=all -Pkotlin.compiler.execution.strategy=in-process :backup-runtime:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin verifyModuleBoundaries`. ignored `.gradle-home/android-organization-export-focused-verified.log`, `android-organization-export-full.log`, `android-organization-export-counts.json`에 근거를 보존한다. 최종 문서 head CI는 PR과 자동 실행 기록에서 대조한다.

다음은 웹/Android 분류 snapshot의 **별도 명시적 채택**이다. absent는 정보만 표시하고 present-empty는 version0에도 명시적 빈 분류 PUT 의도가 필요하다. nullable journal 원자 비교·최신 서버·binding/session·actor 최종 guard 후 새 mutationId/expectedVersion을 만들며 기존 update/choose/open/connect/retry를 그대로 채택에 사용하지 않는다. 최신 S1/S2 ZIP·PDF 계정 UX/물리 위치·메모 API·EPUB 원본 bytes 교환과 V1~V5는 계속 미완료다.

[작업 큐](AGENT_WORK_QUEUE.md) · [남은 체크리스트](REMAINING_WORK_CHECKLIST.md) · [웹 export](PORTABLE_ACCOUNT_EXPORT_UI.md) · [snapshot 계약](../contracts/library-organization-snapshot-v1.md)
