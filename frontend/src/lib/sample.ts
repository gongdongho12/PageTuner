/** Original sample text; added only after the reader chooses Try a sample book. */
export function createSampleFile(locale: 'en' | 'ko') {
  const paragraphs = locale === 'ko' ? [
    '# 천천히 읽는 아침',
    '창문을 열자 서늘한 바람이 책상 위로 들어왔다. 나는 어제 읽다 만 책을 펼쳤다. 책갈피가 꽂힌 자리에는 아직 대답하지 못한 질문이 하나 남아 있었다.',
    '좋은 문장은 서둘러 읽을 필요가 없다. 한 문장에 머무는 동안 생각은 조금 더 멀리 갈 수 있다. 화면 오른쪽이나 다음 버튼을 눌러 이야기를 이어 가자.',
    '기억하고 싶은 페이지에는 책갈피를 남겨 보자. 메모 탭에는 그 문장을 읽으며 떠오른 생각을 기록할 수 있다. 짧은 기록 하나가 다시 책을 펼칠 이유가 된다.',
    '다른 언어로 읽고 싶다면 원문 언어와 번역 언어를 고른 뒤 현재 페이지를 번역해 보자. 한 번 저장된 번역은 연결이 끊어진 뒤에도 이 브라우저에서 읽을 수 있다.',
    '책을 덮기 전에는 잠깐 창밖을 바라본다. 서두르지 않고 읽은 몇 페이지가 오늘 하루의 속도를 바꾸어 놓았다. 이야기는 내일 아침에도 이곳에서 기다릴 것이다.',
  ] : [
    '# A quieter morning',
    'When I opened the window, cool air moved across the desk. I turned to the page where I had stopped yesterday. A question waited beside the bookmark, patient enough to stay unanswered for another morning.',
    'A good sentence does not need to be hurried. While we pause with a few words, a thought can travel a little further. Use the Next button or tap the right side of the page to continue reading.',
    'Leave a bookmark on a page you want to revisit. In Notes, write down the thought that arrived while you were reading. A small note can become a reason to open a book again.',
    'To read in another language, choose a language pair and translate the current page. Saved translations remain in this browser for offline reading. You decide whether to read the original, the translation, or both.',
    'Before closing the book, I looked outside. A handful of unhurried pages had changed the pace of the day. The story would be waiting here again tomorrow morning.',
  ];
  return new File([paragraphs.join('\n\n')], locale === 'ko' ? '천천히 읽는 아침.md' : 'A quieter morning.md', { type: 'text/markdown' });
}
