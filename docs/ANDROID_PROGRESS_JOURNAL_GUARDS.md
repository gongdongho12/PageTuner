# Android S1 응답·journal 공유 잠금 선행 보완

2026-11-01 [draft PR #68](https://github.com/gongdongho12/PageTuner/pull/68), base `codex/progress-export-screen`(#67), head `codex/android-progress-journal-guards`다. 응답/anchor 검증 `8cf987c`·journal 제외/회귀 `8e9e08a`와 문서를 분리했다. **내부 선행 보완**이며 새 GET-only reader, canonical ZIP adapter/SAF 옵션이나 명시적 채택을 연결한 단위가 아니다.

## 정확한 응답과 원래 anchor

`ServerReadingProgressJson`은 projection 전에 response(kind/recordId/version/anchor/updatedAt), anchor(paragraphId/characterOffset), mutation(expectedVersion/mutationId/anchor)의 정확한 필드 집합을 검사한다. unknown/missing 필드를 버리고 계속 진행하지 않는다. 기존 version0/null·양수/present, 숫자 범위와 lowercase UUID 규격을 유지한다.

문단 ID는1~200 UTF-16 code units·ECMAScript trim 기준 nonblank·ISO control 없음·정상 surrogate pair를 요구한다. 공유 trim helper는 nonblank 판정에만 쓰며 원래 앞뒤/중간 공백·BOM·Unicode/NFC/NFD·대소문자를 변경하지 않는다. offset은 음수/실수/문자열 강제 변환 없이 기존 Int 범위를 검사한다. 실제 문단 membership/본문 길이/끝·빈0·surrogate offset 경계는 기존 `ServerReadingDocument.validate`와 후속 전체 문서 adapter의 책임이다. 임의 anchor가 구조 검사를 통과한 것을 ZIP/서버 원본 권한으로 표시하지 않는다.

production HTTP의 직접 GET·ACK·conflict current도 같은 decoder를 거친다. S2 notes의 start/end/anchor decoder도 이 공통 anchor 검사를 사용하므로 전체 앱 회귀를 실행했다. S2 wire 모양이나 삭제/feed 계약을 추가한 것은 아니다. HTTP JSON 중복 키 token 처리나 새로운 timestamp calendar 의미 검증을 도입한 단위도 아니다.

## 기존 AtomicFile 접근의 제외

`FileServerReadingProgressStore`의 존재 검사·read/backup recovery·write·pending scan을 `ServerJournalFileLocks.withDirectory`로 감싼다. 다른 store 객체와 `directory/.` alias도 같은 canonical directory monitor를 사용한다. 기존 public File(directory) 생성자/인터페이스·format1·hashed key·부재 기본 `DeviceReadingProgress()`·backup·pending/queuedAnchor/conflict/retry 기한을 보존한다. 내부 `ReadingProgressJournalIo` 주입은 JVM에서 실제 production 잠금 경계를 시험하기 위한 것이다.

읽기65,536bytes 한도를 유지하고 잘못된 UTF-8은 replacement 문자로 ID를 바꾸지 않고 거절한다. malformed/unsupported nested record와 uppercase UUID 과거 파일은 회귀에서 원래 bytes를 남기며 자동 정규화/migration/정리하지 않는다. 기존 write의 target/schema/size 정책이나 CAS를 새로 추가하지 않았다. 따라서 이 단위의65,536 한도는 **bounded read**이며 새로운 pre-write 크기 계약을 뜻하지 않는다.

읽기는 기존 `AtomicFile.openRead`의 backup 복구로 filesystem 변경을 할 수 있다. 새 getter가 완전 무쓰기라거나 backup 복구를 없앴다는 주장이 아니다. 잠금은 단일 앱 프로세스의 journal 범위이며 계정/library/binding/journal 전체 transaction이나 프로세스 간 잠금이 아니다. `pending()`은 기존처럼 손상 행을 억제하고 pending만 반환한다. 이를 전체 journal/UUID alias/highwater 관찰로 사용하면 안 된다.

## 검증

- 신규 **22개**(JSON9·HTTP5·store8), 대상4클래스 **53개** 통과. 전체 앱 **763개 중747개 통과/16 opt-in 제외**, 실패/오류0. APK·lint·계측 소스 컴파일·모듈 경계 통과. 집중2분25초, 전체1분51초(83 task 중69개 재사용).
- 실제 production store/공유 monitor와 주입된 file IO에서 writer 대기 중 다른 alias의 read/pending/write 및 존재/backup read 대기 중 write가 Thread.BLOCKED로 제외됨을 확인했다. 정상/예외 뒤 잠금 해제·부재 무행생성·전체 journal/옛 retry 필드 누락·backup source·손상/초과/invalid UTF-8/uppercase 파일 보존을 검사했다. 주입 IO가 .bak를 유지하는 회귀는 Android AtomicFile 실제 복구 실행 증거가 아니다.
- production HTTP adapter에 모의 transport를 주입해 직접 GET 회귀의 PUT0, unknown response/anchor·escaped surrogate/blank·ACK/conflict current 거절과 scope 검증 전 요청0을 확인했다. 실제 HTTP 소켓/서버/DB 근거가 아니다.
- 실제 AtomicFile의 read/pending/write 경합 계측 회귀1개를 추가했으나 **소스 컴파일만 했고 실행하지 않았다**. 새 Android 기기/Compose/SAF·회전·재생성·브라우저/OS 파일 저장 검증은 없다. 독립 검토 완료, 이번 초기 테스트 실패는 없었다.
- ignored `.gradle-home/android-progress-guards-focused.log`·focused-counts.json·full.log·counts.json에 실제 근거를 남겼다. 원본 PR34·기존 자료/자격 증명·서버/DB/웹 production은 변경하지 않았다. 기존 Kotlin nullable Java interop 경고는 별도 기존 코드의 경고이며 검사는 성공했다.

선행 PR #67의 이전9836ca7 push는 PR metadata/CI에 반영되지 않았으나, Android 선행 조사 문서를 보완한 정확한 최종 **9ea4d538f774cf2a5ef9321cbf5d38afdc7de5b5**의 [run41306933425](https://github.com/gongdongho12/PageTuner/actions/runs/41306933425)가 SUCCESS3분19초로 확인됐다. PR 본문/ignored `progress-export-screen-final-ci.json`을 갱신했다. 이번 문서 포함 최종 head CI는 PR #68 본문/ignored `android-progress-guards-final-ci.json`에서 대조하고 자동 병합하지 않는다.

## 다음 경계

새로운 strict readonly journal 관찰 설계와 scoped 직접 GET reader를 분리해 추가한다. raw store의 과거 uppercase UUID 파일을 자동 lowercase/migration/삭제하지 말고 같은 scope 별칭의 pending/highwater를 놓치지 않는 관찰 방식을 검토한다. 현재 read는 부재를 기본값과 합치고 pending scan은 불완전하므로 이를 최신 서버 값이나 전체 관찰이라고 표시하지 않는다. 실패/행삭제 뒤에도 remote/journal 최고 관찰을 따로 유지하고 GET 전후·최종 journal await 이후 역행/동일 version 내용 변경과 terminal account/client/선택을 검사해야 한다.

그 뒤 reader 저장을 기다린 currentDocument→전체 실제 ZIP/text proof/raw nonce→metadata 누적 composer/VM/SAF ticket을 연결한다. 정상 editor/open/connect/retry/move/choose는 export/비교에 재사용하지 않는다. 명시적 S1 채택은 별도 nullable durable CAS와 정상 sync actor의 cached unconditional write 보호가 먼저다. 전체 S1/S2/S4·PDF 계정/EPUB 원본 교환은 미완료다. PR #67의 OS ZIP 저장 확인 질문도 여전히 대기하며 반복 요청하거나 Blob 동등성을 실제 파일 저장으로 바꾸지 않는다.
