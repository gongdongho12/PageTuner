# Mixed Language Translation Check

This Markdown file is designed to test paragraph boundaries, headings, lists,
and mixed language detection in PageTurner.

## Reader Note

The device is in monochrome mode. The user expects stable page turns, readable
line spacing, and translation controls that do not jump around while a request
is running.

## 한국어 메모

이 문단은 한국어로 작성되어 있습니다. 자동 감지 모드에서 영어로 번역하거나,
시작언어를 `ko`로 직접 지정해서 같은 결과가 나오는지 확인할 수 있습니다.

## Checklist

- Source language: `auto`, `en`, or `ko`
- Target language: `ko` or `en`
- Provider: Google Web HTML, Google Cloud, or LLM API
- Display mode: original only, translation only, or both

Final sentence: cached translations should load again without another network
request.
