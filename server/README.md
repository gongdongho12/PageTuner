# PageTurner Server — 백엔드 작업 영역

Spring Boot 3.5.16 / PostgreSQL 기반의 초기 구현입니다.
앱 모듈에 의존하지 않고 공용 Kotlin 코어를 참조합니다. 번역본 저장·조회 및 백업
계획 API를 제공합니다. 서버의 번역 실행과 Drive 업로드는 아직 구현하지 않았습니다.

별도 React/TypeScript 클라이언트는 [web/](../web/README.md)에서 빌드합니다.
`web/dist`를 먼저 만든 뒤 `-PwebDistDir=web/dist :server:bootJar`로 웹을 함께
패키징할 수 있습니다. 이때 `/`에서 서재를 열며 API와 같은 origin을 사용합니다.
공개 웹 정적 파일에만 인증 예외를 적용하고 `/api/**` 인증과 쓰기 CSRF 검증은 유지합니다.

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
회원가입은 PostgreSQL 계정을 생성하고 비밀번호는 BCrypt로 저장합니다. 기존 로컬 계정은
`PAGETUNER_LOCAL_PASSWORD`를 설정하면 최초 인증 때 보존하며 사용자 이름 기본값은
`local-reader`입니다. 환경변수를 생략하면 회원가입으로 시작합니다. API는 HTTP Basic을
사용하며 사용자 지정 소유자 헤더를 신뢰하지 않습니다.
CSRF 보호가 켜져 있으므로 변경 요청에는 유효한 CSRF 토큰이 필요합니다.
서버는 기본적으로 `127.0.0.1`에만 바인딩합니다. 원격 배포는 HTTPS가 필요합니다.

Implemented endpoints:

- `GET /api/v1/csrf`
- `GET /api/v1/translations?page=0&size=12`
- `POST /api/v1/translations`
- `GET /api/v1/translations/{recordId}`
- `POST /api/v1/translations/{recordId}/backup-plans`
- `GET /actuator/health`
- `GET /api/v1/accounts/csrf`, `GET /api/v1/accounts/languages` (public)
- `POST /api/v1/accounts/register` (public, CSRF required)
- `GET /api/v1/accounts/me`, `PATCH /api/v1/accounts/me`
- `GET /api/v1/novel-sources`, `GET /api/v1/novels/catalog`, `GET /api/v1/novels/detail`
- `POST /api/v1/chapters/import`, `POST /api/v1/chapters/upload`, `GET /api/v1/chapters`, `GET /api/v1/chapters/{recordId}`
- `GET /api/v1/translation-providers`, `POST /api/v1/translation-jobs`, `GET /api/v1/translation-jobs`
- `GET /api/v1/translation-jobs/{jobId}`, `POST /api/v1/translation-jobs/{jobId}/cancel`

회원가입은 3–40자 영문 소문자 계정 이름과 표시 이름을 받습니다. 비밀번호는 Unicode
문자 10개 이상이며 BCrypt 한계에 맞춰 UTF-8 72바이트 이내로 제한합니다. 계정의
`locale`은 화면 언어, `targetLanguage`는 번역 기본 언어입니다. BCP 47 태그를 정규화해
보관하므로 후속 언어팩을 위해 계정 마이그레이션이 필요하지 않습니다. 현재 한국어·영어
팩을 제공하고 나머지는 `effectiveLocale=en`으로 안내합니다. API 키는 작업 실행 메모리에만
두고 계정/번역 작업 테이블에는 저장하지 않습니다.

단일 프로세스 기준 등록은 IP별 30분에 10회, 실패한 Basic 로그인은 15분에 20회 제한합니다.
프록시 헤더를 임의로 신뢰하지 않습니다. 이메일 없는 사용자 이름 계정이므로 메일 인증,
비밀번호 분실 복구, 다중 서버 공통 rate limiter는 제공하지 않습니다.

WTR-LAB와 NovelBuddy의 장르는 소스 응답 `filters.genres` 값을 사용합니다. WTR 정렬·상태는
`orderBy`, `order`, `status` 쿼리로 전달하고 미지원 값은 거부합니다. 로컬 파일 본문은
`chapters/upload`로 문단 ID와 순서를 보존해 저장한 뒤 동일 번역 작업 API를 사용할 수 있습니다.
한 업로드는 최대 10,000문단·1,000,000자이며 큰 책은 장별로 나눕니다.

번역 작업의 중복 요청·취소·실패 재시도와 키 설정은 [워크플로 문서](../docs/NOVEL_TRANSLATION_WORKFLOW.md),
정확한 wire 계약은 [workflow OpenAPI](../contracts/workflow-v1.openapi.json)와
[accounts OpenAPI](../contracts/accounts-v1.openapi.json)를 참고하세요.

The backup endpoint creates or reuses a durable backup plan. A Google Drive
OAuth connection and upload worker will consume queued plans in the next phase.

## 앱 연동 계약

HTTP Basic으로 인증한 뒤 `GET /api/v1/csrf`를 호출합니다. 응답은
`{"headerName":"X-CSRF-TOKEN","token":"..."}`이며 `Cache-Control: no-store`를
사용합니다. 클라이언트는 여기서 받은 세션 쿠키를 유지하고, 변경 요청에 응답의
`headerName`과 `token`을 헤더로 보냅니다. 인증 정보만 있고 CSRF 토큰 또는 같은
세션 쿠키가 없으면 저장 요청은 거부됩니다.

번역 저장 요청과 조회 응답은 다음 원본 메타데이터를 포함합니다.

- `contentProviderId`, `bookId`, `chapterId`, `sourceRevision`
- `sourceLanguage`, `targetLanguage`, `translationProviderId`
- `modelId`, `promptRevision`, `glossaryRevision`, `paragraphs`

응답에는 기존 `recordId`, `artifactId`, `revision`, `payloadHash`, `created`,
`createdAt`도 유지합니다. 클라이언트는 이 데이터로 공통 `TranslationArtifact`를
완전히 복원하고 해시를 검증할 수 있습니다. 서버는 코어의 `StoredTranslation`과
`TranslationSaveResult`를 HTTP DTO로 매핑합니다. 동일 사용자·번역 ID·revision의
반복 저장은 같은 레코드와 `created=false`를 반환합니다. 다른 사용자의 레코드
조회와 백업 계획 요청은 404입니다. 공통 wire 예시는
[`contracts/fixtures/translation-v1`](../contracts/fixtures/translation-v1)에 있습니다.

V2 마이그레이션은 원본 공급자 ID와 책 ID를 별도 열에 보존합니다. 공통 코어의
기존 식별자·해시 인코딩과 V1 마이그레이션은 변경하지 않았습니다. V1 데이터에는
구분자 경계와 공백 정보가 없어서 새 열을 추측으로 채우지 않습니다. 그런 기존
레코드의 조회는 복원 불가능한 메타데이터를 설명하는 409를 반환합니다. 원본
번역을 다시 POST하면 기존 레코드의 누락 메타데이터를 보완하며 레코드 ID와
해시는 유지합니다. 같은 해시를 만들지만 원본 메타데이터가 다른 요청은 409로
거부합니다. 기존 백업 계획은 저장된 해시로 계산하므로 이 보완 전후에도 같습니다.

웹 서재는 `GET /api/v1/translations?page=0&size=12`를 사용합니다. 0부터 시작하는
페이지와 1–50개 크기를 받으며 `page * size`가 2,147,483,647을 넘는 요청은 400입니다.
응답의 `items`는 원본 메타데이터, 번역 설정·해시, 생성 시각, `paragraphCount`를
포함하고 본문은 제외합니다. 같은 번역의 여러 revision은 각각 별도 항목입니다.
`page`, `size`, `totalItems`, `totalPages`, `hasNext`로 페이지 탐색을 제공합니다.
빈 서재의 `totalPages`는 0이며 마지막을 넘긴 요청은 빈 항목과 기존 합계를 반환합니다.

목록은 인증된 사용자 소유이고 원본 공급자·책 ID가 모두 있는 행만 대상으로 하며,
`createdAt DESC, recordId DESC` 순서가 고정됩니다. 누락 메타데이터가 있는 V1 행은
목록과 합계에서 제외됩니다. PostgreSQL 쿼리가 먼저 해당 페이지의 최대 50개 행을
고른 뒤 JSON 문단 개수만 계산하므로 본문은 서버 JVM과 HTTP 응답에 실리지 않습니다.
부분 인덱스로 사용자별 정렬 조회를 지원하며 합계·항목 조회는 같은 읽기 스냅샷을
사용합니다. 목록은 문단 본문 검증이나 revision 합치기를 대신하지 않습니다.

## 검증

```bash
# JDK 21 및 실행 중인 Docker가 필요합니다. 실제 PostgreSQL 17 컨테이너 사용.
./gradlew -PbuildTarget=server :server:test

# Docker 없이 실행 가능한 HTTP 보안 및 공통 JSON 계약 검사만 선택
./gradlew -PbuildTarget=server :server:test --tests '*ServerSecurityMvcTest' --tests '*TranslationWireContractTest'
```

PostgreSQL 통합 테스트는 Flyway 마이그레이션, 메타데이터 왕복, 반복·동시 저장,
사용자 격리, 백업 재사용, 기존 메타데이터 보완을 검사합니다. MVC 테스트는 실제
Basic 인증과 CSRF 발급·세션 쿠키·쓰기 거부를 확인합니다. JSON 계약 테스트는
앱과 같은 fixture를 소비합니다. 기본 CI와 환경변수 미설정 실행은 Testcontainers를
사용하며 Docker가 없으면 실패합니다. 자동 건너뛰기는 없습니다. Docker 없는
선택 실행은 DB 통합 테스트 성공을 뜻하지 않습니다.

Docker를 사용할 수 없는 개발 환경에서는 별도로 실행한 **일회용 로컬 PostgreSQL**을
명시적으로 사용할 수 있습니다. 다음 세 환경변수를 모두 설정한 프로세스에서
위 `:server:test` 명령을 실행합니다.

- `PAGETUNER_TEST_DATABASE_URL`: 예: `jdbc:postgresql://127.0.0.1:55432/pagetuner_test_local`
- `PAGETUNER_TEST_DATABASE_USER`: 일회용 데이터베이스 사용자
- `PAGETUNER_TEST_DATABASE_PASSWORD`: 해당 사용자 비밀번호

외부 모드는 `localhost`, `127.0.0.1`, `::1`만 허용하며 데이터베이스 이름은
`pagetuner_test` 또는 `pagetuner_test_*`여야 합니다. URL에 추가 연결 옵션이나
인증 정보를 넣을 수 없습니다. **각 테스트가 `translation_artifact`와
`translation_backup`의 모든 데이터를 삭제합니다.** 기존 업무·개발 데이터를
담은 DB를 지정하지 마세요. 테스트는 이 외부 서버를 시작하거나 종료하지 않으며,
임시 DB 생성과 종료는 실행자가 담당합니다. 이 모드도 실제 PostgreSQL에서
동일 마이그레이션·트랜잭션·동시성·API 검사를 실행합니다.
