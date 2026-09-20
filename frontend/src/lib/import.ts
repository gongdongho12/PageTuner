import { unzipSync, strFromU8 } from 'fflate';
import { type Book, sha256, splitText } from './model';

function xml(text: string) {
  const doc = new DOMParser().parseFromString(text, 'application/xml');
  if (doc.querySelector('parsererror')) throw new Error('Invalid EPUB XML.');
  return doc;
}
function plainText(markup: string) {
  const doc = new DOMParser().parseFromString(markup, 'text/html');
  doc.querySelectorAll('script,style,iframe,object,svg').forEach(node => node.remove());
  doc.querySelectorAll('p,div,br,h1,h2,h3,h4,li,section').forEach(node => node.append('\n'));
  return (doc.body.textContent ?? '').replace(/[ \t]+/g, ' ').replace(/\n\s*\n/g, '\n\n').trim();
}
function base64(bytes: Uint8Array) {
  let out = '';
  for (let i = 0; i < bytes.length; i += 8192) out += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(out);
}
export async function importBook(file: File): Promise<Book> {
  if (file.size > 50_000_000) throw new Error('File exceeds 50 MB.');
  const ext = file.name.split('.').pop()?.toUpperCase();
  if (!['TXT', 'MD', 'EPUB', 'PDF'].includes(ext ?? '')) throw new Error('Supported formats: TXT, MD, EPUB, PDF.');
  const bytes = await file.arrayBuffer();
  let title = file.name.replace(/\.[^.]+$/, '');
  const pages: Book['pages'] = [];
  let pdf: string | undefined;
  if (ext === 'EPUB') {
    let expanded = 0;
    const archive = unzipSync(new Uint8Array(bytes), { filter(entry) {
      expanded += entry.originalSize;
      if (expanded > 100_000_000) throw new Error('Expanded EPUB exceeds 100 MB.');
      return /\.(xml|opf|xhtml|html|htm)$/i.test(entry.name);
    } });
    const read = (path: string) => { if (!archive[path]) throw new Error(`Missing EPUB entry: ${path}`); return strFromU8(archive[path]); };
    const rootPath = xml(read('META-INF/container.xml')).getElementsByTagName('rootfile')[0]?.getAttribute('full-path');
    if (!rootPath) throw new Error('EPUB package is missing.');
    const opf = xml(read(rootPath));
    title = opf.getElementsByTagNameNS('*', 'title')[0]?.textContent?.trim() || title;
    const manifest = new Map(Array.from(opf.getElementsByTagName('item')).map(item => [item.getAttribute('id'), item.getAttribute('href')]));
    for (const ref of Array.from(opf.getElementsByTagName('itemref'))) {
      if (ref.getAttribute('linear') === 'no') continue;
      const href = manifest.get(ref.getAttribute('idref')); if (!href) continue;
      const path = decodeURIComponent(new URL(href, `https://epub.local/${rootPath}`).pathname.slice(1));
      const markup = read(path);
      const doc = new DOMParser().parseFromString(markup, 'text/html');
      const chapter = doc.querySelector('h1,h2,title')?.textContent?.trim() || `Chapter ${pages.length + 1}`;
      const text = plainText(markup);
      if (text) pages.push(...splitText(text).map(text => ({ text, chapter })));
    }
  } else if (ext === 'PDF') {
    const pdfjs = await import('pdfjs-dist');
    pdfjs.GlobalWorkerOptions.workerSrc = '/pdf.worker.min.mjs';
    const task = pdfjs.getDocument({ data: new Uint8Array(bytes.slice(0)) });
    const document = await task.promise;
    try {
      if (document.numPages > 2000) throw new Error('PDF exceeds 2,000 pages.');
      for (let i = 1; i <= document.numPages; i++) {
        const page = await document.getPage(i);
        const content = await page.getTextContent();
        const text = content.items.map(item => 'str' in item ? item.str + (item.hasEOL ? '\n' : ' ') : '').join('');
        pages.push({ text, chapter: `PDF · ${i}` });
      }
      pdf = base64(new Uint8Array(bytes));
    } finally { await task.destroy(); }
  } else {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    if (!text.trim()) throw new Error('The document is empty.');
    pages.push(...splitText(text).map(text => ({ text, chapter: title })));
  }
  if (!pages.length || pages.length > 20_000) throw new Error('No readable text, or more than 20,000 pages.');
  const now = new Date().toISOString();
  return { id: await sha256(bytes), title, format: ext as Book['format'], pages, currentPage: 0,
    folder: '', tags: '', importedAt: now, openedAt: now, bookmarks: [], notes: [], ...(pdf ? { pdf } : {}) };
}
