import openapiTS, { astToString } from 'openapi-typescript';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

export async function generateContract(filename, output, check = false) {
  const schema = new URL(`../../contracts/${filename}`, import.meta.url);
  const target = new URL(`../src/generated/${output}`, import.meta.url);
  const generated = `// Generated from contracts/${filename}. Do not edit.\n` + astToString(await openapiTS(schema));
  if (check) {
    const current = await readFile(target, 'utf8').catch(() => '');
    if (current.replaceAll('\r\n', '\n') !== generated.replaceAll('\r\n', '\n')) {
      throw new Error(`${output} is out of date with ${filename}. Run npm run generate:api.`);
    }
    console.log(`${output} matches ${filename}.`);
  } else {
    await mkdir(new URL('../src/generated/', import.meta.url), { recursive: true });
    await writeFile(target, generated);
  }
}
