import { readFile, rename } from 'node:fs/promises';

const root = new URL('../dist-sharing/', import.meta.url);
const html = await readFile(new URL('sharing.html', root), 'utf8');
if (/https?:\/\//i.test(html) || /serviceWorker|manifest\.webmanifest/.test(html)) throw new Error('Phone sharing must contain only packaged same-origin assets.');
await rename(new URL('sharing.html', root), new URL('index.html', root));
console.log('Prepared phone sharing shell: dist-sharing/index.html');
