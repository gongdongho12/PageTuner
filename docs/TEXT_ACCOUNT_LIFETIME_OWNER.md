# Android text 계정 수명의 실제 소유자

2026-10-25, [draft PR #62](https://github.com/gongdongho12/PageTuner/pull/62). Base `codex/text-adoption-commit-guards`(PR #61), head `codex/text-account-lifetime-owner`. 구현·회귀 `bcfd0cf`와 문서를 분리했다. `ServerLibraryViewModel`이 검증된 text connection과 종료 가능한 수명을 함께 소유한다. canonical 분류 채택 adapter와 Compose 선택/확인 UI는 아직 연결하지 않는다.

## 발급과 종료

`PortableTextAccountAuthority`는 immutable `ServerReadingConnection`과 private `PortableTextAccountLifetime`을 묶는다. connect의 profile/list 검증 또는 register 성공 뒤 실제 client·응답 username·검증에 사용한 endpoint로 발급한다. `readingConnection()`과 내부 `textAccountAuthority()`는 저장한 객체만 조회한다. 현재 화면의 profile/입력과 다른 시점의 store를 조합하거나 getter에서 새 HTTP client/수명을 만들지 않는다.

store 교체는 이전 text 수명을 먼저 종료하고 PDF 수명도 정리한다. 입력 변경·disconnect·비밀번호 세션 해제·onCleared가 같은 경계를 사용한다. 같은 client 객체와 같은 account key로 다시 연결해도 새 authority다. 이전 authority에서 operation을 만들거나 기존 ticket을 쓰면 거절한다. 이전 issuer가 새 getter를 호출해 권한을 빌리지 않도록 후속 UI는 렌더 당시 authority 객체를 캡처해야 한다.

비밀번호 변경 성공도 세션 종료다. 인증 실패·충돌·결과 불명·전송 취소 때 자격 증명을 지우고 재로그인을 요구하며 자동으로 새 client를 설치하지 않는다. 확정적인 입력/현재 비밀번호 오류는 기존 계정을 유지한다. 취소된 이전 비밀번호 요청의 늦은 실패가 새 로그인 세션을 지우지 않도록 예외 경로에서도 coroutine 취소를 먼저 확인한다.

onCleared는 소유자를 영구 종료하고 대기 작업을 취소한다. 늦은 connect/register 응답이나 이후 start/getter가 새 권한을 발급하지 않는다. 이미 연결된 상태에서 재연결이 실패해 client/자격 증명을 교체하지 않았다면 마지막으로 검증한 authority를 유지한다.

## 잠금과 후속 소비자

VM의 계정 전환은 기존처럼 UI 소유자 thread에서 직렬화한다. getter에는 별도 owner monitor를 추가하지 않았다. operation ticket의 synchronous callback은 gate→account를 유지하고, 후속 adapter가 library→binding→journal을 이어야 한다. journal의 마지막 guard는 ticket.check의 메모리 확인만 사용한다. 네트워크 await를 이 callback에 넣지 않으며 callback 뒤 guard로 durable 성공을 취소하지 않는다.

이 단위는 계정 수명의 실제 발급/종료 연결이다. 기존 readingConnection 소비자가 자동으로 lifetime lock을 획득하는 것은 아니다. 실제 Compose selection/options lease·canonical 전체 문서/typed snapshot·raw nonce·직접 verify/GET·nullable journal CAS adapter·별도 확인·정상 actor notifyCommitted 인계는 후속이다. PDF 타입/UUID를 text S1~S3에 사용하지 않는다.

## 검증 범위

대상 **4클래스34개**, 새 실제 VM owner 회귀 **12개**가 통과했다. 응답 profile의 canonical username/client 결합·getter 무발급·입력별 종료·같은 client/key 재연결·합성 account/origin ABA·실패한 재연결·실제 ViewModelStore.clear·늦은 connect/register·비밀번호 성공/불명/취소 뒤 늦은 실패를 검사했다. 실제 Main disconnect가 worker ticket의 synchronous callback 동안 BLOCKED로 대기하고 callback의 성공7을 보존함도 확인했다.

독립 검토에서 취소된 이전 비밀번호 요청의 늦은 실패 경로와 이전 수명 종료 대기 뒤 발급 generation 재검사를 보완했다. 첫 focused 실행의34개 중4개 실패는 새 fixture의 빈 등록 프로필·인증 없는 공유 client·해제 후 supervisor join을 수정했다. production 입력/인증 검증을 완화하지 않았고 최종 대상34개가 통과했다. 초기 log/XML을 보존한다.

JVM ViewModel/주입 HTTP transport와 실제 ViewModelStore 해제 검사이며 물리 Android·실소켓·PostgreSQL·Compose UI 실행 근거가 아니다. 계정/origin ABA는 주입 client를 재사용한 합성 응답이며 실제 타 origin 로그인 왕복이 아니다. 서버/DB/웹 production과 원본 PR34 변경을 건드리지 않는다.

전체 앱 **719개 중703통과/16 opt-in 제외**(139 suite), backup-runtime50개·APK/lint/계측 소스 컴파일·모듈 경계가 성공했다(전체1분44초, 기존 task 재사용 포함). 계측 소스 컴파일을 기기 실행으로 표시하지 않는다. 구현 head `bcfd0cf81d311879c58b58a7cd10acab41881e3f`의 run40192574968도 성공했으며 문서 포함 최종 head CI는 별도로 대조한다.

ignored 근거: `text-account-owner-focused.log`, `text-account-owner-focused-counts.json`, `text-account-owner-full.log`, `text-account-owner-counts.json`, `text-account-owner-initial-failure.log`·`.xml`. 정확한 최종 head CI는 PR 본문/`text-account-owner-final-ci.json`에서 확인한다. 선행 PR #61 최종 `e1b4749332183e996aebe2d184f53eda7a1f4c4d`/run40175833802 SUCCESS3분04초를 재확인했다.
