# 내장 독서 폰트

Android 본문 측정과 표시, 웹의 기본 serif 독서 설정은 Noto Serif KR 400/700을 사용한다. 공유 웹의 WOFF2와 라이선스는 APK에 포함되어 폰의 공유 HTTP 서버에서 제공된다. 외부 폰트 서버 연결은 필요 없다. PDF 원본 렌더링과 sans/mono 설정은 기존 동작을 따른다.

원본은 Google Fonts의 `ofl/notoserifkr/NotoSerifKR[wght].ttf`와 같은 디렉터리의 OFL.txt다. SIL Open Font License 1.1을 Android assets와 웹 배포에 포함한다. fonttools 4.66.1로 400/700 정적 인스턴스를 만들고 WOFF2로 변환했다. 문자 부분 집합은 제거하지 않았고 각 파일의 cmap 23,124개 항목이 일치한다. 정적 TTF는 구형 Android에서도 사용할 수 있다.

Android TTF는 각각 약 14.1MB, 공유 웹 WOFF2는 약 3.1/3.2MB다. 최초 공유 접속에 이 전송량이 추가된다. 웹은 폰트 로딩 완료 시 현재 본문 anchor를 유지하며 페이지를 다시 측정한다.

2026-10-03: APK의 내장 WOFF2와 웹 원본의 바이트 일치, 동일 기기 공유 HTTP의 두 폰트 200 응답을 확인했다. Android 본문/페이지 계측 및 실제 패키지 공유 검사 8개 통과. 같은 에뮬레이터 Chrome에서 사용자가 실제 로컬 책 표시를 확인했다. 물리 기기와 무선 핫스팟 확인은 미실행이다.

후속 라이선스 경로의 정확한 allowlist를 추가하고 실제 Android HTTP 200 및 OFL 본문을 확인했다. 공유 runtime 20개 검사와 최종 앱 APK/lint 빌드가 통과했다.

## 서버 공개 캐시 설치 후속 수정

2026-10-03: 공유 runtime의 라이선스 경로는 제공됐지만, 클라우드 ServerSecurity 공개 GET 목록에는 같은 경로가 빠져 있었다. 서비스워커가 credentials omit으로 license를 포함한 전체 SHELL을 cache.addAll할 때 401 때문에 설치가 실패할 수 있어 정확한 `/fonts/OFL-NotoSerifKR.txt` GET만 공개했다. 인접 경로와 POST·API의 인증은 유지한다.

서버 MVC 보안 검사 7개 통과: 실제 static license 200/text/plain/OFL 내용, 인접 경로·POST·API 인증을 검사한다. 웹을 포함한 실제 bootJar의 SHELL 68개를 익명 HTTP로 받아 원본 bytes까지 비교했다. 검증용 8082 서버를 종료하고 네트워크 요청이 실패함을 별도 확인한 후, 실제 브라우저에서 root 새로고침·오프라인 라이선스·독서 미리보기 1→2쪽과 콘솔 오류 없음 확인. 화면 근거는 `.gradle-home/font-license-offline-reader.png`다. 검증 서버와 임시 탭을 종료했으며 사용자 서재/DB는 삭제하지 않았다. CI에도 서버 shell 보안 검사를 추가했다.
