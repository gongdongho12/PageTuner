# 자산 문서의 전체 내용 증명과 정확한 위치

S4b2a는 S4b2 계정 연결에 앞서는 공통 계약과 Kotlin/웹 검증 단위다. 선행 PR #35 head `54236c48`의 CI 성공을 확인하고 시작했다. 기존 텍스트 provenance identity v1과 계정 연결을 변경하지 않는다.

내용 증명은 원래 파일 전체 bytes, 표시용 문단, 순서가 있는 자산 참조를 함께 묶는다. 제목, ZIP 사본 ID, 로컬 ID, 표시 페이지 수는 대응 기준이 아니다. 원본 provenance·계정 소유권·현재 서버 기록의 동일성 확인은 별도이며 증명만으로 계정 연결을 허가하지 않는다.

TEXT는 자산 없이 정확한 문단을 검증한다. PDF는 원본 파일 전체와 그 파일을 가리키는 PDF 자산 참조가 필요하며, 텍스트가 없는 문서도 물리 페이지 위치를 검증할 수 있다. EPUB는 원본 ZIP 전체 bytes와 표시 본문·이미지 순서를 함께 검증한다. 원본 EPUB 파일이 없는 기존 교환 ZIP은 이 전체 증명을 만들 수 없으므로 지원 완료로 표시하지 않는다.

텍스트 위치는 문단 ID와 UTF-16 offset으로 검증하고 빈 문단·끝 위치를 보존한다. surrogate pair 내부 위치를 거절한다. PDF 물리 위치는 정확한 원본 파일 hash와 0부터 시작하는 페이지 인덱스다. 페이지 수는 실제 원본을 해석한 신뢰할 수 있는 reader가 별도로 제공해야 하며 파일 metadata의 주장으로 추정하지 않는다. PDF 페이지를 본문 위치로, EPUB 화면 페이지를 다른 기기 페이지로 변환하지 않는다.

이번 단위는 네트워크·저장소·UI 경로를 활성화하지 않으며 사용자 기록을 업로드하거나 삭제하지 않는다. 화면 검증 대상인 새 흐름은 없다. 후속 S4b2에서 서버 원본 자산 저장과 읽기 전 재검증, 앱/웹 명시적 연결 UI, 실제 reader 위치 대응을 구현해야 한다. S4c 최신 계정 기록의 ZIP 통합은 별도다.

## 검증 근거

- 공통 core-backup 14개, backup-runtime 16개 통과. 신규 proof 검사에 변조 metadata·byte 변경·Unicode·반복 참조·빈 PDF·정확한 anchor와 Python 생성 공통 벡터 3개를 포함한다.
- Android 475개 통과/기존 opt-in 15개 제외, APK·lint 성공. 기존 ZIP 텍스트 계정 연결과 파일 교환 회귀를 함께 확인했다.
- 웹 443개/60파일 통과, 생성 API 계약 14개·typecheck·production build 성공. Kotlin과 웹의 공유 fixture digest 및 웹 전체 proof 객체가 일치한다.
- 독립 검토에서 proof 자체의 framed digest 재검증, 원본 hash에 묶인 PDF pageCount context, 비동기 입력 사본, 반복 payload metadata 일관성을 보완했다.
- 이번 단위는 새 UI/API를 노출하지 않아 새 화면 조작 검증은 없다. PDF/EPUB 디코딩·실기기·서버 계정 연결을 성공으로 표시하지 않는다. CI는 core-backup 테스트와 웹 verify를 추가해 양쪽 계약 벡터를 함께 실행한다.
