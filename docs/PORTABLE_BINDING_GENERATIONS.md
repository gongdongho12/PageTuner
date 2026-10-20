# Android 텍스트 문서 연결의 변경 세대

S4c3b 최신 분류 ZIP 내보내기를 위한 선행 수정이다. 분류 직접 GET·내보내기 화면·명시적 채택 자체는 아직 추가하지 않는다. PDF 연결은 별도 저장소와 계약을 유지한다.

## 보존하는 연결과 변경 감지

기기의 텍스트 연결에 영속 nonce를 추가한다. 같은 계정·로컬 사본·서버 UUID·전체 identity로 다시 저장해도 새 nonce를 발급한다. 해제는 파일을 지우는 대신 nonce가 있는 빈 연결 기록을 남긴다. 따라서 해제 후 같은 UUID로 재연결한 경우도 이전 요청과 구분한다.

기존 v1 파일은 현재 ZIP의 전체 텍스트 identity와 자산 없음 조건을 확인한 뒤 v2로 원자적으로 이전한다. 계정·사본·UUID·identity는 보존한다. 여러 저장소 인스턴스는 canonical 디렉터리별 잠금을 공유하므로 이전된 nonce가 재조회·재시작 때 바뀌지 않는다. 손상·미지원 파일은 strict 조회에서 실패하며 조용히 빈 연결로 덮지 않는다. 기존 nullable 조회도 이런 파일을 활성 연결로 인정하지 않는다.

연결 저장은 library 잠금 다음 binding 잠금 순서로 현재 canonical 문서와 관찰했던 연결 snapshot을 비교한다. 서버 확인을 기다리는 동안 문서나 연결이 바뀌면 저장하지 않는다. 계정·선택 세대 guard는 임시 파일 fsync 이후 원자 교체 직전에도 검사한다. 연결은 명시적인 사용자 선택이며 ZIP에 nonce·binding·계정·자격 증명을 넣지 않는다.

## 늦은 응답 처리

- 서버 읽기는 검증 전후와 읽기 화면 전달 시 현재 ZIP identity와 연결 nonce를 다시 확인한다.
- 이전 요청의 NotFound 또는 identity 불일치는 그 요청이 관찰한 연결만 조건부 해제한다. 새 연결은 보존한다.
- 기존 용어집 export·명시적 채택의 연결 비교에도 nonce가 포함된다. 같은 UUID 재연결을 서버 조회 후·채택 직전·SAF 대기 이후에 검출한다.
- 문서 본문과 별개인 제목·기기 읽기 위치 등의 변경만으로 원본 identity를 바꾸지 않는다.

## 검증 범위

2026-10-20 [draft PR #52](https://github.com/gongdongho12/PageTuner/pull/52)은 PR #51을 base로 한다. 저장소 `3f9ef99`와 소비자 `684e788`로 나눠 커밋·푸시했다. 관련 5개 클래스의 JVM 회귀 46개가 통과했고 독립 코드 검토를 완료했다. 기존 내부 `ExchangeJson`에 대한 앱 접근은 컴파일에서 발견해 수정했다. 엄격한 UTF-8 디코딩과 기존 `StrictExchangeJson` grammar normalizer를 사용하며 내부 문서 codec을 공개하지 않는다.

전체 Android 618개 중 602개 통과/16개 opt-in 제외, backup-runtime 50개 통과, APK·lint·계측 소스 컴파일·모듈 경계가 통과했다. 재현 명령은 `gradlew -PbuildTarget=all -Pkotlin.compiler.execution.strategy=in-process :backup-runtime:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin verifyModuleBoundaries`이다. 로그는 ignored `.gradle-home/binding-generations-focused.log`와 `binding-generations-full.log`에 있다.

이 수정의 저장소·비동기 회귀는 JVM 자동검사이며 물리 Android SAF·회전·프로세스 재생성 검사와 구분한다. 최종 PR head CI는 작업 큐에 기록한다. 최신 계정 분류 ZIP export·채택, S1/S2 최신 ZIP 통합과 전체 S4는 미완료다.

## 다음 웹 export의 선행 경계

웹 `portableBinding.ticket`은 raw binding nonce를 보존하지만 `open`이 반환한 UUID만 비교하면 같은 UUID 재연결을 놓친다. `open`의 늦은 오류 처리도 현재 raw 값과 비교한 뒤 해제하도록 보완해야 한다. 새 export는 읽기 전용 captured binding과 최종 ticket 검사를 사용한다. 기존 `exchangeExportChoices`의 목록 사본과 `exportDocument(saved)`를 최신 canonical 문서로 간주하지 않는다. 직접 organization GET 세션부터 구현하고 pending·queued·conflict 및 version 역행/동일 version의 내용 변경을 거절한다.

[작업 큐](AGENT_WORK_QUEUE.md) · [남은 체크리스트](REMAINING_WORK_CHECKLIST.md) · [분류 snapshot 계약](PORTABLE_ORGANIZATION_SNAPSHOT.md)
