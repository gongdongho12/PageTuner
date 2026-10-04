# Android 계정 변경 요청의 단일 전송

프로필 PATCH와 비밀번호 변경 POST는 응답을 잃었거나 서버가 503을 돌려준 뒤 자동으로 다시 보내면 안 된다. 특히 프로필 저장은 CAS가 없어 두 번째 전송이 다른 클라이언트의 변경을 덮을 수 있다. 기존 OkHttp의 `retryOnConnectionFailure(false)`는 연결 오류 재시도를 막지만 `503 Retry-After: 0`의 HTTP 후속 요청까지 막지는 않았다.

## 변경 범위

`NonRetryingAccountHttpTransport`의 요청 본문은 한 번만 전송할 수 있는 `RequestBody`로 감싼다. 기존 본문의 UTF-8 bytes·Content-Type·Content-Length·쓰기 구현에 위임하며 URL·Basic 인증·CSRF·쿠키·응답 처리·4 MiB 응답 한도는 유지한다. 연결 오류 재시도와 리다이렉션 차단도 유지한다.

503의 HTTP 후속 요청은 공통 one-shot 검사에서 반환되고, 421은 본문 검사에서 반환된다. 408과 연결 오류는 기존 `retryOnConnectionFailure(false)`에서 중단된다. [OkHttp 4.12 공식 구현](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)의 판단 순서도 독립 검토했다. 앱이 명시적인 새로운 사용자 요청까지 금지하는 것은 아니며 서버의 성공 여부를 추측하지 않는다. 비밀번호의 결과 불명확 안내·인증정보 정리와 새 로그인 상태를 지키는 기존 세대 검사를 유지한다.

현재 대상은 `PATCH /api/v1/accounts/me`와 `POST /api/v1/accounts/me/password`다. 가입과 CSRF GET은 기존 표준 transport를 사용하며 이 수정으로 그 경로의 단일 전송까지 검증했다고 표시하지 않는다. PDF 전용 transport는 별도 [PR #43](https://github.com/gongdongho12/PageTuner/pull/43)에 포함되어 있다.

## 검증 기록

- 2026-10-05 수정 전 실제 loopback HTTP에서 프로필 PATCH와 비밀번호 POST의 503을 각각 재현했다. 두 테스트 모두 요청 수가 기대 1회가 아닌 2회여서 실패했다. 원본 checkout의 `.gradle-home/account-mutation-red.log`에 결과를 보관했다.
- 수정 후 계정·비밀번호 대상 27개가 통과했다. 새 7개 테스트는 각 mutation의 503/408/421, 307/308 리다이렉션, 응답 유실에서 요청 1회와 원래 상태/헤더/본문을 확인한다. UTF-8 본문·Content-Length·비밀번호 공백·인증/CSRF/쿠키, 가입/CSRF의 기존 dispatch도 유지된다. `.gradle-home/account-mutation-green.log`에 기록했다.
- 전체 Android 544개 통과/16개 opt-in 제외, APK·lint·계측 소스 컴파일·모듈 경계 성공. `.gradle-home/account-mutation-full.log`에 기록했다. 독립 검토에서 추가 P1/P2 결함은 없었다.

변경은 [draft PR #44](https://github.com/gongdongho12/PageTuner/pull/44), base `codex/pdf-content-clients`(#43), head `codex/account-mutation-single-send`다. 수정·회귀 커밋은 `067b52e`이며 자동 병합하지 않는다. PR #43 최종 `45d969d`의 [CI](https://github.com/gongdongho12/PageTuner/actions/runs/37219805439) 성공을 확인했고, 이번 PR은 문서를 포함한 최종 head의 CI를 별도로 확인한다.

테스트는 가짜 loopback HTTP 응답을 사용하는 로컬 JVM 검사다. 실제 사용자 비밀번호나 미리보기 DB를 변경하지 않으며 실제 Android 화면·네트워크 검증은 V3에 남긴다. 421 응답 회귀는 HTTP/1 소켓을 사용하므로 실제 HTTP/2 연결 병합 실행과 구분한다.
