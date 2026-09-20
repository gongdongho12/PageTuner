/** UI behavior shared by the local and server web readers, matching the Android reader. */
export const readerDefaults = { fontSize: 18, lineHeight: 1.35, margin: 18,
  display: 'translation', listMode: 'paged', tapMode: 'normal' } as const;
export const readerRanges = { fontSize: [14, 28], lineHeight: [1.1, 1.8], margin: [8, 36] } as const;
export const readerTabs = ['reader', 'find', 'bookmarks', 'notes', 'details'] as const;
export type ReaderTab = typeof readerTabs[number];
export const settingsTabs = ['display', 'appearance', 'languages', 'account'] as const;
export type SettingsTab = typeof settingsTabs[number];
export function pageKey(key: string, shift = false): -1 | 0 | 1 {
  if (key === ' ' && shift) return -1;
  if (['ArrowRight', 'PageDown', ' '].includes(key)) return 1;
  if (['ArrowLeft', 'PageUp'].includes(key)) return -1;
  return 0;
}
export function tapAction(x: number, mode: 'normal' | 'reverse' | 'buttons', focus: boolean): -1 | 0 | 1 | 'exit' {
  if (x >= .4 && x <= .6) return focus ? 'exit' : 0;
  if (mode === 'buttons') return 0;
  return (x < .4 ? -1 : 1) * (mode === 'reverse' ? -1 : 1) as -1 | 1;
}
export type TextPart = { text: string; offset: number };
/** A character-based anchor survives font/viewport changes. Never split a surrogate pair. */
export function fitText(text: string, fits: (candidate: string) => boolean): TextPart[] {
  const parts: TextPart[] = [];
  let offset = 0;
  while (offset < text.length) {
    let lo = 1, hi = text.length - offset, best = 0;
    while (lo <= hi) {
      const mid = Math.floor((lo + hi) / 2);
      if (fits(text.slice(offset, offset + mid))) { best = mid; lo = mid + 1; } else hi = mid - 1;
    }
    let end = offset + Math.max(best, 1);
    if (end < text.length) {
      const boundary = Math.max(text.lastIndexOf(' ', end - 1), text.lastIndexOf('\n', end - 1));
      if (boundary > offset + best * .6) end = boundary + 1;
      if (/[\uD800-\uDBFF]/.test(text[end - 1]) && /[\uDC00-\uDFFF]/.test(text[end])) end--;
    }
    if (end <= offset) end = offset + (text.codePointAt(offset)! > 0xffff ? 2 : 1);
    parts.push({ text: text.slice(offset, end), offset }); offset = end;
  }
  return parts.length ? parts : [{ text: '', offset: 0 }];
}
export function partAt(parts: TextPart[], offset: number) {
  for (let i = parts.length - 1; i >= 0; i--) if (parts[i].offset <= offset) return i;
  return 0;
}
export const appLabels = {
  en: { bookmarks: 'Bookmarks', notes: 'Notes', display: 'Display & page turn', appearance: 'Reader preferences',
    languages: 'AI translation', account: 'Server connection', server: 'Server library', device: 'Device library',
    connect: 'Connect', disconnect: 'Disconnect', refresh: 'Refresh', uploadBook: 'Save this book to server',
    serverEmpty: 'No books saved on the server.', chapters: 'Chapters', resume: 'Continue reading',
    conflict: 'Another device saved a newer position.', useServer: 'Use server position', useHere: 'Save this position',
    sourceOnly: 'Source text', progressSaved: 'Reading position saved', serverHelp: 'Sign in to read your server library and share reading positions.',
    bookmarkNote: 'Bookmark note', retry: 'Retry', preferencesReset: 'App reading defaults', connectionRequired: 'Enter the server account in Settings.',
  },
  ko: { bookmarks: '책갈피', notes: '메모', display: '화면 · 페이지 이동', appearance: '독서 설정',
    languages: 'AI 번역', account: '서버 연결', server: '서버 서재', device: '기기 서재',
    connect: '연결', disconnect: '연결 해제', refresh: '새로고침', uploadBook: '현재 책을 서버에 저장',
    serverEmpty: '서버에 저장된 책이 없습니다.', chapters: '챕터', resume: '이어 읽기',
    conflict: '다른 기기에서 더 최근의 읽기 위치를 저장했습니다.', useServer: '서버 위치로 이동', useHere: '현재 위치 저장',
    sourceOnly: '원문', progressSaved: '읽기 위치 저장됨', serverHelp: '로그인하면 서버 서재를 읽고 기기 간 읽기 위치를 이어갈 수 있습니다.',
    bookmarkNote: '책갈피 메모', retry: '재시도', preferencesReset: '앱 독서 기본값', connectionRequired: '설정에서 서버 계정을 입력하세요.',
  },
} as const;
export function appText(locale: 'en' | 'ko') { return (key: keyof typeof appLabels.en) => appLabels[locale][key]; }
