# Android 읽던 위치 journal의 strict 읽기 전용 관찰

2026-11-02 [draft PR #69](https://github.com/gongdongho12/PageTuner/pull/69), base `codex/android-progress-journal-guards`(#68), head `codex/android-progress-observations`다. 구현/23개 회귀 `7b8d590`·계측 소스 `b63b671`와 문서를 분리했다. **기기 journal 관찰 기반만 완료**했다. scoped 최신 GET reader·ZIP/SAF adapter/UI·명시적 채택/CAS는 아직 없다.

## 새로운 관찰 API

`ServerReadingProgressStore.observeAliases(canonicalTarget)`는 부재를 기본값과 합치는 기존 read나 손상 행을 억제하는 pending scan과 별도다. 미지원 store와 inventory/raw-read 구현은 기본적으로 예외를 내며 기존 read/pending으로 fallback하지 않는다. 확인된 부재는 빈 목록, 실제 존재하는 빈 journal은 기본 DeviceReadingProgress를 가진 observation으로 구분한다.

`ReadingProgressJournalObservation(target, record)`는 같은 account/kind/UUID의 모든 대소문자 별칭을 포함한다. 원래 key의 UUID와 remote/conflict의 UUID 표기·전체 pending/queuedAnchor/conflict/retry 기한·Unicode anchor를 보존한 detached 값이다. 기기 pending/local을 최신 서버 값으로 표시하지 않는다. 요청은 기존 canonical lowercase UUID만 받으며, 과거 별칭은 완전한36자 UUID 모양과 case-equivalence를 검사한다. 짧은 UUID·다른 scope를 강제 변환해 허용하지 않는다.

원래 key의 SHA-256과 실제 logical filename을 대조하고 exact format1/root/nested 필드를 검증한다. 임시 JSON scope 복사만 canonical 값으로 바꿔 기존 validator를 재사용한 뒤 반환값에 원래 UUID 표기를 복구한다. **serialize/parse 복사나 normalize를 쓰지 않아** Double1.0 등을 Int1로 바꾸지 않는다. 파일을 lowercase/migration/삭제하지 않는다.

## inventory·원본 bytes 경계

한 canonical-directory monitor 안에서 inventory와 모든 source read/검증을 끝낸다. minSdk23에서 사용 가능한 API21 `Os.stat`의 ENOENT만 부재로 인정하고 EACCES/ENOTDIR·불명확한 listing/비디렉터리는 거절한다. `File.exists()==false`나 listFiles null을 곧바로 빈 목록으로 취급하지 않는다. 실제 stat/권한 경계의 기기 실행 증거는 아래와 구분한다.

base/.bak는 logical row 하나로 중복 제거하며 **목록에 있는 .bak를 우선한 정확한 physical source**를 직접 읽는다. backup 읽기 실패를 base 성공으로 바꾸지 않고 복구/rename/쓰기하지 않는다. 기존 read/pending의 AtomicFile 복구 동작은 그대로다. orphan `.new`·의심스러운 .json 이름/대소문자/후행 문자·미지원 `.json.*`·잘못된 path/디렉터리는 거절하고 정리하지 않는다. 일반 nonjournal 파일은 건드리지 않는다.

bounded read65,536bytes와 strict UTF-8 뒤 `StrictExchangeJson.validate(integerNumbersOnly=true, maxArrayEntries=0, maxValues=128)`를 **JSONObject 생성 전**에 적용한다. 중복/escaped-equivalent key·pending 뒤 pending:null·lenient 문법/후행 내용·깊이/값 한도·실수/exponent literal이 intent를 숨기거나 강제 변환되지 않게 한다. root/anchor/mutation/schema 검증도 이어서 수행한다. 정상 read/write/pending의 raw grammar나 기존 파일 format을 바꾼 단위는 아니다.

전체 logical journal inventory를 검증한 뒤 요청 scope만 반환한다. 손상/초과/불명확한 행을 제외하고 일부 결과를 돌려주지 않는다. **다른 계정/문서의 손상 행도 관찰 전체를 막을 수 있는 보수적인 가용성 한계**가 있다. scan 비용은 전용 directory 행 수에 비례하며 최대 크기/개수 성능 완료를 주장하지 않는다. 정상 pending scan의 억제 정책과 섞어 완전 관찰이라고 표시하면 안 된다.

잠금은 단일 앱 프로세스의 journal 범위다. 관찰 반환 뒤 정상 write를 막거나 계정/library/binding 전체를 transaction으로 묶지 않으며 외부 프로세스의 파일 교체·동일 bytes 재삽입 영속 세대도 제공하지 않는다. 여기서 검증한 anchor는 구조이며 실제 전체 text proof/membership·계정 binding 권한은 후속 adapter가 검사해야 한다.

## 검증

- 신규 JVM **23개**, 대상5클래스 **76개** 성공. 전체 앱 **786개 중770개 통과/16 opt-in 제외**, 실패/오류0. APK/lint/계측 소스 컴파일/모듈 경계 성공. 집중2분13초, 최종 전체2분14초(83 task 중64개 재사용). 마지막 .json.* 분류 보완과 추가 사례는 전체 검사에 포함됐다.
- 실제 JVM 파일과 production observer/공유 monitor에 inventory/raw IO를 주입했다. 별칭 pending/high remote/conflict/기한·부재/빈 행·다른 scope 격리·backup 우선순위/목록 순서 독립성·backup 읽기 오류 무fallback/파일 보존·잘못된 scope/hash/JSON/UTF-8/숫자형·중복키·미지원 inventory/행·무부분결과·detached 값·Thread.BLOCKED 제외와 예외 뒤 잠금 해제를 확인했다. 독립 검토 완료.
- 독립 검토로 backup 복구 fallback에 의존하지 않는 정확한 readonly source read와 Android/JVM JSON 차이를 막는 raw duplicate-key 검사를 보완했다. 초기 테스트 실패는 없었다. 기존 nullable Java interop 경고는 남지만 검사는 성공했다.
- 실제 Android stat/AtomicFile의 부재/빈 행·UUID 별칭 bytes 보존·backup 무복구 관찰/별도 legacy 복구·중복 pending 키 회귀 **4개 계측 소스는 컴파일만 했고 실행하지 않았다**. JVM 주입 IO와 이 source compilation을 Android 실제 기기/권한/SAF 실행으로 표시하지 않는다.
- 새 HTTP/서버/DB/브라우저/OS 파일 저장 검증이 아니며 정상 sync actor나 외부 API를 열지 않았다. 원본 PR34·기존 자료/자격 증명·서버/웹 production은 변경하지 않았다. ignored `.gradle-home/android-progress-observations-*`의 focused/full/counts와 최종 CI 결과에 근거를 남긴다.

문서 포함 정확한 최종 head CI는 PR 본문/ignored `android-progress-observations-final-ci.json`에서 대조한다. 선행 PR #68 `e7798c925f895be9c03b744bcf1cdeacafc5502d`/run41307822485 SUCCESS4분12초를 재확인했다. 체인 base를 보존하고 자동 병합하지 않는다.

다음은 이 관찰 API를 사용하는 scoped 직접 GET reader다. controller/open/connect/retry/move/choose 없이 captured account/client/target/terminal current를 검사하고 remote/journal 최고 관찰을 따로 유지한다. pending 거절/GET 실패/행삭제 뒤 관찰을 낮추지 않고 마지막 journal await 뒤 역행/동일 version anchor·updatedAt 변경·겹친 GET을 다시 검사한다. UUID만 case-equivalence로 비교하고 실제 anchor/시간/identity는 정규화하지 않는다. 그 뒤 canonical 전체 ZIP/proof/raw nonce·실제 account/선택 ticket·SAF와 별도 채택을 연결한다. PR #67 OS 파일 경로 질문은 여전히 대기이며 반복 요청하지 않는다.
