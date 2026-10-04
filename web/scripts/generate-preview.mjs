import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
const sample = JSON.parse(await readFile(new URL('../fixtures/preview-source.json', import.meta.url), 'utf8'));
const hash = text => createHash('sha256').update(text, 'utf8').digest('hex');
const artifactId = hash([`${sample.contentProviderId.trim()}:${sample.bookId.trim()}:${sample.chapterId.trim()}`, sample.sourceRevision, sample.sourceLanguage, sample.targetLanguage, sample.translationProviderId, sample.modelId, sample.promptRevision, sample.glossaryRevision].join('|'));
const payloadHash = hash(sample.paragraphs.map(p => `${p.paragraphId}:${p.text}`).join('\n'));
const response = { ...sample, recordId: 'c7037bb4-b9c1-4410-bb7e-55ebf631a5c1', artifactId, payloadHash, revision: hash(`${artifactId}|${payloadHash}`), created: false, createdAt: '2026-09-14T00:00:00Z' };
await writeFile(new URL('../src/preview.json', import.meta.url), JSON.stringify(response, null, 2) + '\n');
