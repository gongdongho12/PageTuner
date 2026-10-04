# /frontend 연동 계약

`/frontend`는 별도 웹 클라이언트 작업 영역이고, `/server`는 공통 JSON API와 선택적으로 빌드된 `/web` 정적 파일을 제공합니다.
Android UI 및 웹소설 공급자 코드를 서버에 의존시키지 않습니다.

## 브라우저 인증

계정 가입·프로필·비밀번호 변경은 공통 `/api/v1/accounts/*` 계약을 사용합니다.
기존 `PAGETUNER_LOCAL_USER` / `PAGETUNER_LOCAL_PASSWORD` 계정의 소유권도 유지합니다.

1. 클라이언트는 자격 증명을 메모리에만 보관하고 매 요청 `Authorization: Basic ...`을 보냅니다.
2. `GET /api/v1/session`은 현재 계정의 `{ "username": "..." }`를 반환합니다.
3. 쓰기 전 `GET /api/v1/csrf`로 `{ "headerName": "X-CSRF-TOKEN", "token": "..." }`을 받고
   같은 세션 쿠키와 반환된 CSRF 헤더를 쓰기 요청에 보냅니다.
4. 연결 해제 시 클라이언트 자격 증명을 지웁니다. 폼 로그인·로그아웃 경로는 제공하지 않습니다.

모든 요청에 `credentials: 'include'`를 사용합니다. 비밀번호를 localStorage에 저장하지 않습니다.
CSRF 쿠키는 인증을 대체하지 않으며 이전 비밀번호는 변경 후 모든 요청에서 거부됩니다.
변경 요청의 CSRF 누락·만료는 403, 잘못된 Basic 인증은 401입니다.
실패한 쓰기 요청은 자동 재전송하지 말고 현재 상태를 확인한 다음 명시적으로 재시도합니다.

개발 기본 허용 origin은 localhost와 127.0.0.1의 3000(Next.js), 5173 포트입니다.
현재 `/frontend`의 Next.js `/api/v1` rewrite를 사용하면 브라우저에서 상대 경로로
호출할 수 있습니다. 이 경우 위 예제의 base를 빈 문자열로 설정하세요.
`PAGETUNER_FRONTEND_ORIGINS`는 쉼표로 구분한 정확한 origin으로 변경할 수 있습니다.
프론트와 서버의 호스트 표기는 일치시키세요(둘 다 localhost 또는 둘 다 127.0.0.1).
운영은 동일 사이트의 `/api` 역방향 프록시를 권장하며 HTTPS에서는
`PAGETUNER_COOKIE_SECURE=true`로 설정합니다. 임의 origin 및 와일드카드는 허용하지 않습니다.

## 서재와 원문 읽기

| Method | `/api/v1` 이하 경로 | 동작 |
| --- | --- | --- |
| POST | `/library/books` | 원문 책·챕터 일괄 등록(201) |
| GET | `/library/books?query=&page=0&size=20` | 제목·저자 검색, 등록일 최신순 |
| GET | `/library/books/{bookId}` | 책 정보 |
| GET | `/library/books/{bookId}/chapters?page=0&size=20` | 챕터 순서대로 목록 |
| GET | `/library/books/{bookId}/chapters/{chapterId}` | 원문 문단 및 sourceRevision |
| GET / PUT | `/library/books/{bookId}/progress` | 읽기 위치 조회·저장 |
| GET / POST | `/library/books/{bookId}/bookmarks` | 책갈피 목록·추가 |
| DELETE | `/library/books/{bookId}/bookmarks/{bookmarkId}` | 책갈피 삭제(204) |

위 책 단위 목록의 페이지 번호는 0부터 시작하고 size는 1–100입니다. 반환 형식은
`{ items, page, size, totalItems, totalPages }`입니다. 없는 항목 및 다른 소유자의
항목은 404입니다. 제목·본문을 HTML로 해석하지 말고 텍스트로 렌더링하세요.

등록 예시:

```json
{
  "title": "첫 번째 책",
  "author": "작가",
  "sourceLanguage": "ko",
  "chapters": [{
    "title": "1장",
    "paragraphs": [
      { "paragraphId": "p1", "text": "첫 번째 문단입니다." },
      { "paragraphId": "p2", "text": "두 번째 문단입니다." }
    ]
  }]
}
```

반환: `{ id, title, author, sourceLanguage, chapterCount, createdAt }`.
책과 챕터 ID는 서버가 UUID로 생성합니다. 원문은 불변 snapshot으로 저장합니다.
등록 POST를 반복하면 별도 책을 만들므로 클라이언트에서 이중 제출을 방지하세요.
TXT/EPUB/PDF 바이너리 파싱은 이 API에 포함되지 않습니다. 파싱된 문단 JSON을 받습니다.
책은 최대 500챕터·본문 합계 500만 UTF-16 코드 단위, 챕터는 최대 10,000문단입니다.

챕터 목록 항목: `{ id, ordinal, title, sourceRevision }` (`ordinal`은 0부터).
챕터 상세: `{ id, bookId, ordinal, title, contentProviderId: "library", sourceLanguage,
sourceRevision, paragraphs: [{ paragraphId, text }] }`.

## 읽기 위치와 책갈피

위치는 원문 문단을 기준으로 합니다. 페이지 번호는 화면 크기와 글꼴에 따라 달라지므로
서버에 저장하지 않습니다. `characterOffset`은 문단 내 UTF-16 코드 단위 위치입니다
(JavaScript의 문자열 인덱스와 동일). 챕터·문단 소속과 문자 범위를 서버에서 검사합니다.

최초 GET progress는 `{ "anchor": null, "version": 0, "updatedAt": null }`입니다.
PUT에는 마지막으로 조회·저장한 version을 넣습니다.

```json
{
  "anchor": { "chapterId": "CHAPTER_UUID", "paragraphId": "p1", "characterOffset": 3 },
  "version": 0
}
```

성공 시 `{ anchor, version: 1, updatedAt }`. 오래된 version은 409입니다.
409 응답에서는 GET으로 다른 기기의 위치를 확인한 뒤 이어 읽기 또는 현재 위치 저장을
선택하도록 하세요. 클라이언트도 진행률 쓰기를 순서대로 보내야 합니다.

책갈피 추가: `{ "anchor": { ... }, "note": "다시 읽기" }`.
반환: `{ id, anchor, note, createdAt }`. note는 최대 2,000자입니다.
책갈피 목록도 `page`와 `size`를 받습니다.

## 번역과 백업

- `GET /translations?page=0&size=20`: 사용자 번역 목록. 본문은 포함하지 않습니다.
- 선택 필터: `contentProviderId`와 `bookId`(항상 함께), `chapterId`, `sourceRevision`,
  `sourceLanguage`, `targetLanguage`. 정렬은 생성 시각 최신순이며 동일 시각에는 레코드 ID로 정렬합니다.
- `GET /translations/{recordId}`: 저장 번역 문단.
- `POST /translations`: 기존 SaveTranslationRequest로 번역 저장. 새 revision 201, 동일본 200.
- `GET /translations/{recordId}/backup`: 구조화된 JSON 다운로드.
- `POST /translations/restore`: 백업 JSON 검증 후 복원. 새 revision 201, 동일본 200.
- `POST /translations/{recordId}/backup-plans`: 기존 Drive 예약 API. 실제 업로드는 미구현.

서재 챕터 번역에는 `contentProviderId=library`, `bookId=책 UUID`, `chapterId=챕터 UUID`와
챕터 응답의 `sourceRevision`, `sourceLanguage`를 사용합니다. 문단 ID는 원문과 일치시킵니다.
번역 공급자·모델·프롬프트·용어집 revision도 저장합니다. 읽을 때는 sourceRevision까지
필터링하고 동일 문단 ID로 연결하세요. 부분 번역에서 없는 문단은 원문으로 표시합니다.

번역 JSON 백업은 번역본만 복원하며 원문 서재·진행률·책갈피까지 복원하지 않습니다.
원문이 없는 복원본도 `/translations` 목록으로 발견하고 읽을 수 있습니다.
공통 `/translation-jobs`와 `/novels` API는 번역 실행·웹소설 수집을 제공합니다.
이 책 단위 API와 원본 스냅샷·앱 동기화 저장소를 자동 연결하지 않습니다. Drive 자동 업로드는 미구현입니다.

## 검증

`./gradlew -PbuildTarget=server :server:test :server:bootJar verifyModuleBoundaries`

`WebApiIntegrationTest`는 Docker의 격리된 PostgreSQL 17 또는 명시적으로 설정한
일회용 로컬 PostgreSQL에서 Flyway 마이그레이션, Basic 인증·CSRF, CORS, 서재 등록·조회,
소유자 격리, 진행률 충돌, 책갈피 및 번역 저장·백업·중복 복원을 검사합니다.
외부 DB 설정은 [README.md](README.md)를 따르며 Docker 부재를 성공이나 자동 skip으로 처리하지 않습니다.

## 통합 경계

책 단위 서재는 V15로 추가되며 기존 V1–V14 마이그레이션은 그대로 유지됩니다.
이 서재의 UUID는 `/chapters` 원본 스냅샷의 UUID와 다른 저장소 식별자입니다.
번역 목록은 1–50개 페이지 크기와 `hasNext`를 사용합니다. 원래 공급자/책 ID는 함께 필터링하고
언어를 포함한 모든 지정 식별자는 정확히 일치해야 합니다. 조직 필터와 동시에 사용할 수 있습니다.
원본 메타데이터 없는 legacy 번역의 백업 내보내기는 409로 거부합니다.
