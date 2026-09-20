# PageTurner Server — 웹·앱 공통 API

Spring Boot 3.5.16 / PostgreSQL 기반입니다.
별도 `/frontend` 클라이언트의 연동 방법과 전체 경로는 [WEB_API.md](WEB_API.md)를 참고하세요.
앱 모듈에 의존하지 않고 공용 Kotlin 코어를 참조합니다. 기존 저장·조회·백업 계획 API와
번역 JSON 백업 다운로드·복원을 제공합니다. 서재·챕터·읽기 위치·책갈피를 PostgreSQL에
저장하며 브라우저 세션 인증을 지원합니다. Drive 업로드는 아직 구현하지 않았습니다.

## Run

Create a PostgreSQL database and set these environment variables when the
defaults are not suitable:

- `PAGETUNER_DATABASE_URL`
- `PAGETUNER_DATABASE_USER`
- `PAGETUNER_DATABASE_PASSWORD`

Then run:

```bash
./gradlew :server:bootRun
```

앱 없이 빌드하려면 `./gradlew -PbuildTarget=server :server:bootJar`를 사용합니다.
실행 시 `PAGETUNER_LOCAL_PASSWORD`를 반드시 설정해야 합니다. 로컬 전용 HTTP Basic
인증과 브라우저 세션 로그인을 지원하며 사용자 이름 기본값은 `local-reader`입니다.
현재 환경변수로 설정한 개인용 계정을 사용합니다. 사용자 지정 헤더를 신뢰하지 않습니다.
CSRF 보호가 켜져 있으므로 변경 요청에는 유효한 CSRF 토큰이 필요합니다.
서버는 기본적으로 `127.0.0.1`에만 바인딩합니다. 계정 가입·관리 기능은 후속 작업입니다.

주요 API (전체 계약: [WEB_API.md](WEB_API.md)):

- `GET /api/v1/session`, `POST /api/v1/session`, `POST /api/v1/session/logout` — 세션 인증
- `/api/v1/library/books` — 서재·챕터·진행률·책갈피
- `GET /api/v1/translations` — 번역 목록·필터
- `GET /api/v1/csrf` — 변경 요청용 CSRF 토큰 발급
- `GET /api/v1/translations/{recordId}/backup` — 번역 JSON 파일 다운로드
- `POST /api/v1/translations/restore` — 백업 JSON 검증·복원
- `POST /api/v1/translations`
- `GET /api/v1/translations/{recordId}`
- `POST /api/v1/translations/{recordId}/backup-plans`
- `GET /actuator/health`

The backup endpoint creates or reuses a durable backup plan. A Google Drive
OAuth connection and upload worker will consume queued plans in the next phase.

## 번역 파일 백업과 복원

백업은 레코드(챕터 번역 revision) 단위입니다. 원문 식별자, 번역 공급자·모델,
프롬프트·용어집 revision, 언어, 문단 순서와 번역 내용을 함께 저장합니다.
파일의 `schemaVersion`은 1이며 artifact ID, revision, payload hash를 다시 계산해
손상 여부를 검사합니다. 해시는 손상 검사용이며 파일 작성자의 진위를 보증하지 않습니다.
복원 소유자는 로그인 사용자로 지정됩니다. 같은 번역을 다시 복원하면 기존 레코드를
반환(200)하고, 다른 번역 revision은 새 레코드로 저장(201)합니다.
다운로드가 Drive 업로드 완료 상태를 변경하지는 않습니다.

```bash
# 비밀번호는 curl 프롬프트에서 입력합니다.
curl --fail --user local-reader \
  http://127.0.0.1:8080/api/v1/translations/RECORD_ID/backup \
  --output translation-backup.json

# 반환된 token과 headerName을 다음 POST에 사용합니다.
curl --fail --user local-reader --cookie-jar cookies.txt \
  http://127.0.0.1:8080/api/v1/csrf

curl --fail --user local-reader --cookie cookies.txt \
  --header 'X-CSRF-TOKEN: TOKEN_FROM_PREVIOUS_RESPONSE' \
  --header 'Content-Type: application/json' \
  --data-binary @translation-backup.json \
  http://127.0.0.1:8080/api/v1/translations/restore
```

백업 파일에는 번역 내용이 평문으로 포함됩니다. 다운로드한 파일은 별도 저장소에
보관해야 서버 DB 손실 시 복원할 수 있습니다. 웹 화면과 Drive 자동 업로드는 후속 범위이며,
기존 Spring HTTP API 및 health endpoint는 그대로 제공합니다. 프론트엔드 정적 파일은 서버에서 제공하지 않습니다.

검증 명령:

```bash
./gradlew -PbuildTarget=server :server:test :server:bootJar verifyModuleBoundaries
```

JSON 왕복·무결성 검증과 MVC 인증·CSRF·다운로드·복원 계약을 테스트합니다.
Docker가 있으면 `WebApiIntegrationTest`가 실제 PostgreSQL에서 인증·서재·소유자 격리·
진행률·책갈피·번역 저장 및 복원을 검증합니다. Docker가 없으면 통합 테스트만 건너뜁니다.

## 프론트엔드 분리 설정

- `PAGETUNER_FRONTEND_ORIGINS`: 허용된 프론트엔드 origin 목록(쉼표 구분).
  기본값: `http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173`.
- `PAGETUNER_SERVER_ADDRESS`: 기본값 `127.0.0.1`.
- `PAGETUNER_COOKIE_SECURE`: HTTPS 운영 시 `true`. 로컬 HTTP 기본값 `false`.

브라우저는 `GET /api/v1/csrf` → `POST /api/v1/session` → CSRF 재조회 순서로
로그인합니다. 모든 fetch에 `credentials: 'include'`를 지정합니다.
