# 앱 번역 저장소 연동

`translation/sync`는 공통 `TranslationStore`를 사용하는 수동 publish/restore 경로입니다.
기존 리더 UI, 자동 동기화, 계정 로그인 화면, 비밀 저장, 기존 캐시 키/파일 형식은 변경하지 않습니다.

## 호출

호출자가 서버 계정과 이미 번역된 문서, 원문 챕터 식별자 및 명시적인 문단 매핑을 제공합니다.
아래 변수는 해당 문서를 로드한 기능에서 전달해야 합니다.

```kotlin
val store: TranslationStore = HttpTranslationStore(
    baseUrl = "https://reader.example",
    auth = TranslationStoreBasicAuth(username, password),
)
val mapping = TranslationCacheChapterMapping(
    document = readerDocument,
    chapter = chapterContent,
    paragraphToSegmentId = mapOf(
        "source-paragraph-1" to readerDocument.pages[0].segments[0].id,
        "source-paragraph-2" to readerDocument.pages[1].segments[0].id,
    ),
    variant = TranslationCacheVariant(
        sourceLanguage = chapterContent.sourceLanguage,
        targetLanguage = "ko",
        cacheProviderId = translationProvider.id,
        translationProviderId = "deepseek",
        modelId = "chosen-model",
        promptRevision = "prompt-v1",
        glossaryRevision = "glossary-v1",
    ),
)
val sync = TranslationCacheSyncService(store, existingTranslationCache)
val saved = sync.publish(mapping)
val restored = sync.restore(saved.translation.recordId, mapping)
```

페이지 배열 접근은 호출 예시일 뿐이며 API는 페이지 번호로 문단 ID를 만들지 않습니다.
`paragraphToSegmentId`는 원본 공급자의 안정적인 문단 ID와 실제 문서 segment ID를 연결해야 합니다.
원문 챕터 전체를 1:1로 매핑하고 각 원문 문자열이 정확히 같아야 합니다. 여러 segment로 나뉜 문단,
여러 문단이 합쳐진 segment, 불완전한 번역은 이번 경로에서 거부합니다. 손실 없는 별도 변환기가 필요합니다.
`sourceLanguage="auto"`로 만들어진 기존 캐시는 `sourceLanguage="en"` 챕터 캐시로 간주하지 않습니다.

`cacheProviderId`는 기존 `TranslationProvider.id` 그대로 사용합니다. 이 값에는 endpoint/model/사전 등
앱의 캐시 구분 정보가 포함될 수 있습니다. 서버 메타데이터의 `translationProviderId`는 번역 공급자이며,
model/prompt/glossary revision은 별도 필드입니다. 호출자가 실제 번역에 사용한 값을 명시해야 하며,
동기화 서비스는 이 값을 provider enum이나 페이지 번호에서 추측하지 않습니다.

## 검증과 저장

- publish는 전체 챕터 캐시의 문서/segment/언어/provider key와 완전성을 확인한 후 서버에 저장합니다.
- restore는 챕터, source revision, 언어, 공급자, model/prompt/glossary revision, 모든 문단 ID와 순서를
  확인한 후 한 번의 조건부 일괄 쓰기로 기록합니다. 타임스탬프는 로컬 복원 시각입니다.
- 같은 key에 다른 로컬 번역이 있으면 기본적으로 `TranslationCacheConflictException`을 발생시키며
  전체 복원을 거부합니다. 교체 의도가 명시된 호출만 `restore(id, mapping, replaceExisting = true)`를 사용합니다.
- `TranslationCache.putAllIfCompatible`은 충돌 비교와 저장을 원자적으로 처리합니다. 기본 파일 캐시는
  같은 파일의 모든 인스턴스가 lock/메모리를 공유하고 디스크 쓰기에 성공한 후 메모리를 갱신합니다.
  다른 캐시 구현은 이 계약을 구현해야 기본 restore를 사용할 수 있습니다. 지원하지 않는 캐시는
  `UnsupportedOperationException`으로 실패하며 비원자적 get/put으로 대체하지 않습니다.
- 응답의 `artifactId`, `revision`, `payloadHash`는 공용 코어 계산 결과와 대조합니다.
  누락·손상·부분 응답과 서버의 legacy record `409 Conflict`는 로컬 캐시 쓰기 전에 실패합니다.

## HTTP 범위

기본 URL은 API 경로가 없는 HTTPS origin입니다. `localhost`, `127.0.0.1`, `::1` HTTP는 개발용으로
허용합니다. Android Emulator의 `http://10.0.2.2:8080`처럼 다른 HTTP 주소는
`allowInsecureDevelopmentHttp = true`를 생성자에 명시해야 합니다. 기기에서 localhost는 그 기기 자체입니다.

HTTP Basic 인증값은 생성자로만 전달하고 메모리에만 둡니다. save마다 `GET /api/v1/csrf`를 요청한 후
반환된 CSRF header/token과 같은 origin에 유효한 세션 cookie를 POST에 함께 보냅니다.
세션을 전역 cookie jar나 디스크에 저장하지 않습니다. 자동 redirect를 금지하며 3xx를 오류로 반환합니다.
연결 15초/읽기 30초 timeout, 응답 최대 4 MiB, coroutine 취소시 연결 해제를 적용합니다.
공개 save/get은 JSON 변환과 해시 검증까지 IO dispatcher에서 수행하고 save 입력 문단은 suspend 전에 복사합니다.
실패는 `TranslationStoreException.failure`와 `status`로 구분하며 서버 응답 본문/인증값을 오류에 넣지 않습니다.

`TranslationStoreHttpTransport`를 주입해 외부 API 없이 검증할 수 있습니다. 앱과 서버 계약 검사는
`contracts/fixtures/translation-v1`의 동일 JSON fixture를 사용합니다. unit tests는 CSRF/Basic 흐름,
인증·충돌·redirect·응답 위변조·취소·timeout, 명시 매핑 및 캐시 왕복/불일치/로컬 충돌을 검사합니다.
실제 서버 왕복 검사는 기본 unit test에서 제외된 별도 task입니다. 임시 로컬 서버를 준비한 뒤
`PAGETURNER_INTEGRATION_BASE_URL`, `PAGETURNER_INTEGRATION_USERNAME`,
`PAGETURNER_INTEGRATION_PASSWORD`를 환경변수로 전달하고
`./gradlew -PbuildTarget=app :app:translationServerIntegrationTest`를 실행합니다.
환경변수가 없거나 주소가 loopback이 아니면 명확히 실패합니다. 실제 HTTP 인증/CSRF, DB 저장/조회,
다른 캐시 파일 복원, 재POST recordId 재사용을 검사하며 서버에 테스트용 번역 1건을 남깁니다.
같은 task는 임의의 테스트 계정 1개를 등록하고 인증 GET/PATCH로 프로필을 왕복합니다.
`PAGETUNER_LIVE_JOB_CREATE=1`을 추가하면 합성 원문 2문단 업로드, 실제 Google Web 서버 job 생성,
완료 조회, 원문/번역 identity 검증, 앱 리더 읽기 및 중복 요청 재사용까지 검사합니다.
이 검사는 해당 opt-in이 없으면 task 대상에서 제외되며 원문/job/번역 1건씩을 테스트 DB에 남깁니다.
이미 실번역한 회차를 검증하려면 `PAGETUNER_LIVE_CHAPTER_RECORD_ID`와
`PAGETUNER_LIVE_TRANSLATION_RECORD_ID`를 함께 지정합니다. 원문과 번역의 전체 문단 ID/수/제목을
비교하고 실제 Google 번역과 한국어 본문임을 확인합니다.
실기기 터치/화면 및 운영 배포 인증 검증은 별도입니다.

## 앱의 계정·서버 번역 화면

서버 화면의 연결 탭에서 origin/계정/비밀번호를 입력합니다. 계정 탭은 회원가입 및 프로필 편집을
실제 계정 API에 연결합니다. UI locale과 번역 목표 언어는 독립적입니다. 프로필의 locale을 저장하면
서버가 확정한 `effectiveLocale`로 앱 리소스를 선택하며 ko/en 이외의 언어팩은 영어로 표시합니다.
프로필의 목표 언어를 기기의 기존 번역 설정에 적용하는 동작은 별도 버튼입니다.
비밀번호와 번역 API 키는 ViewModel 메모리에만 보관하며 연결 해제 시 비웁니다.

원문 서재의 회차에서 **서버에서 새 번역**을 선택하면 검증된 원문과 서버 제공자 목록을 불러옵니다.
Google Web/Google Cloud/DeepSeek/OpenAI 호환 제공자, 목표 언어, 허용된 endpoint/model 및
`원문=번역` 형식의 용어집을 설정할 수 있습니다. 입력한 제공자 키는 서버에 전송되며 앱과 서버 모두
디스크에 저장하지 않습니다. 키를 비우면 서버에 설정된 키를 사용합니다. 작업 시작은 명시적입니다.

`HttpTranslationStore`는 `translationProviders`, `createTranslationJob`, `translationJobs`,
`translationJob`, `cancelTranslationJob` API를 제공합니다. GET은 명시 Basic 헤더를,
변경 요청은 같은 세션의 `/api/v1/csrf` 응답까지 사용합니다. 계정 생성/편집은 `/accounts/csrf`를
사용하며 PATCH는 Android/JVM 호환 OkHttp 전송을 사용합니다. 모든 전송은 redirect를 차단합니다.

작업 화면은 진행 중인 작업을 3초 간격으로 갱신합니다. 취소 요청은 서버에 전달되고, 실패/취소/중단된
작업은 서버가 반환한 설정과 완성된 문단을 이용해 재시도합니다. 재시도 설정은 읽기 전용이며
필요한 API 키만 다시 입력할 수 있습니다. 용어집의 실제 항목 배열을 보존하므로 `=`나 줄바꿈이
포함된 서버 용어도 손실 없이 재전송됩니다. 새 설정이 필요한 경우 원문에서 새 번역을 선택합니다.
완료된 작업만 리더로 열 수 있고, 원문 identity/revision/문단 ID 및 번역 해시를 다시 검증합니다.
연결 해제는 앱의 갱신을 중단하며 이미 서버에 제출한 작업을 취소하지 않습니다.

`ServerAccountHttpTest`와 `ServerAccountViewModelTest`, `ServerTranslationJobsHttpTest`와
`ServerTranslationJobsViewModelTest`는 등록/프로필/PATCH, CSRF, 키 메모리, 재시도 설정 보존 및
진행 조회와 취소 사이의 경쟁을 검증합니다. 모든 목록과 입력 목록은 공통 `AdaptiveCollection`으로
페이지 이동하며, 새 서버 화면도 E-Ink benchmark inventory에 포함됩니다.
