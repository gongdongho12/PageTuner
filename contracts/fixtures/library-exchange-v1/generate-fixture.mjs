// Deterministic, dependency-free fixture writer. These are synthetic documents.
import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync, deflateSync } from 'node:zlib';

const root = dirname(fileURLToPath(import.meta.url));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function pngChunk(type, bytes) {
  const name = Buffer.from(type);
  const size = Buffer.alloc(4); size.writeUInt32BE(bytes.length);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([name, bytes])));
  return Buffer.concat([size, name, bytes, crc]);
}
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(1, 0); ihdr.writeUInt32BE(1, 4); ihdr[8] = 8; ihdr[9] = 2;
const png = Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), pngChunk('IHDR', ihdr), pngChunk('IDAT', deflateSync(Buffer.from([0,255,255,255]))), pngChunk('IEND', Buffer.alloc(0))]);
const pdfContent = 'BT /F1 12 Tf 20 70 Td (Portable library fixture) Tj ET';
const pdfObjects = [
  '<< /Type /Catalog /Pages 2 0 R >>',
  '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
  '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  `<< /Length ${Buffer.byteLength(pdfContent)} >>\nstream\n${pdfContent}\nendstream`,
];
let pdfText = '%PDF-1.4\n'; const offsets = [0];
for (const [index, object] of pdfObjects.entries()) { offsets.push(Buffer.byteLength(pdfText)); pdfText += `${index + 1} 0 obj\n${object}\nendobj\n`; }
const xref = Buffer.byteLength(pdfText);
pdfText += `xref\n0 6\n0000000000 65535 f \n${offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
const pdf = Buffer.from(pdfText);
const createdAt = '2026-09-15T00:00:00Z';
const document = {
  id: 'fixture:original', bookTitle: 'Portable Book', chapterTitle: 'Chapter 1', language: 'en', kind: 'original',
  paragraphs: [{ paragraphId: 'p-1', text: 'Hello 🌏.\nSecond line.' }, { paragraphId: 'p-2', text: '원문과 번역을 함께 보관합니다.' }],
  outline: [{ title: 'Chapter 1', paragraphId: 'p-1' }], position: { paragraphId: 'p-1', characterOffset: 8 },
  notes: [{ id: 'note-1', kind: 'highlight', title: 'Greeting', text: 'Across devices', excerpt: 'Hello', anchor: { paragraphId: 'p-1', characterOffset: 0 }, range: { start: { paragraphId: 'p-1', characterOffset: 0 }, end: { paragraphId: 'p-1', characterOffset: 5 } }, createdAt }],
  organization: { folder: 'Reading', tags: ['fixture'], favorite: true },
  glossary: [{ source: 'Hello', target: '안녕', kind: 'TERM', displayTerm: '안녕', caseSensitive: false, enabled: true }],
  assets: [{ path: `assets/${hash(png)}`, role: 'image', paragraphId: 'p-1', alt: 'Synthetic white pixel' }],
  extensions: { origin: { providerId: 'fixture', bookId: 'book-1', chapterId: 'chapter-1' } },
};
const translation = {
  ...document, id: 'fixture:translation', language: 'ko', kind: 'translation',
  paragraphs: [{ paragraphId: 'p-1', text: '안녕 🌏.\n두 번째 줄.' }, { paragraphId: 'p-2', text: '원문과 번역을 함께 보관합니다.' }],
  position: { paragraphId: 'p-1', characterOffset: 6 }, notes: [], assets: [],
  extensions: { originalDocumentId: 'fixture:original', translation: { providerId: 'google-web-translate-public', sourceLanguage: 'en', targetLanguage: 'ko' } },
};
const pdfDocument = {
  id: 'fixture:pdf', bookTitle: 'PDF fixture', chapterTitle: 'Page 1', language: 'en', kind: 'local',
  paragraphs: [{ paragraphId: 'pdf-p-1', text: 'Portable library fixture' }],
  outline: [{ title: 'Page 1', paragraphId: 'pdf-p-1' }], notes: [], organization: { folder: '', tags: [], favorite: false }, glossary: [],
  assets: [{ path: `assets/${hash(pdf)}`, role: 'pdf' }], extensions: { pdfTextErrorPages: [] },
};
const files = new Map();
const documents = [document, translation, pdfDocument].map(value => {
  const bytes = Buffer.from(JSON.stringify(value, null, 2) + '\n');
  const sha256 = hash(bytes); const path = `documents/${sha256}.json`;
  files.set(path, bytes); return { path, sha256, bytes: bytes.length };
});
const assets = [[png, 'image/png'], [pdf, 'application/pdf']].map(([bytes, mimeType]) => {
  const sha256 = hash(bytes); const path = `assets/${sha256}`;
  files.set(path, bytes); return { path, sha256, bytes: bytes.length, mimeType };
});
const manifest = { format: 'pageturner.library', version: 1, createdAt, documents, assets };
const entries = [['manifest.json', Buffer.from(JSON.stringify(manifest, null, 2) + '\n')], ...files.entries()];
const locals = []; const centrals = []; let cursor = 0;
for (const [path, bytes] of entries) {
  const name = Buffer.from(path); const compressed = deflateRawSync(bytes); const crc = crc32(bytes);
  const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6); local.writeUInt16LE(8, 8); local.writeUInt32LE(crc, 14); local.writeUInt32LE(compressed.length, 18); local.writeUInt32LE(bytes.length, 22); local.writeUInt16LE(name.length, 26);
  const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8); central.writeUInt16LE(8, 10); central.writeUInt32LE(crc, 16); central.writeUInt32LE(compressed.length, 20); central.writeUInt32LE(bytes.length, 24); central.writeUInt16LE(name.length, 28); central.writeUInt32LE(cursor, 42);
  locals.push(local, name, compressed); centrals.push(central, name); cursor += local.length + name.length + compressed.length;
}
const directory = Buffer.concat(centrals); const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(cursor, 16);
for (const [path, bytes] of entries) { await mkdir(dirname(join(root, path)), { recursive: true }); await writeFile(join(root, path), bytes); }
await writeFile(join(root, 'portable-v1.zip'), Buffer.concat([...locals, directory, end]));
await writeFile(join(root, 'expected.json'), JSON.stringify({ createdAt, documentIds: [document.id, translation.id, pdfDocument.id], assetSha256: assets.map(asset => asset.sha256), originalFirstParagraph: document.paragraphs[0].text, position: document.position, translationFirstParagraph: translation.paragraphs[0].text }, null, 2) + '\n');
