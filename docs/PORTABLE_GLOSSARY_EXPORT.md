# 최신 계정 용어집 ZIP 내보내기 (S4c2a)

[PR #40](https://github.com/gongdongho12/PageTuner/pull/40)은 [수동 스냅샷 계약](PORTABLE_GLOSSARY_SNAPSHOTS.md)에 실제 계정 조회와 앱·웹 내보내기를 연결한다. 기본 ZIP 내보내기는 기존 동작을 유지하며 최신 계정 용어집 포함은 사용자가 명시적으로 선택한다. 원래 provider/book/targetLanguage, ordered 항목 ID·공백·별칭·종류·활성·대소문자를 보존한다.

웹은 `이 기기 보관 → 앱 · 웹 ZIP 교환 → ZIP 내보내기 → 내보내기 설정`에서 언어와 포함 여부를 선택한다. 기기에 저장한 서버 문서는 전체 identity를 다시 검증한다. ZIP으로 가져온 사본은 기존 계정 binding도 필요하며, 인코딩 뒤 binding을 다시 확인한다. Android는 ZIP 책의 서버 원본 확인 패널에서 명시적으로 연결된 텍스트 문서만 내보낸다. 번역본은 그 번역본의 정확한 대상 언어를 사용한다. PDF/EPUB 자산의 계정 연결은 이 변경으로 활성화되지 않는다.

최신 조회는 `bookGlossaryApi.get` / `HttpTranslationStore.bookGlossary`의 읽기 전용 `POST /api/v1/book-glossary/query`다. 긴 원본 ID를 URL에 넣지 않는 조회이며 PUT이 아니다. export 경로는 sync controller를 시작하거나 outbox를 flush하지 않고 journal을 읽기만 한다. 대기 변경·충돌, 조회 이후 더 최신이라고 알려진 remote 값은 출력 전에 거절한다. 전체 계정이 변경 불가능한 시점의 전역 스냅샷을 보장한다는 뜻은 아니다.

웹은 계정·origin·client·작업 세대를 각 비동기 경계와 다운로드 직전에 확인한다. Android는 메모리 UUID 요청 티켓으로 prepared export와 SAF 콜백을 연결하며, 계정/client 변경·다음 요청·취소가 이전 티켓을 무효화한다. 파일 공급자를 열기 전과 연 뒤 실제 쓰기 전에 다시 검사한다. 오래된 콜백이 새 요청의 bytes를 저장할 수 없다. 공급자를 연 동안 상태가 바뀌면 bytes 쓰기는 거절되지만 공급자가 이미 빈 목적 파일을 만들었을 수 있다.

`absent`, `deleted`, `present`의 빈/비어 있지 않은 목록을 구분한다. 기존 legacy glossary·다른 언어 scope·sibling extension은 그대로 보존한다. 미지원 스냅샷을 덮어쓰지 않고 전체 extensions 256 KiB를 넘으면 내용을 자르지 않고 거절한다. 자격 증명·계정 ID·CAS version·outbox mutation은 스냅샷에 넣지 않는다. 모든 최대 크기 계정 데이터를 한 ZIP으로 옮길 수 있다는 뜻은 아니다.

## 검증

- 웹 484개/62파일, 생성 API 계약 14개, TypeScript, production/sharing build 통과. 새 31개에는 계정/client 종료, 지연 load/verify/GET/encode, 다중 scope 중 앞선 scope의 충돌, unsupported version·합산 크기 한도, 재연결과 binding 재확인, 읽기 전용 journal 경로를 포함한다.
- Android 511개 통과·기존 opt-in 15개 제외(116 suite), APK·lint·계측 소스 컴파일·모듈 경계 검사 성공. 새 14개에서 실제 HTTP adapter의 검증/조회 호출과 세 가지 presence, ID·순서·공백, pending/conflict, 늦은 remote, SAF 오래된/중복 콜백·파일 공급자 대기를 확인했다. 새 테스트 fixture의 Kotlin 순환 타입 추론 오류는 명시적 타입으로 수정한 뒤 전체 검사를 통과했다.
- 독립 검토에서 GET 후 더 최신 remote가 생긴 Android 경계를 발견해 최종 저장 guard와 회귀 검사로 수정했다. 남은 blocking finding은 없다.
- 격리 PostgreSQL `pagetuner_test_20261004_glossary_ui`, 전용 서버 8082와 웹 5174에서 실제 UI 내보내기 ZIP을 읽었다. 서버 version 1의 두 항목 ID/순서/공백/별칭/종류/불리언을 확인하고, 별도 API로 별칭을 version 2로 바꾼 뒤 같은 웹 화면에서 다시 내보냈다. 실제 다운로드 파일의 모든 항목이 version 2 응답과 정확히 같았으며 내보내기로 서버 version이 증가하지 않았다. ZIP에는 version/CAS/인증 정보가 없었다.
- 해당 ZIP을 실제 웹 파일 선택기로 가져왔다. 계정 연결이 없는 사본의 최신 내보내기는 안내와 함께 거절했고, 전체 서버 원본 확인 → 명시적 연결 후에는 같은 사본의 최신 내보내기가 성공했다. 합성 ZIP 두 개는 원본 작업 폴더 `.gradle-home/fresh-glossary-browser-v1.zip`, `fresh-glossary-browser-v2.zip`에 보존한다. 미리보기 DB는 사용하지 않았다.
- 390×844와 844×390에서 실제 설정/선택/페이지 버튼을 검증했다. 처음 가로 화면에서 바깥 메뉴가 공간을 차지해 목록을 볼 수 없던 결함을 발견하고 ZIP export를 전체 높이 화면으로 전환했다. 최종 가로 화면에서 책 선택과 설정 입력·다운로드·메뉴 복귀를 확인했다. 44px 이상 버튼을 유지하며 문서 전체 스크롤 크기는 viewport와 같았다. 성공/오류 안내 때문에 목록 공간이 부족하면 안내를 닫고 다시 목록을 이용한다. 최신 sharing 자산으로 APK를 다시 묶었다.

## 남은 범위

S4c2b의 명시적 채택은 별도다. 가져오기만으로 계정 연결·변경·삭제하지 않는다. absent는 적용 명령으로 바꾸지 않으며 deleted/present 채택은 현재 계정의 정확한 원본 scope를 확인하고 별도 사용자 선택·충돌 보존을 적용해야 한다. S1/S2/분류의 최신 ZIP 통합, 실제 자산의 서버 보관·연결도 미완료다. Android 실기기 SAF·회전/프로세스 재생성·핫스팟/절전 검증은 V3에 남긴다. workflow 추가 권한은 V5, 유료 API는 V2, 기존 용어집 JSON/조사/강조 화면 회귀는 V1이다.

후속 구현에서 웹 `adoptLegacy()`는 ID 매핑/재생성 때문에 사용하지 않는다. `update()`와 Android `adopt()`도 기존 대기열 재사용·queued 추가·동일 null의 조기 반환이 있어 그대로 쓰지 않는다. 현재 target·서버 base·기기 base 전체와 pending/queued/conflict 부재를 원자적으로 검사하는 별도 정확한 snapshot 채택 명령이 필요하다. 특히 deleted를 absent 계정(version 0)에 채택할 때는 명시적 null tombstone mutation을 만들어야 한다. 기존 서버 PUT은 이를 지원한다. 500항목 계정 스냅샷을 200항목 workflow 정규화에 통과시키지 않는다.
