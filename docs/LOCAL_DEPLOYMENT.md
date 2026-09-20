# 로컬 배포와 반복 검증

전체 스택은 다음 주소에서 실행합니다.

- 웹: http://127.0.0.1:3000
- API 및 health: http://127.0.0.1:8080/actuator/health
- PostgreSQL: 127.0.0.1:55432 (`pagetuner` DB/user)

로컬 계정의 사용자 이름과 비밀번호는 `.local/stack.env`에 있습니다. 최초 `init`에서
임의 비밀번호를 만들며 이후 재실행 시 보존합니다. 파일은 소유자만 읽을 수 있게 만들고
`.local/` 전체를 Git에서 제외합니다. 웹 설정 → 서버 연결에 이 계정을 입력한 뒤
서재 → 서버에서 연결하세요. 서버 탭에서도 계정을 바로 입력해 연결할 수 있습니다.

## 명령

저장소 루트에서 실행합니다. macOS JDK 21, Node, 설치된 frontend 의존성과
Docker Desktop이 필요합니다. `up`과 `deploy`는 Docker를 먼저 검사하고, macOS에서
중지되어 있으면 Docker Desktop을 실행한 뒤 최대 90초 동안 준비를 기다립니다.
준비되지 않으면 기존 배포를 유지하며 중단합니다. 빌드는 기존 Gradle 및 npm 환경을 사용합니다.

```bash
python3 scripts/local_stack.py init    # 로컬 설정 생성, 기존 값 유지
python3 scripts/local_stack.py deploy  # 새 배포본 빌드 → 후보 브라우저 검사 → 전환 → health 검사
python3 scripts/local_stack.py up      # 마지막 배포본 실행
python3 scripts/local_stack.py status  # 웹/API/DB 접속 상태
python3 scripts/local_stack.py check   # 실제 브라우저 검증
python3 scripts/local_stack.py stop    # 웹/API만 정상 종료
python3 scripts/local_stack.py down    # 웹/API와 전용 DB 컨테이너 종료, 볼륨 보존
```

`deploy`는 서버 JAR와 Next standalone 출력을 `.local/releases/시간/`에 복사합니다.
빌드 중에는 이전 배포본을 유지합니다. 새 standalone 후보를 3110 포트에서 실행해
배포 관리 스크립트·프론트엔드 단위 테스트와 데스크톱·모바일 브라우저 회귀를 통과한 뒤 관리 중인 프로세스를 전환합니다.
후보 검증이 실패하면 현재 3000 포트 배포본을 유지합니다.
전환에 실패하면 이전 애플리케이션 배포본을 다시 실행합니다. DB 마이그레이션을 되돌리는
기능은 아닙니다. `.local/stack.lock`으로 배포·검증 명령의 중복 실행을 방지합니다.
서로 다른 프로세스가 같은 포트를 사용 중이면 임의로 종료하지 않습니다.

DB는 `deploy/compose.local.yml`의 전용 `pageturner-local_database` 볼륨에 저장합니다.
스크립트는 볼륨을 삭제하지 않습니다. 서버·웹 PID와 시작 시각을 기록하여 해당 프로세스만
정상 종료합니다. 로그는 `.local/server.log`, `.local/frontend.log`에 있습니다.
오래된 배포본은 자동 삭제하지 않으므로 필요할 때 별도로 정리할 수 있습니다.

## 실제 브라우저 검증

`frontend/playwright.live.config.ts`와 `frontend/tests/live/local-stack.spec.ts`는
응답을 대체하지 않고 실행 중인 스택에 접속합니다. 다음 흐름을 검사합니다.

1. 로컬 텍스트 파일 가져오기, 설정에서 서버 계정 입력, 실제 세션 로그인.
2. 최초 실행 때만 `__local_check__ PageTurner` 테스트 책을 서버에 등록.
3. 챕터 읽기, 문자·문단 이동에 따른 진행률 저장.
4. 테스트 책갈피 저장과 재조회.
5. 같은 브라우저 쿠키로 번역 저장 → JSON 백업 → 중복 없이 복원.
6. 페이지 새로고침 뒤 로그인 유지, 서버 위치로 이어 읽기.
7. 모바일 뷰포트 스크린샷과 브라우저 오류 검사.

테스트는 전용 이름의 책·책갈피·번역을 재사용하며 사용자 책을 삭제하지 않습니다.
유료 번역 공급자를 호출하지 않고 고정된 검증용 번역문을 저장합니다. 반복 결과는
`.local/live-results.json`, 스크린샷은 `.local/live-reader-mobile.png`에 남습니다.
로그인 자격 증명이 trace에 남지 않도록 live 테스트의 trace를 비활성화했습니다.

## 개선 루프

현재 작업에 30분 간격의 반복 실행을 연결했습니다. 매 실행에서 현재 배포와 실제
사용 흐름을 점검하고, 재현 가능한 오류 또는 근거가 있는 작은 개선 하나를 처리합니다.
적절한 회귀 검증 후 로컬 재배포하고 live 검증을 반복합니다. 검사할 문제가 없으면
코드를 억지로 변경하지 않습니다. 의미 있는 개선 완료·새 실패·사용자 조치가 필요한
경우에만 알립니다. 내역은 `LOCAL_IMPROVEMENT_LOG.md`에 기록합니다.

Mac과 Codex 앱이 실행 중이어야 로컬 반복 작업이 실행됩니다. 중지 요청 시 이 작업에
연결한 'PageTurner 로컬 검증·개선 루프'를 일시정지하면 됩니다.
