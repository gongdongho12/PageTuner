// Generated from contracts/translation-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/csrf": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Authenticate, retain the session cookie and send the returned token under headerName on POST. The response has Cache-Control: no-store. Tokens and credentials must not be persisted in caches or logs. */
        get: operations["getCsrfToken"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/translations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Lists only the authenticated user's saved revisions whose original contentProviderId and bookId are available. Legacy rows missing those fields are omitted from both items and totals. Items are ordered by createdAt DESC, recordId DESC. The zero-based page defaults to 0 and size defaults to 12. Size must be 1..50 and page*size must not exceed 2147483647. Pages beyond the end return empty items with unchanged totals. Paragraph bodies are excluded. Each saved revision remains a separate entry. */
        get: operations["listTranslations"];
        put?: never;
        /** @description Repeated saves of the same authenticated user's artifact/revision reuse one record. A fully specified repeat POST can repair missing original book metadata on a legacy record. No source content is fetched or translated. */
        post: operations["saveTranslation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/translations/{recordId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Returns a complete artifact owned by the authenticated user (created=false). Clients validate artifactId/revision/payloadHash and expected source/settings before writing to a local cache. */
        get: operations["getTranslation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        Paragraph: {
            paragraphId: string;
            text: string;
        };
        /**
         * @example {
         *       "contentProviderId": "fixture-site",
         *       "bookId": "book-42",
         *       "chapterId": "chapter-7",
         *       "sourceRevision": "source-v1",
         *       "sourceLanguage": "en",
         *       "targetLanguage": "ko",
         *       "translationProviderId": "fixture-translator",
         *       "modelId": "model-v1",
         *       "promptRevision": "prompt-v1",
         *       "glossaryRevision": "glossary-v1",
         *       "paragraphs": [
         *         {
         *           "paragraphId": "p-1",
         *           "text": "첫 번째 문단입니다."
         *         },
         *         {
         *           "paragraphId": "p-2",
         *           "text": "두 번째 문단입니다."
         *         }
         *       ]
         *     }
         */
        SaveTranslationRequest: {
            contentProviderId: string;
            bookId: string;
            chapterId: string;
            sourceRevision: string;
            sourceLanguage: string;
            targetLanguage: string;
            translationProviderId: string;
            /** @default  */
            modelId: string;
            /** @default  */
            promptRevision: string;
            /** @default  */
            glossaryRevision: string;
            paragraphs: components["schemas"]["Paragraph"][];
            /** @description Optional human-readable display title. Does not participate in paragraph, artifact or revision hashes; legacy records may omit it. */
            bookTitle?: string;
            /** @description Optional human-readable display title. Does not participate in paragraph, artifact or revision hashes; legacy records may omit it. */
            chapterTitle?: string;
        };
        /**
         * @example {
         *       "recordId": "5ca57e1a-0b31-40f2-a170-0e68b546e0ca",
         *       "artifactId": "c9cf8eada168518eba1340f1e9cfe136b0ac3509da949da0551afc18f6de5c26",
         *       "revision": "c1310764c870042b1297181c6a94a8e00e06e06e93d5aff8aaf3da7aa70e29a5",
         *       "payloadHash": "444a7063ccbd87c1984846efb0edb98b9d46d5f22efbaf0953c05fba951ade1a",
         *       "created": true,
         *       "createdAt": "2026-09-14T00:00:00Z",
         *       "contentProviderId": "fixture-site",
         *       "bookId": "book-42",
         *       "chapterId": "chapter-7",
         *       "sourceRevision": "source-v1",
         *       "sourceLanguage": "en",
         *       "targetLanguage": "ko",
         *       "translationProviderId": "fixture-translator",
         *       "modelId": "model-v1",
         *       "promptRevision": "prompt-v1",
         *       "glossaryRevision": "glossary-v1",
         *       "paragraphs": [
         *         {
         *           "paragraphId": "p-1",
         *           "text": "첫 번째 문단입니다."
         *         },
         *         {
         *           "paragraphId": "p-2",
         *           "text": "두 번째 문단입니다."
         *         }
         *       ]
         *     }
         */
        TranslationResponse: components["schemas"]["SaveTranslationRequest"] & {
            /** Format: uuid */
            recordId: string;
            artifactId: string;
            revision: string;
            payloadHash: string;
            created: boolean;
            /** Format: date-time */
            createdAt: string;
        };
        CsrfResponse: {
            /** @example X-CSRF-TOKEN */
            headerName: string;
            token: string;
        };
        Problem: {
            title?: string;
            status?: number;
            detail?: string;
        };
        /**
         * @description A saved translation revision without paragraph bodies. Revisions are separate entries.
         * @example {
         *       "recordId": "5ca57e1a-0b31-40f2-a170-0e68b546e0ca",
         *       "contentProviderId": "fixture-site",
         *       "bookId": "book-42",
         *       "chapterId": "chapter-7",
         *       "sourceLanguage": "en",
         *       "targetLanguage": "ko",
         *       "translationProviderId": "fixture-translator",
         *       "modelId": "model-v1",
         *       "promptRevision": "prompt-v1",
         *       "glossaryRevision": "glossary-v1",
         *       "sourceRevision": "source-v1",
         *       "artifactId": "c9cf8eada168518eba1340f1e9cfe136b0ac3509da949da0551afc18f6de5c26",
         *       "revision": "c1310764c870042b1297181c6a94a8e00e06e06e93d5aff8aaf3da7aa70e29a5",
         *       "payloadHash": "444a7063ccbd87c1984846efb0edb98b9d46d5f22efbaf0953c05fba951ade1a",
         *       "createdAt": "2026-09-14T00:00:00Z",
         *       "paragraphCount": 2
         *     }
         */
        TranslationSummary: {
            /** Format: uuid */
            recordId: string;
            contentProviderId: string;
            bookId: string;
            chapterId: string;
            sourceLanguage: string;
            targetLanguage: string;
            translationProviderId: string;
            /** @default  */
            modelId: string;
            /** @default  */
            promptRevision: string;
            /** @default  */
            glossaryRevision: string;
            sourceRevision: string;
            artifactId: string;
            revision: string;
            payloadHash: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: int32 */
            paragraphCount: number;
            /** @description Optional human-readable display title. Does not participate in paragraph, artifact or revision hashes; legacy records may omit it. */
            bookTitle?: string;
            /** @description Optional human-readable display title. Does not participate in paragraph, artifact or revision hashes; legacy records may omit it. */
            chapterTitle?: string;
        };
        /**
         * @example {
         *       "items": [
         *         {
         *           "recordId": "5ca57e1a-0b31-40f2-a170-0e68b546e0ca",
         *           "contentProviderId": "fixture-site",
         *           "bookId": "book-42",
         *           "chapterId": "chapter-7",
         *           "sourceLanguage": "en",
         *           "targetLanguage": "ko",
         *           "translationProviderId": "fixture-translator",
         *           "modelId": "model-v1",
         *           "promptRevision": "prompt-v1",
         *           "glossaryRevision": "glossary-v1",
         *           "sourceRevision": "source-v1",
         *           "artifactId": "c9cf8eada168518eba1340f1e9cfe136b0ac3509da949da0551afc18f6de5c26",
         *           "revision": "c1310764c870042b1297181c6a94a8e00e06e06e93d5aff8aaf3da7aa70e29a5",
         *           "payloadHash": "444a7063ccbd87c1984846efb0edb98b9d46d5f22efbaf0953c05fba951ade1a",
         *           "createdAt": "2026-09-14T00:00:00Z",
         *           "paragraphCount": 2
         *         }
         *       ],
         *       "page": 0,
         *       "size": 12,
         *       "totalItems": 1,
         *       "totalPages": 1,
         *       "hasNext": false
         *     }
         */
        TranslationListResponse: {
            items: components["schemas"]["TranslationSummary"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
            /** Format: int64 */
            totalItems: number;
            /** Format: int32 */
            totalPages: number;
            hasNext: boolean;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getCsrfToken: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description CSRF token for the current session. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CsrfResponse"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listTranslations: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description A page of the authenticated user's complete translation metadata. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationListResponse"];
                };
            };
            /** @description Invalid page, size or excessive page offset. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    saveTranslation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveTranslationRequest"];
            };
        };
        responses: {
            /** @description Existing record reused (created=false). */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationResponse"];
                };
            };
            /** @description Created (created=true). */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationResponse"];
                };
            };
            /** @description Invalid or inconsistent content. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing or invalid CSRF token/session. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Stored identity cannot be reconciled with the supplied metadata. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getTranslation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                recordId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Complete saved translation. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationResponse"];
                };
            };
            /** @description Invalid record identifier. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Record absent or owned by another user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Legacy record lacks the original book identifiers required for a safe restore. Republish the original complete artifact to repair it. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
}
