# 내장 독서 폰트

Android 본문 측정과 표시, 웹의 기본 serif 독서 설정은 Noto Serif KR 400/700을 사용한다. 공유 웹의 WOFF2와 라이선스는 APK에 포함되어 폰의 공유 HTTP 서버에서 제공된다. 외부 폰트 서버 연결은 필요 없다. PDF 원본 렌더링과 sans/mono 설정은 기존 동작을 따른다.

원본은 Google Fonts의 `ofl/notoserifkr/NotoSerifKR[wght].ttf`와 같은 디렉터리의 OFL.txt다. SIL Open Font License 1.1을 Android assets와 웹 배포에 포함한다. fonttools 4.66.1로 400/700 정적 인스턴스를 만들고 WOFF2로 변환했다. 문자 부분 집합은 제거하지 않았고 각 파일의 cmap 23,124개 항목이 일치한다. 정적 TTF는 구형 Android에서도 사용할 수 있다.

Android TTF는 각각 약 14.1MB, 공유 웹 WOFF2는 약 3.1/3.2MB다. 최초 공유 접속에 이 전송량이 추가된다. 웹은 폰트 로딩 완료 시 현재 본문 anchor를 유지하며 페이지를 다시 측정한다.

2026-10-03: APK의 내장 WOFF2와 웹 원본의 바이트 일치, 동일 기기 공유 HTTP의 두 폰트 200 응답을 확인했다. Android 본문/페이지 계측 및 실제 패키지 공유 검사 8개 통과. 같은 에뮬레이터 Chrome에서 사용자가 실제 로컬 책 표시를 확인했다. 물리 기기와 무선 핫스팟 확인은 미실행이다.
