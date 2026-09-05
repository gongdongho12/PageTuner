# PageTurner Server — 백엔드 작업 영역

Spring Boot 3.5.16 / PostgreSQL 기반의 초기 구현입니다.
앱 모듈에 의존하지 않고 공용 Kotlin 코어를 참조합니다. 저장 API와 백업 계획 코드가
있지만 실제 PostgreSQL 통합 검증 및 Drive 업로드는 다음 백엔드 단계에서 진행합니다.

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
인증이며 사용자 이름 기본값은 `local-reader`입니다. 사용자 지정 헤더를 신뢰하지 않습니다.
CSRF 보호가 켜져 있으므로 변경 요청에는 유효한 CSRF 토큰이 필요합니다.
서버는 기본적으로 `127.0.0.1`에만 바인딩합니다. 공개 서비스용 인증/세션 흐름은 후속 작업입니다.

Implemented endpoints:

- `POST /api/v1/translations`
- `GET /api/v1/translations/{recordId}`
- `POST /api/v1/translations/{recordId}/backup-plans`
- `GET /actuator/health`

The backup endpoint creates or reuses a durable backup plan. A Google Drive
OAuth connection and upload worker will consume queued plans in the next phase.
