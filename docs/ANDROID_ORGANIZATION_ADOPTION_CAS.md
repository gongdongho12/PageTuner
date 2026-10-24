# Android 분류 채택의 nullable journal·새 CAS 기반

2026-10-25, [draft PR #59](https://github.com/gongdongho12/PageTuner/pull/59). Base `codex/organization-adoption-screen`(PR #58), head `codex/android-organization-adoption-cas`. 저장소 `f617e46`·command `45e0259`를 분리했다. 이 단위는 **내부 저장소·직접 GET 비교/새 의도 command 기반**이다. Android ZIP 채택 adapter/화면을 활성화하지 않으며 전체 S4c3b/S4는 미완료다.

## 저장 경계

`ServerLibraryOrganizationStore.readNullable`는 파일 부재와 이미 저장된 빈 journal을 구분한다. 기존 `read`의 빈 값 반환은 유지한다. 기존 구현체가 새 API를 지원하지 않으면 예외로 거절하며 read/write 조합으로 원자성을 흉내 내지 않는다.

`compareAndWrite(target, expected, value, guard)`는 기존 canonical-directory `ServerJournalFileLocks` 안에서 nullable 전체 journal을 비교하고 저장한다. 다른 인스턴스의 read/write/targets 및 CAS와 같은 잠금을 사용한다. pending/queued/conflict·remote·retry 기한이 다르면 쓰지 않는다. 입력 목록은 외부 guard 호출 전에 codec으로 분리한다. AtomicFile의 backup 복구·기존 형식·64 KiB 한도를 유지하며 손상·초과 파일은 없는 값으로 간주하거나 덮어쓰지 않는다.

guard는 stage 후 flush·fd.sync를 거쳐 AtomicFile.finishWrite 직전에도 실행한다. 이 경계 전 실패는 AtomicFile.failWrite로 되돌린다. commit 후 계정 전환/취소를 새 실패로 만들 후속 guard는 없다. Android AtomicFile의 플랫폼 commit 구현과 같은 한계를 가지며 프로세스 간 잠금이나 모든 저장소의 transaction을 제공하지 않는다.

## 내부 채택 command

`ServerLibraryOrganizationAdoption`은 한 account/target/client에 묶인 직렬 command다. `compare`는 직접 GET과 nullable journal 읽기만 수행하며 행을 초기화하거나 정상 sync actor를 열거나 PUT하지 않는다. 표시 사본과 실제 command를 분리한다. 실제 HTTP adapter도 주입 모의 transport에서 GET만 호출하는 것을 검사한다.

`adopt`는 single-use 비교 ID와 명시적으로 선택한 non-null 분류 값을 받는다. canonical 검증 callback·동기 session guard·전체 nullable 기기 base·서버 직접 GET을 재검사하며 조회 값이 비교 화면과 다르면 다시 비교해야 한다. 같은 값과 명시적 빈 값도 새로운 UUID/expectedVersion 의도를 저장한다. retry 기한을 보존하고 pending/queued/conflict를 덮거나 재사용하지 않는다. version 상한에서는 저장하지 않는다. absent snapshot은 이후 adapter에서 정보만 표시해야 한다.

서버와 journal의 최고 관찰값을 따로 유지한다. journal v1이 그대로 있고 GET이 v2인 정상 경우를 허용하면서 실제 journal 역행과 최고값 소실 뒤 오래된 GET은 거절한다. 같은 version의 다른 내용/시각, 잘못된 scope와 production HTTP INVALID_RESPONSE는 command를 영구 폐기한다. 정상 네트워크 오류는 새 비교를 허용한다. close는 영구 종료다.

최종 guard는 journal 잠금 안에 있다. 여기서 앞 순서의 gate/account/library/binding 잠금을 다시 획득하면 안 된다. 실제 adapter는 gate→account→library→binding→journal 순서와 메모리 세대 검사를 설계해야 한다. 필수 callback이 있다는 사실만으로 실제 ZIP 권한이 연결됐다고 판단하지 않는다.

true는 **기기에 새 의도가 저장됨**이며 서버 ACK가 아니다. IO dispatcher 복귀 시 coroutine 취소가 발생하더라도 이미 commit한 의도를 false/rollback으로 표시하지 않는다. 이 command는 정상 outbox를 시작하지 않는다.

## 다음 연결 전에 필요한 조정

기존 `ServerLibraryOrganizationSync`는 메모리 journal 사본을 가지고 unconditional write한다. 열린 편집기의 오래된 GET/ACK가 독립 CAS로 저장한 의도를 나중에 덮을 수 있으므로 **정상 actor의 CAS/reload 또는 동등한 조정이 선행**돼야 한다. 현재 내부 command를 기존 actor와 함께 UI에 연결해서는 안 된다. 이 위험을 전역 원자성으로 해결했다고 표시하지 않는다.

이후 reader 저장 완료 뒤 currentDocument의 실제 ZIP/canonical 문서·full proof·raw binding nonce·account/origin/client/선택 세대를 검증하는 adapter와 별도 확인 UI를 연결한다. import/export는 파일 권한이며 자동 계정 변경 권한이 아니다. PDF UUID로 text S1~S3를 열지 않고 binding·계정/origin·자격 증명·CAS/outbox는 ZIP에 넣지 않는다. Unicode/NFC/NFD/공백·ordered tags·legacy/sibling 보존과 기존 extension/문서/ZIP 한도를 유지한다.

## 검증

새 JVM 대상 **2클래스25개**(store9·command16), 전체 앱 **666개 중650통과/16 opt-in 제외**, backup-runtime50개가 통과했다. APK·lint·계측 소스 컴파일·모듈 경계도 성공했다(전체 명령1분28초). IO를 주입한 실제 저장소 CAS/잠금 경로와 모의 HTTP transport 검사이며 Android AtomicFile 실행이나 실제 서버 요청으로 표시하지 않는다.

null/기존 빈 값·전체 journal/기한·두 인스턴스 한 승자·stage guard rollback·입력 목록 분리·손상/초과 보존, 동일 값/빈 값의 새 UUID·version·ordered Unicode tags, pending/queued/conflict 보존·지연 응답·close/취소·최고 관찰값·production INVALID_RESPONSE·commit 후 dispatcher 취소를 검사했다. 독립 검토에서 stale journal v1/새 GET v2를 잘못 거절하는 high-water 혼용, HTTP 예외의 폐기 누락, guard 도중 close 경계를 보완했다.

실제 AtomicFile의 nullable restart·backup 복구·stage rollback·동시 CAS·손상/초과 보존 **5개 계측 소스**를 추가했다. 소스 컴파일은 기기 실행과 구분한다. 이 단위에는 신규 UI·실제 서버/DB·물리 Android 실행 근거가 없다. 웹 production은 변경하지 않았으며 공유 웹 build와 원격 CI의 웹/shared proof를 별도 근거로 남긴다.

ignored 근거: `android-organization-adoption-focused.log`, `android-organization-adoption-full.log`, `android-organization-adoption-counts.json`. 정확한 최종 head CI는 PR 본문과 `android-organization-adoption-final-ci.json`에서 대조한다.

선행 PR #58 정확한 최종 `836b218cefe16364766ab067167d762abc40fb52`의 CI run40088553014 SUCCESS4분08초를 재확인했다. 원본 PR34 미커밋 변경과 기존 DB는 보존한다.
