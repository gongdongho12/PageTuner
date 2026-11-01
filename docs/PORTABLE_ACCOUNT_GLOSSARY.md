# S4c1 최신 계정 용어집 ZIP snapshot

2026-10-03. 기준 PR #37 최종 `74e83cd9`의 CI 성공 이후 구현한 S4c의 첫 단위다. [공통 계약](../contracts/account-glossary-snapshot-v1.md)을 따른다.

## 사용자 동작

웹 이 기기 보관 → 앱 · 웹 ZIP 교환 → ZIP 내보내기에서 책을 선택하고 **최신 계정 용어집과 함께 내보내기**를 누른다. 원문은 현재 번역 대상 언어를 표시하고 번역본은 문서의 실제 대상 언어를 사용한다. Android Local → ZIP의 원본 확인 패널에도 별도 최신 계정 용어집 내보내기 버튼과 원문 대상 언어 입력을 제공한다. 기존 일반 ZIP 내보내기는 독립적으로 유지한다.

현재 계정이 소유한 서버 문서와 전체 원본 identity를 다시 확인하고 실제 용어집 query 결과만 담는다. HTTP transport는 기존 읽기 전용 POST `/api/v1/book-glossary/query`다. 기기 캐시·대기/충돌 초안을 최신 서버 값으로 대체하지 않는다. 계정·클라이언트·대상 언어·문서가 바뀌면 늦은 응답과 저장을 폐기한다. 조회 실패, 알려진 extension 변조, 전체 크기 초과는 실패로 표시하며 일부 항목만 잘라서 내보내지 않는다.

`extensions.accountGlossarySnapshots`는 passive metadata다. 원래 항목 ID·순서·source/translated/displayTerm·kind·caseSensitive·enabled와 언어 범위를 보존한다. PRESENT 빈 배열, ABSENT 미저장, DELETED 삭제를 구분한다. 같은 언어 범위만 제자리 교체하고 다른 범위의 순서를 보존한다. CAS version·mutation/outbox·인증 정보는 내보내지 않는다. 가져오기만으로 계정 용어집을 생성/삭제/연결하지 않는다. 기기 메모·위치·분류·기존 ID 없는 glossary·다른 passive extension은 보존한다.

## 자동 검증

- Kotlin core-backup 16개, backup-runtime 19개 통과. 공유 fixture를 실제 ZIP write/read/reexport로 왕복하며 strict shape·16범위·전체 merged extension 256KiB 한도를 확인했다.
- Android 단위 테스트 478개 통과, 기존 외부 opt-in 15개 제외. 실제 export helper의 전체 identity 확인·실제 query·지연 계정 변경 폐기·조회 실패·metadata 보존을 검증했다. APK와 lint 성공(오류 0, 경고 80, 힌트 5).
- 웹 450개/61파일·생성 API 계약 14개·typecheck·production build 성공. 실제 export choice와 query client → ZIP → import/reexport, 순서/ID, null/빈 배열, 변조·초과·abort를 확인했다. 기존 내보내기의 notes.list가 계정 bind/outbox를 만들던 부수 효과도 제거했다.
- 독립 읽기 전용 검토 완료. Android의 suspending 문서 재조회 이후 계정 guard, 웹/Kotlin snapshot 제자리 교체 순서를 보완했다. CI에 backup-runtime 테스트를 포함했다.

## 실제 웹 확인

별도 Vite 5174에서 기존 8081 합성 테스트 서버와 연결했다. 새 고유 uploaded-document 책과 ko 용어집 3개를 만들고 ZIP을 UI에서 가져왔다. 별도 최신 계정 버튼으로 받은 실제 다운로드 ZIP을 원본과 대조했다. SHA-256/manifest 검증, 서버 query와 exact snapshot equality, 기기 메모·읽던 위치·폴더·legacy glossary·source identity·vendor extension equality 모두 성공했다. 별칭 앞뒤 공백, disabled Place, 중복 River sourceTerm의 서로 다른 ID/종류와 순서를 보존했다. 390×844 화면에서 선택과 두 내보내기 버튼이 표시됨을 확인했다. 스크린샷 `.gradle-home/s4c-account-glossary-mobile.png`, 다운로드 사본 `.gradle-home/s4c-live-web-export.ptlibrary.zip`은 로컬 검증 산출물이며 커밋하지 않는다.

이번 실행에서 연결된 Android emulator가 없어 APK 설치와 Android 실제 저장 chooser 화면을 검증하지 않았다. 성공한 JVM helper/codec 테스트와 APK 빌드를 기기 화면 성공으로 세지 않는다. 실제 폰 Chrome 볼륨 버튼 전달/터치와 유료 번역 API 검증도 기존 미검증 상태다.

## 남은 범위

최신 계정 위치·메모·분류 snapshot과 계정 저장소로의 명시적 가져오기 선택은 후속 S4c다. 자산 문서 전체 원본 보관·proof 재검증·실제 연결 UI는 S4b2 후속이다. 이번 용어집 snapshot 보존을 S4c 전체 완료로 표시하지 않는다.
