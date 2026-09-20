import { expect, it } from 'vitest';
import { parseCatalog } from '../src/lib/catalog';
it('normalizes relative catalog links and rejects active content, embedded credentials and unsupported formats', () => {
  const catalog = { version: 'pagetuner.catalog.v0', title: 'Catalog', items: [{ id: 'one', title: '<script>not markup</script>', format: 'txt', href: 'books/one.txt' }] };
  expect(parseCatalog(catalog, 'https://example.org/reader/catalog.json').items[0].href).toBe('https://example.org/reader/books/one.txt');
  for (const href of ['javascript:alert(1)', 'file:///etc/passwd', 'https://user:pass@example.org/one.txt']) {
    expect(() => parseCatalog({ ...catalog, items: [{ ...catalog.items[0], href }] }, 'https://example.org/catalog.json')).toThrow();
  }
});
