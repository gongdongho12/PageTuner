# 계정 용어집의 수동 ZIP 스냅샷 계약

S4c의 첫 공통 단위다. `extensions.bookGlossarySnapshots` v1은 원래 provider/book/targetLanguage와 용어집 항목의 ID·순서·정확한 텍스트·표시 별칭·종류·활성·대소문자 설정을 보존한다. Kotlin core와 웹은 같은 fixture를 사용하고 JVM runtime이 ZIP JSON을 읽고 쓴다. [계약](../contracts/book-glossary-snapshots-v1.md).

기존 `glossary` 배열은 계속 보존하며 새 스냅샷과 임의 병합하지 않는다. 미등록(`absent`), 저장된 삭제(`deleted`), 실제 빈 목록(`present` + `[]`)을 구분한다. 가져오기만으로 용어집이 변경되거나 삭제되지 않으며, 계정 ID·서버 주소·인증 정보·CAS version·outbox mutation을 옮기지 않는다.

이 단위에는 최신 서버 GET·화면의 내보내기/채택·S1/S2/S3 분류 기록 통합이 없다. 스냅샷의 출처와 현재 계정 소유권·최신성은 별도 검증 대상이다. S4c 전체와 앱/웹 최신 계정 용어집 내보내기가 완료됐다는 뜻이 아니다.

기존 ZIP extensions 전체 256 KiB 제한을 유지한다. 500항목/100개 scope 이내여도 전체 JSON이 한도를 넘으면 명시적으로 거절하며 항목을 자르지 않는다. 모든 최대 크기 계정 용어집을 담을 수 있는 더 큰 교환 형식은 후속 결정이 필요하다.

## 검증

- 2026-10-04 [draft PR #39](https://github.com/gongdongho12/PageTuner/pull/39), base는 Android 위치 보존 [PR #38](https://github.com/gongdongho12/PageTuner/pull/38)이다. #38 최종 `b996a36` CI 성공(4분48초)을 확인했다. 자동 병합하지 않는다.
- 공통 모델 15개, core-backup 19개, backup-runtime 24개가 통과했다. 신규 계약/JSON 검사 13개에 모든 필드·4언어·3presence, 중복 scope/ID/key, 숫자/타입 강제 변환, 정확한 공백·Unicode, 크기 초과와 sibling 보존을 포함한다. 모듈 경계 검사를 통과했다.
- 웹 453개/61파일, 생성 API 계약 14개, TypeScript·production/sharing build 성공. 신규 10개 검사에는 입력/결과 간 변경 격리, unknown extension 재출력, combined 256 KiB 경계와 ZIP 왕복이 포함된다.
- 웹의 실제 codec으로 만든 ZIP을 공통 JVM runtime에서 read→write→read한 다음 웹 codec으로 다시 읽었다. 전체 문서와 ordered scope/entries, 기존 documentIdentity 및 다른 속성이 같음을 확인했다. 합성 파일 `.gradle-home/glossary-web.zip`, `glossary-jvm.zip`은 원래 작업 폴더에 보존했다. 실제 Android 기기 UI 검증을 뜻하지 않는다.
- 최종 Android 480개 통과·기존 opt-in 15개 제외(112 suite), APK·lint·계측 소스 컴파일 성공. lint 오류 0/경고 80/힌트 4. 새 UI나 서버 API 변경은 없으며 미리보기 DB를 사용하지 않았다.
- 독립 검토에서 sibling의 malformed Unicode가 UTF-8 변환 중 치환될 수 있는 경계를 발견해 실제/escaped 값·키·중첩 배열 5사례를 추가하고 명시적으로 거절했다. 최종 검토에 남은 blocking finding은 없다.
- CI에 backup-runtime 및 모듈 경계 task를 추가하려던 푸시는 현재 OAuth 인증의 `workflow` 권한 부족으로 거절됐다. 미게시 커밋에서 CI 변경만 분리한 뒤 기능 코드는 정상 푸시했다. 추가 CI patch는 원래 작업 폴더의 `.gradle-home/portable-glossary-ci.patch`에 보존했고 V5에 남긴다. 현재 PR CI와 로컬 전체 검증을 구분한다.

## 다음 연결 단위

S4c2는 선택한 원래 provider/book/targetLanguage에 대해 실제 최신 계정 GET을 하고, 계정/origin/client가 바뀐 지연 응답을 폐기하는 export adapter와 앱/웹 화면이다. 캐시·pending·충돌 후보를 최신 서버 값으로 표시하지 않는다. 용어집을 적용할 때에는 별도 명시적 채택과 대상 identity 확인이 필요하며, 삭제 snapshot을 가져왔다고 서버를 삭제하지 않는다. 기존 256 KiB 한도를 넘는 계정 snapshot은 이유를 알리고 데이터 전체를 보존해야 한다. S1/S2/분류 기록 통합과 자산 문서의 실제 서버 보관·연결도 남아 있다.
