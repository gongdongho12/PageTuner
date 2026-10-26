# 실제 서버의 분류 채택·ZIP PDF 연결 화면 검증

2026-10-25 준비, **2026-10-26 실제 Chrome UI/HTTP/PostgreSQL** 실행. PR #63의 production 웹/JAR에서 PR #58 분류 화면과 기존 ZIP PDF 보관·연결 흐름을 확인한 뒤, 발견한 PDF 렌더 반복을 웹에서 수정·재검증했다. 모의 API를 주입하지 않았다. 서버 production/DB schema는 바꾸지 않았으며 원본 PR34 변경과 기존 미리보기 자료를 보존했다.

## 격리와 자료

승인된 QA cluster의 `127.0.0.1:55433/pagetuner_qa_live_20261005`만 사용했다. 새 합성 QA 계정2개와 원문3개, PDF1개를 만들었으며 QA DB에 테스트 초기화/테이블 삭제를 하지 않았다. 파괴적 테스트용 `pagetuner_test_live_20261005`와 기존 미리보기 DB는 건드리지 않았다. 암호는 ignored 파일에서 메모리로만 읽고 로그/저장소/PR에 넣지 않았다.

최신 production 웹을 build하고 `:server:bootJar`에 포함했다. 실제 웹 진입은 **`http://127.0.0.1:8080/`**다. 이전 서버 문서의 `/web` 안내는 현재 public route가 아니며401이었다. 개발5174는 시작하지 않았다. 이번에 시작한 QA 서버와 cluster는 검증 후 종료했다. 최초 cluster 시작에 port 옵션이 빠져 잠시 기본 localhost5432로 시작한 것을 확인하고, 같은 QA cluster만 정상 종료한 뒤 명시 `-h 127.0.0.1 -p 55433`로 재시작했다. 기존 별도 DB를 시작/중단하지 않았다.

파일 선택은 처음 Chrome ChatGPT 확장의 파일 URL 권한에서 거절됐다. 사용자가 직접 권한을 켰다고 응답한 뒤 같은 정상 file chooser로 재개했다. 다른 업로드 수단이나 브라우저 보안 우회는 사용하지 않았다.

## 분류: 비교와 확정의 분리

production codec으로 생성한 ZIP을 실제 파일 선택 → 가져오기 → 기기 보관함에서 서버 원본 전체 확인 → **이 문서를 계정 기록에 연결**로 명시 연결했다. 가져오기나 연결만으로 분류를 채택하지 않았다.

| 사례 | 실제 UI와 독립 결과 |
| --- | --- |
| present | 계정 정보 채택→ZIP 분류→원본 확인 후 비교. 독립 GET에서 v0/null 유지. 별도 채택 확인→최종 확정 뒤 GET v1. 폴더 `읽을  책 é`, ordered tags `é`, `é`, `Tag`, `tag`, `中  文`과 favorite=true가 전체 동등했다. |
| present-empty | 비교/최종 확인 전 v0/null. 별도 확정 후 v1/폴더빈값·태그빈목록·favorite=false. 같은 빈 화면값이어도 새 저장 의도였다. |
| absent | 비교 후 정보 전용 안내, 채택 확정 버튼 없음. 독립 GET v0/null, DB 행 없음 유지. |

독립 readonly PostgreSQL 조회에서 분류는 정확히2행, 둘 다 v1/expectedVersion0, 서로 다른 새 mutation UUID이며 전체 JSON이 ZIP의 기대값과 일치했다. 세 원문의 분류를 다른 QA 계정으로 GET하면 모두404였다. 비교 직후 GET/DB의 **상태 불변**을 확인한 것이며 별도 요청 로그 없이 HTTP PUT0회라고 주장하지 않는다. UI의 완료는 기기 durable 의도이고, 여기서 추가 독립 GET/DB로 서버 반영까지 확인했다.

ZIP은 각500항목 용어집/다른 언어 scope·legacy 분류·unknown sibling을 담았다. 이번 실서버 검증은 분류 경로이며500항목 전체 보존은 S1 및 기존 ZIP 자동/교차 검증과 구분한다.

## PDF: 업로드·연결·읽기·해제

실제 file chooser로 원본 PDF를 포함한 ZIP 가져오기 → PDF 서버 보관·연결 → 전체 내용 확인 → **새 서버 사본 보관** → **서버에 새 사본 보관**을 선택했다. 업로드가 끝난 뒤 별도 **이 기기 연결 확정**이 필요했다. 서버 확인 후 **내용 재검증 후 읽기**에서 실제 PDF.js 한 페이지와 본문 `Portable library fixture`가 보였다.

독립 HTTP에서 metadata/original200, 원본 **598bytes**, SHA-256 `646afc0abe8b35d35cdf6c4ed88a4fcafe5a956225be324c5e082915ae7d4ccd` 전체 동등성을 확인했다. PDF UUID는 `04b36bad-2247-49cf-a3df-73e9c486f58d`, 두 QA 계정의 합계 snapshot은 소유 계정1행이었다. 다른 계정의 metadata와 original은 모두404였다.

그 뒤 **이 기기 연결 해제→연결 해제 확정**을 실행했다. UI는 기기/서버 사본 보존을 알렸고 보관함의 PDF 행도 남았다. 해제 후 독립 GET200·동일 원본 hash를 다시 확인했다. 로컬 raw tombstone 저장소를 직접 열어 확인한 것은 아니다. PDF UUID로 text S1/S2 API를 사용하지 않았다.

## 근거와 남은 범위

### 실제 화면에서 발견한 PDF 렌더 반복 수정

`showReadingTools=false`의 ZIP PDF에서 busy가 해제될 때 footer가 사라져 관찰 중인 viewport가 커지고, ResizeObserver가 다시 렌더를 시작하면서 busy/footer가 돌아오는 반복을 발견했다. 화면은 보였지만 로딩 안내가 계속 남았다. 로딩 status를 viewport 내부 absolute overlay로 옮기고 일반 footer를 busy와 독립시켰다. canvas만 aria-busy이며 status는 그 밖에서 알리므로 로딩 안내가 busy 영역에 갇히지 않는다. 기존 원본/세대/취소 proof는 바꾸지 않았다.

독립 코드 검토 후 production을 다시 build/JAR로 올렸다. 기존 검증 탭을 모두 닫는 정상 worker 업데이트 절차로 `/assets/cloud-xRrJenSk.js`를 확인하고 재로그인→같은 기기 PDF 재열기를 했다. 두 연속 관찰에서 canvas aria-busy=false·로딩 status 없음·canvas2829×1415와 viewport3278×1421이 동일했다. 너비 맞춤(아래 이동 제어로 viewport높이1377)→페이지 맞춤(1421 복귀)도 각각 busy=false로 종료됐다. `pdf-render-fixed-evidence.json`·`pdf-render-fixed.png`에 기록했다. 사용자의 당시 실제 viewport 근거이며 새390×844/844×390 검증을 뜻하지 않는다. 웹 기존83파일808개 및 S1포함84파일828개·typecheck/production build가 통과했다. 전자는 마지막 접근성 수정 뒤, 후자는 S1/PDF layout 수정 후 실행했다. 마지막 전체 CI에서도 같은 최종 head를 검사한다.

원본 저장소의 ignored `.gradle-home/live-exchange-20261025/`에 합성 fixture/준비 script, 단계별 `*-readback.json`, 강화된 `readback.mjs`의 실제 assert와 `final-readback.json`, 서버 로그 및 화면 `organization-durable-success.png`, `organization-absent-no-confirm.png`, `pdf-linked-success.png`, `pdf-rendered-page.png`, `pdf-unlinked-success.png`를 보존했다. 독립 검토자가 실제 DB readonly 조회와 JSON 동등성/새 mutation을 재확인했다.

이번은 로컬 실제 소켓·Spring·PostgreSQL·Chrome production UI 근거다. 타 origin 로그인 이동·네트워크 단절·모든 소스의 상시 가용성·Android Compose/SAF/회전/재생성/절전·생성 PDF의 OS 저장·다양한 CJK 글꼴/페이지 시각 검사는 아니다. PDF Blob 인쇄 보안 차단과 workflow 권한 V5는 우회하지 않았다. 전체 S4와 최신 S1/S2 ZIP 통합은 계속 미완료다.
