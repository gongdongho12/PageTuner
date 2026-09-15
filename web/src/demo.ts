import preview from './preview.json';
import type { components } from './generated/api';

/** Original preview prose; kept separate from the authenticated server library. */
export const previewTranslation: components['schemas']['TranslationResponse'] = preview;
export const demoTitle = '느린 독서에 관하여';
