# ZIP 문서의 원본 식별과 서버 동일성 확인

작업 큐 S4a. 2026-10-03 구현·로컬 및 실제 웹 검증 완료. S4b 연결과 S4c 최신 계정 기록 ZIP 통합은 남아 있으며, S4 전체의 완료와 구분한다. [draft PR #33](https://github.com/gongdongho12/PageTuner/pull/33)의 base는 PR #32다.

## 범위

서버에서 보관한 텍스트 원문·번역본의 ZIP에 공통 `extensions.documentIdentity` v1을 넣는다. 원래 소스 provider/book/chapter, 원문 revision·언어, 번역 제공자/설정과 artifact/revision/payload hash를 유지한다. 현재 문서의 전체 문단 ID·순서·정확한 본문은 길이로 구분한 UTF-8 프레임의 SHA-256으로 확인한다.

ZIP의 파일 SHA-256은 파일 무결성을, 문단 hash는 본문 표현의 동일성을 검사한다. 제목, 서버 UUID, Android 로컬 bookId, ZIP package hash나 화면 쪽 번호는 원본 책 identity로 사용하지 않는다. 기존 서버 UUID는 확인할 레코드의 힌트일 뿐이며 현재 로그인한 계정의 소유권을 별도로 검사한다.

앱과 웹의 가져온 책 화면에서 직접 서버 문서 확인을 요청한다. 서버는 인증·CSRF가 적용된 읽기 전용 API에서 전체 identity와 자신이 보관한 본문 digest를 비교한다. 이 요청은 기록·진행 위치·분류·용어집을 수정하거나 새 문서를 업로드하지 않는다. 성공 결과도 영속 동기화 연결을 만들지 않는다.

## 보존과 미지원 범위

- 기존 ZIP은 계속 읽고 다시 내보낼 수 있다. 공통 identity가 없거나 손상되면 확인 기능에서 이유를 설명한다.
- 미지원 passive extension은 그대로 보존한다. 새 identity 계약에 서버 URL·자격 증명·CAS/outbox mutation을 넣지 않는다.
- PDF/이미지 asset이 있는 문서의 물리 페이지·본문 anchor 대응은 S4b에 남긴다. 자체 기기 파일에는 출처를 추론해 넣지 않는다.
- 이미 지원하는 기기/ZIP 기록 교환과 최신 계정 동기화 저장소의 기록 통합은 다르다. 현재 S1~S3 위치·메모·분류·용어집을 ZIP에 합치는 작업은 S4c다.

## 검증 기록

- 공통 `core-backup` 6개, `backup-runtime` 16개, 격리 PostgreSQL 서버 185개/29 suite, 웹 416개/56파일·생성 계약 14개, Android 469개 통과/기존 opt-in 15개 제외(111 suite). APK·lint·계측 소스 컴파일·모듈 경계 성공. lint 오류 0/경고 79/힌트 4. APK 공유 웹 자산 189개/4,671,706 bytes가 `web/dist-sharing`와 SHA-256까지 일치했다.
- fixture는 원래 ID의 구분자·Unicode·공백·빈 옵션, 원문/번역 variant·언어·문단 순서·정확한 본문을 검사한다. ZIP codec의 binary64 숫자 정규화 때문에 `version=1`을 거절하던 문제를 고치고 실제 ZIP write→read 회귀를 추가했다.
- 서버 테스트는 인증/CSRF·중복/후행/잘못된 JSON·64KiB body·소유권/없는 문서·모든 identity 필드·손상된 JSON/문단/revision·legacy 원본 메타 누락을 검증했다. 원문 저장소의 Spring 예외 변환을 피하기 위해 내용 손상만 전용 예외로 구분한다. SQL/DB 오류는 숨기지 않고 그대로 전달한다. 확인 요청이 위치·메모·분류·용어집 행을 만들지 않는 것도 검사했다.
- 독립 8081/격리 DB의 실제 HTTP에서 원문·합성 저장 번역 각 2문단의 성공, 다른 identity의 409, 없는/잘못된 종류 UUID의 404를 확인했다. 이 검사는 번역 제공자 실호출 검증이 아니다.
- 실제 웹에서 서버 번역을 기기에 보관→ZIP 다운로드→다시 가져오기→서버 확인 성공, 대응 원문 보관→원문/읽은 ZIP 재내보내기를 확인했다. 기존 원문 `ordinal`이 ZIP 문단 필드에 섞여 내보내기에 실패하던 결함을 수정했다. 다운로드한 두 문서의 identity/문단은 서버 fixture와 정확히 일치했다.
- 실제 다운로드 ZIP을 앱과 같은 공통 JVM runtime으로 read→write→read하여 전체 문서·metadata·assets·생성 시간을 비교했다. 그 재출력 ZIP을 실제 웹에서 가져와 원문·번역 모두 서버 확인에 성공했다. 이는 Android 공통 runtime 왕복이며 실기기 UI 검증은 아니다.
- 원본 정보 없는 legacy ZIP은 가져오기·읽기를 유지하고 확인 버튼을 비활성화했다. 본문만 변경하고 ZIP 체크섬을 다시 계산한 fixture는 가져오기는 허용하되 identity 확인을 거절했다. 계정/client/문서/UUID 변경과 지연 응답 폐기는 앱·웹 자동 검사 및 독립 검토로 확인했다.
- 실제 390×844 한국어/영어와 844×390 가로 화면의 고정 108px 행·44px 버튼·목록 페이지를 확인했다. 모달/행 overflow는 없었고 단어 중간에 잘리던 영어 상태 문구를 수정했다. 최종 화면은 `.gradle-home/portable-identity-final-ko.png`, `portable-identity-final-en.png`에 보존했다. 테스트 탭과 8081 서버는 종료했다.
- Android 연결 장치가 없어 물리 기기/핫스팟/Doze 검증은 V3에 남긴다. S4b/c 없이 확인 성공을 자동 동기화 완료로 표시하지 않는다.

## 다음 단계에서 해결할 경계

- S4b는 확인 결과와 별도로 계정·서버 origin·로컬 문서의 명시적 연결을 저장해야 한다. 기존 `readingNoteStore.bind`는 최초 연결에 로컬 메모를 전송하므로 확인 성공에 바로 호출하지 않는다.
- 웹 `contentId`는 메모·분류를 포함한 문서 JSON hash다. 메타데이터가 바뀐 ZIP을 같은 원본으로 취급할 때에는 새 원본 identity와 문서 사본 ID를 구분한다. 기존 `migrateDocument`의 이전 행 삭제를 곧바로 재사용하지 않는다.
- ZIP anchor는 문단 끝과 빈 문단의 offset을 허용하지만 계정 메모 API의 범위는 다르다. 페이지나 가까운 문단으로 옮기지 말고 지원 가능한 위치·범위를 명시해야 한다.
- 기존 원문 저장소 dedup 키는 계정/provider/book/chapter/revision이며 언어를 포함하지 않는다. 같은 본문을 다른 언어로 업로드할 때의 정책은 S4b에서 결정한다. S4a는 실제 저장 행의 언어까지 비교하여 다르면 거절한다.
- S4c는 최신 S1~S3 저장소와 전체 ID·순서·별칭·언어 범위를 가진 용어집을 읽어야 한다. 기존 legacy 위치·메모·용어집 export를 최신 계정 기록 통합으로 간주하지 않는다.
