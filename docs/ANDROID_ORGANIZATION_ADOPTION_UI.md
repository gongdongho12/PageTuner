# Android ZIP 분류 비교·별도 적용

2026-10-25, [draft PR #63](https://github.com/gongdongho12/PageTuner/pull/63). Base `codex/text-account-lifetime-owner`(PR #62), head `codex/android-organization-adoption`. readonly capture `b127a0f`·canonical adapter `9eca26f`·화면/선택 세션 `13a88fe`·benchmark inventory `0853c47`를 분리했다. PR #59~62 내부 기반을 실제 보관함 진입점과 별도 확인 화면에 연결한 단위다.

## 사용 흐름

계정에 로그인한 뒤 보관함 문서의 연결 확인 화면에서 **분류 비교·적용**을 연다. 문서를 서버 기록에 먼저 명시적으로 연결해야 한다. 비교 버튼은 문서·연결을 확인하고 최신 계정 분류와 기기 기록을 읽는다. 비교만으로 계정 변경 요청이나 새 journal을 만들지 않는다.

문서/ZIP/계정/기기/확정의 다섯 탭에서 전체 identity·폴더·순서 있는 태그·즐겨찾기를 확인한다. **확정 탭의 별도 적용 버튼**을 누르면 ZIP에 담긴 분류를 새 의도로 기기에 저장한다. 부재 snapshot은 정보만 표시하고 적용 버튼이 없다. present-empty는 기존 분류를 비우는 명시적 의도이며 서버 version0에서도 새 mutation이다. 분류에 deleted 상태는 없다.

성공 표시는 기기에 적용 요청을 저장했다는 뜻이다. 서버 ACK를 뜻하지 않으며 import/export만으로 계정을 자동 변경하지 않는다. commit 전에 정상 actor의 account/client/session을 캡처하고, 성공 뒤 아직 유효할 때만 notifyCommitted로 전달한다. 인계가 거절돼도 저장 성공은 유지되고 다음 정상 reload에서 pending을 발견한다. 비교를 위해 editor/open/connect/retry/update/choose를 호출하지 않는다.

## 실제 권한과 저장 경계

`PortableLibraryViewModel.organizationAdoption`은 factory이며 전역 비교 상태를 보관하지 않는다. `PortableOrganizationSelection`은 렌더 당시 authority·문서·계정 표시를 캡처한다. `PortableOrganizationAdoptionWorkspace`의 committed DisposableEffect에서만 terminal session을 활성화한다. 뒤로 가기·Android Back·패널/탭 unmount에서 캡처한 이전 session을 닫고, 이전 cleanup이 새 route를 지우지 않도록 객체를 비교한다. identity 패널의 진입 callback도 기존 committed lease로 보호해 오래된 click이 새 route를 발급하지 못한다.

`PortableOrganizationAdoption`은 reader 저장 완료를 기다린 currentDocument에서 실제 ZIP 문서를 다시 읽고 모든 목록을 분리한다. 본문·메타데이터·outline·notes·position·legacy organization·glossary·raw extensions까지 비교한다. LibraryExchangeCodec·typed LibraryOrganizationSnapshotJson·전체 text identity를 검증하고 자산이 있는 문서를 text 권한으로 열지 않는다. 비교 표시 사본을 바꿔도 실제 private snapshot/command는 바뀌지 않는다.

새 `PortableTextBindingAccess.capture`/binding store capture는 strict v2 raw nonce를 읽기만 한다. v1은 기존 명시적 연결 흐름에서 먼저 확인해야 하며 비교 중 migration/쓰기/삭제하지 않는다. 손상·미지원·64 KiB 초과도 bytes를 보존하고 거절한다. 기존 일반 readSnapshot의 v1 migration은 유지한다.

verifyPortableIdentity와 분류 direct GET은 잠금 밖에서 실행한다. 같은 선택의 PR #59 command를 유지해 서버/journal highwater를 잃지 않는다. 최종 저장은 **ticket.withCurrent → text verified → journal compareAndWrite**, 즉 gate→account→library→binding→journal 순서다. 마지막 guard는 메모리 세대만 확인하며 앞 잠금을 재획득하지 않는다. block 뒤의 재검사·취소·통지 실패로 durable 성공을 rollback으로 바꾸지 않는다.

## 화면과 보존 범위

E-Ink 지침의 AdaptiveCollection·고정128dp×fontScale 행/동일 pager 높이·고대비 border·44dp 이상 버튼을 사용한다. 긴 값은 선택 가능한 read-only field로 제공하며 태그 순서·대소문자·NFC/NFD·유효한 중간 공백을 정규화하지 않는다. 비교/탭/상태 변경은 첫 페이지부터 표시한다. 한·영 리소스46개씩을 제공한다.

ZIP의 본문·다른 언어 용어집·legacy/unknown sibling 확장을 수정하지 않는다. 용어집500항목을200항목 workflow로 줄이지 않는다. 기존 extensions전체256KiB·문서8MiB·ZIP 한도와 binding/계정/origin/자격 증명/CAS/outbox의 ZIP 제외를 유지한다. PDF UUID로 text S1~S3를 열지 않는다.

앱 한 프로세스의 협력 저장소 잠금이며 모든 계정/library/binding/journal/서버의 원자 transaction이 아니다. 동일 bytes/동일 private 시각으로 삭제·재삽입한 영속 세대를 제공하지 않는다. 실제 Compose/실기기 화면 검증과 실제 서버 UI 종단은 별도로 남는다.

## 검증과 후속

전체 앱 **741개 중725통과/16 opt-in 제외**(141 suite), backup-runtime50개·APK/lint/계측 소스 컴파일·모듈 경계가 성공했다(최종1분14초, task 재사용 포함). 초기 구현 head `13a88fe`의 run40201763626도 같은 benchmark inventory 누락으로 실패했으며 `0853c47`에서 수정했다. 문서 포함 최종 head CI는 별도로 대조한다.

대상 **4클래스53개**, 신규22개(adapter13·callback session6·readonly capture3)가 통과했다. 실제 JVM ZIP/binding 파일과 주입 journal/query/production HTTP transport를 사용했다. 비교 읽기 전용·absent/present-empty·같은 값의 새 UUID·nullable base/기한·Unicode/표시 사본 변조·reader 저장 대기·문서 삭제/전체 메타 변경·raw nonce ABA·계정/선택 만료·지연 응답·dirty journal·highwater·pre/postcommit 종료·인계 실패를 검사했다. 실제 socket/PostgreSQL/Compose 실행으로 표시하지 않는다.

독립 검토에서 이전 identity 패널 click의 재발급 가능성을 발견해 committed lease 검사로 보완했다. 첫 새 fixture의 재귀 타입 추론, identity 확장을 지운 sibling 준비, 현재 시각을 담는 export 결과의 bytes 비교를 수정했다. 최종 검사는 실제 저장 ZIP bytes와 전체 문서가 그대로임을 확인한다. 첫 전체 검사가 지적한 새 AdaptiveCollection callsite의 benchmark inventory 누락도 실제128dp/41행 fixture와 33개 inventory로 보완했다. 새 fixture는 계측 소스 컴파일만 하며 실행 결과를 만들지 않았다. 초기 compile/failure/fixture-failure/first-full-failure 로그·XML을 보존한다.

이번 adb devices에는 장치가 없었으며 조회가 시작한 daemon은 종료했다. 설정된 SDK에 emulator 실행 파일도 없었다. 물리 SAF/회전/재생성·새 Compose 시각 검사는 V3다. 8080/55433은 listen하지 않았고 이번 서버/DB를 시작하거나 변경하지 않았다. 서버/웹 production과 원본 PR34 변경은 보존했다.

ignored 근거: `android-organization-adoption-ui-focused.log`·`focused-counts.json`·`full.log`·`counts.json`·초기 실패 로그/XML. 문서 포함 정확한 최종 head CI는 PR 본문/`android-organization-adoption-ui-final-ci.json`에서 확인한다. 선행 PR #62 최종 `0b85502d18246eaefd36f7f6f06722c317b73fe5`/run40195968105 SUCCESS2분31초를 재확인했다.

다음은 웹의 분류/PDF 실제 서버 UI 종단과 앱 실기기 검증이며, 독립 개발 큐에는 최신 S1/S2 ZIP 통합·네이티브 PDF 계정 UX/위치·메모·EPUB 원본 교환 등이 남아 있다. 전체 S4를 완료로 표시하지 않는다.
