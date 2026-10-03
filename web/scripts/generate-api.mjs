import { generateContract } from './generate-contract.mjs';
import { generateWorkflowApi } from './generate-workflow-api.mjs';

const check = process.argv.includes('--check');
await generateContract('translation-v1.openapi.json', 'api.ts', check);
await generateWorkflowApi(check);
await generateContract('accounts-v1.openapi.json', 'accounts.ts', check);
await generateContract('catalog-translations-v1.openapi.json', 'catalogTranslations.ts', check);
await generateContract('json-catalog-v1.openapi.json', 'jsonCatalog.ts', check);
await generateContract('reading-translation-v1.openapi.json', 'readingTranslations.ts', check);
await generateContract('reading-progress-v1.openapi.json', 'readingProgress.ts', check);
await generateContract('reading-notes-v1.openapi.json', 'readingNotes.ts', check);
await generateContract('reader-preferences-v1.openapi.json', 'readerPreferences.ts', check);
await generateContract('library-organization-v1.openapi.json', 'libraryOrganization.ts', check);
await generateContract('local-sharing-v1.openapi.json', 'localSharing.ts', check);
await generateContract('source-book-favorites-v1.openapi.json', 'sourceBookFavorites.ts', check);
