# PDF 원본의 계정별 보관과 전체 내용 재검증

S4b2b1은 PDF 원본을 현재 계정에 불변 사본으로 보관하는 API다. 기존 텍스트 서재·원본 provenance·계정 binding·읽기 위치 및 메모 API와 분리한다. 같은 hash·제목·로컬 ID를 근거로 기존 문서를 합치지 않는다. 이 단위는 앱·웹의 자산 연결 화면이나 PDF 위치 동기화를 활성화하지 않는다.

## 보관 계약

업로드에는 요청 UUID와 version 1의 정확한 언어·ordered 문단·ordered 자산 참조·각 payload의 MIME과 canonical base64를 보낸다. PDF 참조 하나가 전체 원본 파일을 가리키며 그 파일은 한 번만 전송·보관한다. 클라이언트가 계산한 proof는 업로드 권한이나 원본 검증 근거로 받지 않는다. 서버가 `PortableContentProofs.compute`에 실제 bytes를 전달해 전체 내용 증명을 계산한다.

계정과 업로드 UUID가 같은 재시도는 동일한 입력만 허용한다. 다른 내용으로 같은 UUID를 재사용하면 충돌을 반환한다. 동일 내용이라도 새 업로드 UUID는 별도 서버 사본을 만든다. DB transaction으로 metadata와 bytes를 함께 저장하며 실패 시 부분 레코드를 남기지 않는다.

조회·읽기 전용 비교·원본 다운로드는 현재 계정 소유권과 현재 보관 bytes를 다시 확인한다. 저장한 proof JSON 자체만 검사하지 않는다. 다른 계정의 레코드와 없는 레코드는 같은 404로 처리하며 소실·변조는 정상 조회로 표시하지 않는다. 다운로드는 attachment/no-store/nosniff를 적용한다.

## 이번 단위의 한도

- 업로드 HTTP JSON 8 MiB, 고유 decoded payload 합계 4 MiB. PDF 원본도 이 합계에 포함한다. 전체 proof 비교 요청은 2 MiB이며 웹은 proof를 함께 담은 전체 조회 응답을 최대 12 MiB까지만 읽는다.
- payload 64개·ordered 자산 참조 128개·문단 4,096개·metadata 텍스트 합계 262,144 UTF-16 code unit 이내. metadata 합계는 언어·문단 ID와 본문·참조의 비null 문단 ID와 alt를 센다. path와 MIME은 별도로 제한한다. 값을 자르거나 정규화하지 않는다.
- 압축 HTTP 요청·중복/후행/미지원 JSON 필드·비정규 base64·누락/고아 자산·잘못된 Unicode를 거절한다. Content-Length 없는 요청에도 실제 읽은 bytes 한도를 적용한다. ZIP 해제를 수행하지 않는다.
- PDF 해석기나 OCR은 실행하지 않는다. PDF로 표시한 bytes의 보관·증명이며 렌더링 성공이나 안전한 pageCount를 보장하지 않는다. 물리 위치는 이후 실제 decoder의 `VerifiedPdfContext`로 검증해야 한다.
- 모든 최대 크기 PDF의 업로드 지원을 뜻하지 않는다. 큰 파일 transport는 별도 후속이며 EPUB는 웹의 원본 bytes 보존과 ZIP MIME 계약부터 보완해야 한다.

## 검증

2026-10-05, [draft PR #42](https://github.com/gongdongho12/PageTuner/pull/42). base는 `codex/portable-glossary-adoption`(PR #41), head는 `codex/pdf-content-storage`다. 계약 `7d4dea5`, 웹 소비자 `9099d68`, 서버 `3090095`로 나누어 검증·커밋·푸시했다.

- `:core-backup:test` 29개(새 PDF 계약 10개 포함), `:backup-runtime:test` 24개 통과. 별도 Python 생성 fixture의 proof·전체 요청 fingerprint가 Kotlin·웹·서버에서 일치한다. API 23을 위해 공통 Base64는 `java.util.Base64`에 의존하지 않는다.
- 전용 `pagetuner_test_20261005_pdf_storage` PostgreSQL DB에서 `:server:test` 전체 225개 통과. PDF 통합 11개가 계정 격리·동시 동일/서로 다른 재시도·저장 데이터 변조/소실·4 MiB 정확한 경계·초과 rollback·다른 동기화 기록 무변경을 검사한다. `bootJar`·모듈 경계도 통과했다. 첫 실행의 JSON 숫자 노드 타입 비교 오류는 wire JSON으로 비교하도록 테스트만 수정했다.
- 웹 전체 522개/65파일·계약 생성 15개·typecheck·production build 통과. canonical Base64 padding을 할당 전에 검사하고 계정 client 종료/abort·지연 hash/응답·8 MiB를 넘는 유효한 전체 조회 응답도 검증했다. 연결 UI는 이번 단위에 추가하지 않았다.
- Android 523개 통과/15개 외부 opt-in 제외, APK·lint·계측 소스 컴파일·모듈 경계 통과. 실제 장치에서 실행한 결과는 아니다.
- 실제 포트 8082 서버와 격리 DB에 합성 PDF 437 bytes·PNG 69 bytes, 한글/이모지/공백/빈 문단·중복된 ordered 이미지 참조를 저장했다. 원본 bytes 다운로드와 전체 metadata가 일치했고, 별도 Python 계산 proof `3874855c9224c8b3dee1f9dc26800c173b50ca5ab8f0d0d5c82d5c2d70fe29fc`와 같았다.
- 실제 HTTP에서 동일 업로드 재시도는 같은 receipt, 다른 내용의 UUID 재사용은 409, 같은 내용의 새 UUID는 별도 record, 동시 4회 재시도는 한 record였다. 다른 계정의 조회/검증/다운로드는 404, 압축 요청은 415, 중복 JSON은 400, Content-Length 없는 8 MiB 초과 chunked 요청은 413이었다.
- Java 프로세스를 종료한 뒤 같은 DB로 재시작하여 전체 내용·proof·원본 bytes를 다시 비교했다. 이후 해당 QA record의 DB bytes 1개를 변조하자 조회·검증·다운로드·업로드 재시도가 모두 409로 거절됐다. QA 계정의 원문·번역·읽기 위치·메모·분류·용어집·소스 즐겨찾기 8종 테이블은 모두 0건이었다. 미리보기 DB는 사용하지 않았다.

검증 로그와 합성 fixture는 원본 checkout의 ignored `.gradle-home/pdf-storage-*`에 남긴다. 현재 CI에는 이 PostgreSQL 통합 검사가 연결되지 않으므로 로컬 전체 검사와 PR CI를 구분한다. 기존 workflow 권한 제약은 V5에 유지한다.

## 다음 범위

S4b2b2에서 앱·웹이 실제 저장 원본을 재검증하고 계정/client/세대를 확인한 후 별도 명시적 연결을 제공한다. PDF 물리 위치와 텍스트 anchor를 임의 변환하지 않는다. S1/S2/분류 최신 ZIP 통합과 전체 S4도 미완료다. 기존 V1/V2/V3/V5 검증 의존성을 유지한다.

우선 ZIP PDF의 canonical 문서와 payload를 동일한 저장소 읽기에서 준비하고 Android bounded API adapter를 추가한다. `PortableDocumentMapper.native`는 빈 문단 제거·이미지 참조 재구성이 있으므로 기존 ZIP의 손실 없는 builder로 재사용하지 않는다. Android 기본 HTTP 응답 8 MiB를 전역 변경하지 말고 PDF GET에만 12 MiB를 적용한다. 이후 별도 계정/origin/사본/전체 proof binding과 명시적 화면을 연결한다. `PortableServerBindingStore`/`ServerReadingDocument`는 텍스트 전용이므로 PDF record UUID를 넣지 않는다. 새 binding은 ZIP에 넣거나 S1~S3 권한으로 해석하지 않는다. 물리 PDF context는 정확한 bytes를 실제 decoder로 연 결과에서만 만들고 reader의 보정된 pageCount나 ZIP metadata로 추정하지 않는다.
