# PageTurner

기본 프로젝트 문서는 영어입니다.

English: [README.md](README.md)

작업 체크리스트: [TODO.md](TODO.md)

PageTurner는 전자잉크 단말기용 Android 리더 프로토타입입니다. 핵심은 페이지
단위 읽기, 읽는 속도에 맞춘 번역, 번역 결과의 오프라인 재사용입니다. 이
코드베이스는 독립 구현이며, GPL 리더 프로젝트는 제품 참고용으로만 보고 소스
기반으로 사용하지 않는 방향입니다.

## 구현된 기능

### 리더

- Kotlin과 Jetpack Compose 기반 Android 네이티브 앱.
- 전자잉크에 맞춘 고대비 UI.
- 문서 세로 스크롤이 아닌 페이지 단위 읽기 화면.
- 이전/다음 페이지 이동 버튼.
- 좌/우, PageUp/PageDown 계열 하드웨어 키와 D-pad 페이지 넘김.
- 설정 가능한 페이지 넘김 방식:
  - 왼쪽 탭 이전, 오른쪽 탭 다음
  - 왼쪽 탭 다음, 오른쪽 탭 이전
  - 버튼만 사용
- 리더 컨트롤 빠른 숨김/표시.
- 형식, 쪽 수, 진행률, 로컬 파일 크기를 보여주는 문서 상세 다이얼로그.
- Android 문서 선택기를 통한 텍스트/Markdown 파일 열기.
- Android `PdfRenderer` 기반 PDF 열기와 페이지 이미지 보기.
- Android 15+에서는 PDF native 텍스트 추출을 사용해 페이지별 세그먼트로
  매핑하고 번역/검색 흐름에 연결합니다.
- EPUB package OPF spine을 읽어 텍스트 페이지로 정규화하고 목차 항목 생성.
- EPUB 챕터 라벨과 이전/다음 챕터 이동.
- 전자잉크에서 빠르게 읽을 수 있도록 EPUB heading/list 줄바꿈과 이미지
  자리표시자 보존.
- 문서를 페이지와 문단 세그먼트로 정규화하는 내부 모델.
- 가져온 책을 보여주는 로컬 서재 패널.
- 가져온 책은 오프라인에서 다시 열 수 있도록 앱 내부 저장소에 복사됩니다.
- 로컬 메타데이터는 제목, 형식, 파일 경로, 현재 쪽, 전체 쪽 수, 읽기 진행률,
  가져온 시각, 마지막으로 연 시각을 저장합니다.
- 파일 해시로 중복 import를 감지하고 저장된 사본을 다시 엽니다.
- 최근 책은 로컬 서재에서 다시 열거나 삭제할 수 있습니다.
- 앱 시작 시 마지막으로 열었던 저장 책을 자동으로 복원합니다.
- 전자잉크 페이지 넘김을 가볍게 유지하기 위한 현재 페이지만 렌더링.
- 컬러, 그레이스케일, 흑백, 전자잉크 고대비를 위한 Display 모드.
- PDF 렌더링은 선택한 Display 모드를 따릅니다.
- PDF 쪽 맞춤 / 너비 맞춤 설정.
- PDF 이전/현재/다음 쪽 이미지 캐시.
- 리더 글자 크기, 줄간격, 페이지 여백 설정.
- DataStore 기반 영속 설정:
  - Display 모드
  - 페이지 넘김 방식
  - 원문/번역 언어
  - 번역 provider
  - LLM 엔드포인트/모델
  - 읽기 속도
  - 번역 pacing 모드

### 번역

- `TranslationProvider` 인터페이스 뒤에 번역 provider를 분리.
- 현재 provider 옵션:
  - Google Cloud Translation API
  - OpenAI-compatible LLM API
- LLM provider 입력값:
  - API 키
  - chat-completions 호환 엔드포인트
  - 모델명
- provider별 캐시 키 분리로 Google 번역과 LLM 번역 결과가 섞이지 않음.
- 한국어/영어 번역 프리셋:
  - 자동 감지 → 한국어
  - 영어 → 한국어
  - 한국어 → 영어
  - 자동 감지 → 영어
- 읽는 속도 기반 API 호출 pacing.
- 빠른 번역 모드.
- 미리 번역해 저장하는 오프라인 prefetch 모드.
- 앱 내부 저장소의 JSON 번역 캐시.
- 저장된 페이지 번역을 불러오는 오프라인 읽기 흐름.
- 현재 provider/언어 조합 기준 문서별 번역 캐시 상태 표시.
- 현재 문서와 provider/언어 조합의 번역 캐시 삭제 액션.
- 번역 표시 모드:
  - 원문만
  - 번역만
  - 원문과 번역 함께 보기
- API 키 또는 LLM 엔드포인트/모델 누락을 알려주는 provider 설정 상태.

### 로컬라이징

- 영어 UI가 기본입니다.
- Android locale 리소스로 한국어 UI를 지원합니다.
- 사용자에게 보이는 문자열은 아래 파일로 분리되어 있습니다.
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-ko/strings.xml`

### 원격 서재 계획

- 앱 안에 원격 서재 TODO 패널 추가.
- 예정 source:
  - Google Drive
  - FTP / FTPS
  - PageTurner Web Catalog
- 원격 source 규격 초안: [docs/REMOTE_SOURCES_TODO.md](docs/REMOTE_SOURCES_TODO.md)
- 정적 샘플 카탈로그:
  - [examples/pagetuner-catalog/catalog.json](examples/pagetuner-catalog/catalog.json)

### 개발 문서

- 제품 TODO:
  [docs/PRODUCT_TODO.md](docs/PRODUCT_TODO.md)
- 구조 메모:
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- 번역 provider 확장 가이드:
  [docs/TRANSLATION_PROVIDERS.md](docs/TRANSLATION_PROVIDERS.md)
- 원격 서재와 웹 카탈로그 TODO:
  [docs/REMOTE_SOURCES_TODO.md](docs/REMOTE_SOURCES_TODO.md)
- 스캔 PDF OCR 계획:
  [docs/OCR_PLAN.md](docs/OCR_PLAN.md)

## 현재 제한 사항

- PDF native 텍스트 추출은 Android 15+ 플랫폼 API에 의존합니다. 스캔 PDF는
  아직 OCR 구현이 필요합니다.
- EPUB은 텍스트 우선 리더입니다. 복잡한 레이아웃, 실제 임베디드 미디어 렌더링,
  커스텀 폰트, 고급 CSS 렌더링은 아직 지원하지 않습니다.
- Google Drive, FTP, 웹 카탈로그 connector는 아직 TODO/계획 단계입니다.
- API 키는 세션 중 화면에서 입력하며 아직 저장하지 않습니다. 제품화 전에는 더
  안전한 credential storage가 필요합니다.
- LLM provider는 OpenAI-compatible chat completions JSON 형식을 기대합니다.
- 아직 프로젝트 라이선스는 선택하지 않았습니다.

## 빌드

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 추천 다음 작업

1. 설정, 리더, 번역 상태를 ViewModel 경계로 분리.
2. 일시정지/재개/취소가 가능한 오프라인 번역 큐 추가.
3. 로컬 테스트가 쉬운 PageTurner Web Catalog connector부터 구현.
4. 실제 API 키 사용 전 안전한 credential storage 추가.
5. 배포 전 프로젝트 라이선스 선택 및 추가.
