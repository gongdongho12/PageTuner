# 번역 공급자 연결

앱과 서버는 `translation-runtime`의 같은 공급자를 사용한다. 웹의 전체·읽기·묶음·목록 번역은
서버 API를 호출하며, 각 설정 화면에서 같은 연결 필드와 **연결 확인** 탭을 사용한다.
API 키는 웹 화면의 메모리에서만 유지하고 서버 실행에 전달한다. 화면 간 키 자동 공유나
브라우저 저장은 하지 않는다. 기존 Android 로컬 설정 저장 정책은 별도다.

| 공급자 | 서버 설정 | 기본 동작 |
| --- | --- | --- |
| Google 웹 번역 | 키 불필요 | 공개 HTML 번역 endpoint. 외부 요청 제한 시 명확한 실패 안내 |
| Google Cloud Translation | `PAGETUNER_GOOGLE_API_KEY` | Translation v2, `x-goog-api-key` 헤더로 인증 |
| DeepSeek | `DEEPSEEK_API_KEY`, 선택 `DEEPSEEK_API_URL`, `DEEPSEEK_MODEL` | `deepseek-flash`, JSON 응답, thinking 비활성화, `max_tokens=32768` |
| OpenAI 호환 API | `OPENAI_API_KEY`, 선택 `OPENAI_API_URL`, `OPENAI_MODEL` | Chat Completions, 기본 `gpt-4.1-mini`; 제공자의 호환 모델 지정 가능 |

서버 키를 설정하지 않았으면 웹 연결 설정에서 이번 화면에 사용할 키를 입력한다. 서버 키가
설정됐으면 입력을 비워 서버 키를 사용한다. 사용자 지정 LLM 주소는 기본 주소 또는
`PAGETUNER_LLM_ENDPOINTS`의 쉼표로 구분된 허용 목록과 정확히 일치해야 한다.
HTTPS를 사용하고, 명시적으로 등록한 loopback 개발 주소만 HTTP를 허용한다.
주소에 사용자 정보·query·fragment를 넣거나 redirect를 따르지 않는다.

DeepSeek의 명시적으로 저장된 기존 모델 값은 유지한다. 현재 기본값과 요청 옵션은
[DeepSeek 요청 규격](https://api-docs.deepseek.com/api/create-chat-completion/) 및
[모델과 기존 별칭](https://api-docs.deepseek.com/quick_start/pricing/)을 2026-09-16 확인했다.
Google 헤더 인증은 [공식 API 키 사용법](https://docs.cloud.google.com/docs/authentication/api-keys-use)을 따른다.
외부 모델·권한·가격은 변경될 수 있다.

## 연결 확인 API

기존 Basic 인증과 CSRF 토큰·세션 쿠키를 사용하는 `POST /api/v1/translation-providers/check`다.
공통 스키마는 `contracts/workflow-v1.openapi.json`에 있고 웹 타입을 여기서 생성한다.

```json
{
  "providerKind": "DEEPSEEK",
  "sourceLanguage": "auto",
  "targetLanguage": "ko"
}
```

선택 필드는 `apiKey`, `endpoint`, `model`이다. 서버의 고정 영어 한 문장만 번역한다.
웹은 항상 원문 자동 감지를 요청하며 책 본문·용어집·문서 ID를 보내지 않는다.
공급자 API를 실제 호출하므로 유료 공급자는 비용이 발생할 수 있다.

정상 응답은 HTTP 200의 `SUCCESS` / `PROVIDER_CHECK_OK`다. 공급자 오류는 HTTP 200의
`FAILED`와 고정 공개 코드·안내를 반환한다. 잘못된 설정은 400, 계정·전역 실행 제한이나
10초 호출 간격은 429다. 인증과 CSRF 실패는 기존 401/403을 사용한다.
응답에는 공급자·언어·모델과 상태·코드·안내만 있고 키·주소·원문·번역문은 없다.

요청은 최대 16KiB이며 계정별 1개, 전체 4개만 실행한다. 응답 제한은 20초이고 타임아웃 시
공급자 작업을 취소한다. 취소 정리가 끝날 때까지 실행 슬롯은 유지된다. 브라우저 취소가
실제 HTTP 연결 종료로 즉시 감지되는지는 컨테이너에 달려 있으며 최대 제한 시간까지
공급자 호출이 계속될 수 있다. 원문·작업·checkpoint·번역 artifact를 저장하지 않는다.

웹에서는 중복 확인을 막고 설정 변경·화면 종료·계정 연결 변경 시 이전 결과를 버린다.
연결 확인 성공은 해당 짧은 요청의 성공이며 긴 책 전체의 번역 성공을 보장하지 않는다.

## 불완전 응답과 재시도

LLM은 `finish_reason=stop`인 하나의 Chat Completion과 완전한 JSON 번역 배열을 요구한다.
잘림·거절·도구 호출·문단 수 불일치·숫자 등 문자열 아닌 번역·중복 JSON 키·후행 데이터는
`TRANSLATION_INVALID_RESPONSE`로 실패한다. 단일 JSON 코드 펜스는 허용하지만 설명문에서
JSON 일부만 추출해 성공으로 바꾸지 않는다. 불완전 배치는 완료 checkpoint로 저장되지 않는다.
공통 LLM prompt revision과 앱 캐시 식별자에 새 응답 규격·요청 옵션을 반영했다.

전체 번역은 저장된 완료 문단부터 기존 재시도 절차를 사용한다. 읽기·목록 번역도 같은
인증·사용량·요청 제한·연결·응답 오류 코드를 표시하며 완성 번역 artifact와 구분한다.

자동 검증과 실제 외부 호출 결과는 [검증 기록](WEB_FRONTEND_VALIDATION.md)을 따른다.
유료 키가 없는 환경의 localhost 가짜 응답 검증은 실제 DeepSeek·Google Cloud·OpenAI 번역 성공을 뜻하지 않는다.
