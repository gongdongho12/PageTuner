# ZIP 계정 용어집의 명시적 채택 (S4c2b)

[수동 스냅샷](PORTABLE_GLOSSARY_SNAPSHOTS.md)과 [최신 계정 내보내기](PORTABLE_GLOSSARY_EXPORT.md)에 앱·웹의 별도 채택 화면을 연결한다. ZIP 가져오기·목록 보기·비교 준비는 계정 기록을 만들거나 전송하지 않는다. 현재 계정에 명시적으로 연결한 텍스트 ZIP만 서버 원본을 다시 확인한 뒤 비교할 수 있다.

웹은 `이 기기 보관 → 앱 · 웹 ZIP 교환 → 가져온 책`에서 용어집 채택을 연다. Android는 해당 ZIP의 서버 원본 확인 화면에서 연다. 원래 provider/book/targetLanguage별로 ZIP·서버 조회값·기기 계정 기록을 확인한 다음 별도 최종 확인을 선택한다. 긴 식별자와 용어는 생략하지 않는 필드와 페이지로 확인한다.

## 변경 경계

- 비교 준비는 직접 읽기 전용 조회와 journal 읽기만 사용한다. 기존 sync controller를 시작하거나 기존 대기열을 flush하지 않는다.
- 최종 확인에서 서버 원본·계정/origin/client/세대·명시적 binding을 다시 확인하고 서버값을 재조회한다. 비교한 서버값 또는 기기 base 전체가 달라졌거나 pending/queued/conflict가 있으면 새 비교를 요구한다.
- 웹의 원자적 저장 transaction과 Android의 직렬 actor 명령이 base를 다시 검사한 뒤 새로운 mutation ID와 조회한 expectedVersion으로 outbox를 만든다. 이후 기존 재시도·CAS 충돌 처리로 전송한다. CAS 경쟁에서 서버가 먼저 바뀌면 충돌을 보존하며 자동 덮어쓰지 않는다.
- `absent`는 정보만 표시한다. `deleted`는 별도 삭제 확인으로 null tombstone을 만든다. 현재 서버가 version 0의 absent여도 삭제 의도가 명시되면 새 mutation을 만든다. `present`의 빈 배열은 삭제와 구분한다.
- 최대 500항목의 원래 ID·배열 순서·공백·별칭·종류·활성·대소문자를 그대로 사용한다. legacy/workflow의 ID 재생성이나 200항목 정규화를 거치지 않는다.
- ZIP 원본과 다른 언어 scope는 그대로 보존한다. 미지원 extension과 extensions 전체 256 KiB 초과는 절단하거나 덮어쓰지 않고 거절한다. 계정 정보·자격 증명·CAS/outbox는 ZIP에 추가하지 않는다.

## 검증 기록

- 2026-10-04~05 [draft PR #41](https://github.com/gongdongho12/PageTuner/pull/41), base는 PR #40의 `codex/fresh-glossary-zip-export`다. 웹 `bb62563`, Android `913cb1e`를 검증 후 별도로 커밋·푸시했다. 웹 head `bb62563` CI와 Android head `913cb1e`의 [CI](https://github.com/gongdongho12/PageTuner/actions/runs/37211449440)가 모두 성공했다(후자 3분25초). 이후 화면·문서 변경은 해당 최종 head의 CI에서 확인한다. 자동 병합하지 않는다.
- 웹 506개/64파일, 생성 계약 14개, TypeScript·production·sharing build 통과. 새 22개 검사에는 500항목과 공백/중복 용어·ID 보존, absent/deleted/빈 배열, readonly 준비, 다른 탭의 최초 journal 경쟁, pending/conflict·더 최신 remote, 계정/client/세대·binding 재연결·지연 응답, 단회 확인과 전체 표시 필드를 포함한다.
- Android 523개 통과·기존 opt-in 15개 제외(118 suite), APK·lint·계측 소스 컴파일·모듈 경계 성공. lint 오류 0/경고 88/힌트 4. 신규 adapter 7개와 actor 5개에서 정확한 500항목·삭제·빈 목록, 계정 전환·binding 변경·대기열 보존·저장 오류·중복 확인·재시도/409를 검사했다. 처음 전체 검사에서 새 AdaptiveCollection 화면의 benchmark fixture 누락을 발견해 500항목 전체 3,502행 fixture와 호출 위치 목록을 추가한 뒤 전체를 재통과했다. 계측 실행은 하지 않았다.
- 독립 검토에서 채택한 책 B의 용어집 actor가 기존 리더 A에 남는 문제를 발견했다. 실제 패널 종료·Local 하위 메뉴 전환·리더 전체화면 전환 시 비교 세대를 닫아 원래 리더 대상으로 돌아오도록 수정했다. 웹 저장소 수명·오래된 비교 안내·목적 계정 표시도 수정했으며 최종 정적 검토의 blocking finding은 없다.
- 전용 PostgreSQL `pagetuner_test_20261004_glossary_ui`와 서버 8082·웹 5174에서 실제 파일 선택기로 합성 ZIP을 가져오고 원본 확인·명시적 연결 후 채택했다. 미등록 fr 스냅샷에는 변경 버튼이 없고 서버 version 0을 유지했다. 다른 provider의 de scope는 명시적으로 거절했다. 미지원 version 2 extension은 가져오기/보관은 허용하되 채택을 거절했다.
- 실제 ko 비교 준비 뒤 별도 API에서 서버 version 2→3을 변경했다. 최종 확인은 오래된 비교를 거절했고 새로 비교한 뒤 명시 채택한 경우에만 version 4의 500항목으로 저장됐다. 서버 응답의 전체 ordered entries를 합성 ZIP 예상값과 깊은 동등 비교하여 ID·공백·별칭·종류·활성·대소문자가 모두 같음을 확인했다. 별도 en 삭제 snapshot은 absent→version 1/null, ja 빈 목록은 absent→version 1/[]로 저장됐다. ko 500개와 fr absent는 그대로 유지됐다.
- 390×844와 844×390에서 목록·비교·최종 확인·페이지 이동을 실제 조작했다. textarea의 원래 공백과 화면 회전 후 필드 anchor를 확인했고 문서 scroll 크기는 viewport와 같았다. 닫기 버튼 폭 42.92px를 발견해 채택 화면 버튼 전체의 최소 44×44px를 보장했다. 최종 sharing 자산으로 APK를 다시 묶었고 192개 파일의 SHA-256이 빌드 결과와 모두 일치했다.
- 합성 ZIP/예상값/API 결과와 화면 증거는 원래 작업 폴더 `.gradle-home/glossary-adoption-*`에 보존했다. 미리보기 DB와 원래 작업 폴더의 PR34 기반 미커밋 파일은 사용하거나 변경하지 않았다.

## 남은 범위

S4 전체는 미완료다. S4b2b 자산 원본의 서버 보관·재검증·명시적 연결과 S1/S2/분류 최신 ZIP 통합이 남는다. Android 물리 장치의 SAF·회전/프로세스 재생성·화면 꺼짐·절전은 V3, 유료 API는 V2, 기존 용어집 파일 JSON·조사·강조 화면 회귀는 V1, CI workflow 추가 권한은 V5에 유지한다.
