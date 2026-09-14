# 번역본 저장·조회 연동

2026-09-14 작업 루프: 공통 계약 → 서버·앱 병렬 구현 → 교차 리뷰 → 독립 빌드/테스트.

## 모듈별 책임

```text
app: TranslationCacheSyncService
  → core-translation: TranslationStore / StoredTranslation / TranslationSaveResult
  → app: HttpTranslationStore (Basic 인증, CSRF 세션, JSON, 취소)
  → server: /api/v1/translations (사용자 소유권, 검증, 트랜잭션)
  → PostgreSQL (원본 식별자, 전체 메타데이터, 번역 문단, revision)
```

공통 코어에는 Android, Spring, HTTP, JSON 프레임워크 의존성을 추가하지 않습니다.
앱과 서버는 서로의 실행 모듈을 참조하지 않습니다. `web/`은 같은 OpenAPI에서
TypeScript 타입을 생성해 사용자별 서재·페이지 리더·기기 보관함을 구현합니다.
원문 수집과 번역 실행은 별도의 `source-runtime`, `translation-runtime`에서
앱·서버가 함께 사용합니다. 완성된 번역을 저장하는 이 계약은 그대로 유지합니다.
새 실행 흐름은 [소설 수집·번역 작업](NOVEL_TRANSLATION_WORKFLOW.md)을 참고합니다.

## 계약

- Kotlin 계약: `core-translation/.../TranslationStore.kt`.
- HTTP 계약: [OpenAPI](../contracts/translation-v1.openapi.json).
- 공유 요청/응답 샘플: [fixtures](../contracts/fixtures/translation-v1/).
- `POST /api/v1/translations`: 새 revision이면 201, 같은 사용자·artifact·revision이면
  기존 레코드를 200으로 반환합니다. 이 저장 엔드포인트 자체는 공급자를 호출하지 않습니다.
  서버 번역 실행은 별도 `POST /api/v1/translation-jobs`가 담당하고, 완료된 결과를 같은 저장 서비스에 전달합니다.
- `GET /api/v1/translations?page=0&size=12`: 현재 사용자의 번역 요약과 페이지 정보를 반환합니다.
- `GET /api/v1/translations/{recordId}`: 해당 사용자에게 속한 번역본의 원본 식별자,
  언어·모델·프롬프트·사전 버전, 문단을 반환합니다. 없는 레코드와 다른 사용자 소유는 404입니다.
- 쓰기 전에 인증된 `GET /api/v1/csrf`에서 토큰과 세션 쿠키를 받고 같은 세션으로 POST합니다.
  자격증명은 호출자가 주입하며 이 어댑터가 디스크에 저장하지 않습니다.

앱은 응답의 artifactId/revision/payloadHash를 공통 코어 계산과 비교합니다.
복원할 때에는 원본 revision과 번역 설정까지 로컬 문단 매핑에 일치해야 합니다.

## 기존 데이터와 수동 동기화

기존 페이지 캐시의 document/segment ID는 유지합니다. 현재 캐시에는 기기 독립적인
문단 매핑이 없으므로 페이지 번호로 서버 문단 ID를 추측하지 않습니다. 호출자가
원문 `ChapterContent`, 로컬 `ReaderDocument`, 정확한 문단→segment 1:1 매핑과
번역 설정을 제공해야 합니다. 분할/병합 문단, 불완전한 캐시, 원문 불일치는 거부합니다.

복원은 전체 데이터를 검증한 뒤 캐시에 적용합니다. 다른 로컬 번역이 이미 있으면
기본적으로 충돌을 반환하여 사용자의 수정본을 보존합니다. 자동 업로드나 앱 시작 시
자동 복원은 추가하지 않습니다. 이 캐시 변환 API와 서버 서재에서 번역본을 읽는 기능은 구분합니다.
호출 방법과 세부 조건은 [앱 동기화 문서](../app/TRANSLATION_STORE_SYNC.md)를 참고합니다.

서버 V2 마이그레이션은 기존 V1 및 해시를 변경하지 않고 원본 공급자 ID와 책 ID를
별도 컬럼에 보존합니다. 과거 결합 문자열은 원본을 정확히 복구할 수 없으므로
원본 메타데이터가 없는 행의 GET은 409를 반환합니다. 같은 번역본을 완전한 메타데이터로
다시 POST하면 기존 행을 보완합니다. 충돌하는 원본 식별자를 덮어쓰지는 않습니다.
백업 계획은 저장된 해시로 계산해 기존 백업 키를 유지합니다.

## 검증 명령

```bash
./gradlew -PbuildTarget=core verifyModuleBoundaries :core-model:test :core-content:test :core-translation:test :core-backup:test
./gradlew -PbuildTarget=core :source-runtime:test :translation-runtime:test
./gradlew -PbuildTarget=server verifyModuleBoundaries :server:test :server:bootJar
./gradlew -PbuildTarget=app verifyModuleBoundaries :app:testDebugUnitTest :app:assembleDebug
```

서버 테스트는 기본적으로 PostgreSQL Testcontainers를 사용합니다. Docker가 없는 경우
`PAGETUNER_TEST_DATABASE_URL/USER/PASSWORD`를 모두 명시하면 전용 로컬 PostgreSQL로
같은 통합 테스트를 실행합니다. loopback의 `pagetuner_test` 또는 `pagetuner_test_*`
DB만 허용하며 테스트는 해당 DB의 번역/백업 테이블을 비웁니다. 상세 설정은
[서버 검증 문서](../server/README.md)를 참고합니다.

DB 없이 수행하는 선택 검증은 다음과 같습니다. 이 결과를 DB 통합 검증으로
집계하지 않습니다.

```bash
./gradlew -PbuildTarget=server :server:test --tests '*ServerSecurityMvcTest' --tests '*TranslationWireContractTest'
```

실행 중인 전용 로컬 서버에 `PAGETURNER_INTEGRATION_BASE_URL/USERNAME/PASSWORD`를
명시한 뒤 `:app:translationServerIntegrationTest`를 실행하면 실제 Android HTTP 어댑터로
저장 → 조회 → 다른 캐시 파일 복원 → 중복 저장 재사용을 검증합니다. 이 검증은
기본 단위 테스트와 별도 작업이며 설정 누락을 건너뛰기로 처리하지 않습니다.
매 실행마다 테스트용 책 한 건을 생성하므로 전용 임시 서버에서만 실행합니다.

CI도 코어·서버·앱을 독립 작업으로 검사합니다. 위 기본 테스트에는 외부 사이트 실호출,
유료 번역 공급자, Drive 업로드·복원, Android 실기기 동작이 포함되지 않습니다.
실제 Google Web·소설 공급자 호출은 [별도 실행 검증](NOVEL_TRANSLATION_WORKFLOW.md#검증)을 사용합니다.

## 최초 저장·복원 루프 검증 결과

2026-09-14, Windows / JDK 21 / Android SDK 36.1 / 전용 PostgreSQL 16.15 기준:

| 검증 | 결과 |
| --- | --- |
| 공통 코어 | 17개 통과, 실패·건너뜀 0 |
| 서버 HTTP/계약/실제 DB | 13개 통과, 실패·건너뜀 0 |
| 앱 단위 테스트 | 280개 중 266개 통과, 기존 외부 서비스 호출 테스트 14개 건너뜀, 실패 0 |
| 실제 앱 어댑터 → HTTP 서버 → PostgreSQL → 다른 캐시 파일 | 1개 통과, 실패·건너뜀 0 |
| 빌드 | `app-debug.apk`, `server.jar` 생성 성공 |
| 계약/경계 | OpenAPI 독립 검증, 모듈 의존성 검사, diff 공백 검사 통과 |

앱 통합 테스트는 기본 단위 테스트와 분리해 실제로 실행했습니다. 개별 통합 작업의
태스크 그래프에도 테스트 소스 컴파일과 리소스 준비가 포함됨을 확인했습니다.
PostgreSQL은 Docker 대신 작업 디렉터리의 일회용 네이티브 인스턴스를 사용했습니다.
실기기 테스트와 외부 서비스 호출 테스트를 통과로 집계하지 않습니다.

교차 리뷰에서 복원 검사/쓰기 사이의 동시 수정과 디스크 실패 후 메모리 변경을
발견해 파일별 잠금과 성공 후 상태 반영으로 수정했습니다. Windows 검증에서 확인한
기존 파일 교체 문제는 공통 원자 교체 함수로 처리하며, 같은 함수를 번역 캐시,
오프라인 챕터, 카탈로그 페이지 저장에 적용했습니다. 대상 파일을 먼저 삭제하지 않고
원자 교체가 불가능하면 실패합니다. Android 23에는 NIO 파일 API를 직접 연결하지 않습니다.

## 연결된 실행 흐름과 남은 범위

서버는 공급자에서 받은 원문을 보존하고 안정적인 원문 문단 ID와 revision을 생성합니다.
작업별 번역 설정·용어집 revision을 고정해 공통 엔진을 실행하고, 완성된 문단만 checkpoint에 저장합니다.
전체 문단이 완료되면 기존 `TranslationArtifact`로 저장하므로 웹·앱 조회 계약을 추가로 변환하지 않습니다.
웹 오프라인 사본의 문단/문자 위치는 페이지 크기와 독립적이며, 서버 간 읽기 위치 동기화와는 구분합니다.

기존 Android 페이지 캐시와의 임의 분할/병합 매핑, 읽기 위치의 기기 간 동기화,
Drive 실제 업로드·복원, 공개 서비스 인증과 Android 실기기 검증은 별도 후속 범위입니다.
