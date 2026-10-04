// Generated from contracts/book-glossary-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/book-glossary/query": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Read-only query with the same result as GET. Use for all identities to avoid URL request-line limits for long percent-encoded IDs. Requires CSRF but does not consume the write rate limit or create rows. */
        post: operations["queryBookGlossary"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/book-glossary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getBookGlossary"];
        put: operations["putBookGlossary"];
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
        BookGlossaryQuery: {
            providerId: string;
            bookId: string;
            targetLanguage: components["schemas"]["TargetLanguage"];
        };
        /** @description Lowercase concrete language tag. auto is forbidden; never silently lowercase or trim. */
        TargetLanguage: string;
        BookGlossaryEntry: {
            /** @description Exact opaque existing entry ID. No boundary ECMAScript whitespace, controls or unpaired surrogates. Unique within the snapshot. */
            id: string;
            /** @description Nonblank source text, preserving outer whitespace; no controls or unpaired surrogates. */
            sourceTerm: string;
            /** @description Nonblank translation text, preserving outer whitespace; no controls or unpaired surrogates. */
            translatedTerm: string;
            /** @description Display-only alias, including empty or whitespace; preserved exactly. No controls or unpaired surrogates. */
            displayTerm: string;
            /** @enum {string} */
            kind: "Character" | "Place" | "Term";
            caseSensitive: boolean;
            enabled: boolean;
        };
        PutBookGlossaryRequest: {
            /** @description Exact original ID; reject boundary ECMAScript whitespace, controls and unpaired surrogates. */
            providerId: string;
            /** @description Exact original ID; same canonical identity checks. Never replace with title, document UUID or storage digest. */
            bookId: string;
            targetLanguage: components["schemas"]["TargetLanguage"];
            expectedVersion: number;
            /** Format: uuid */
            mutationId: string;
            /** @description Ordered full snapshot including disabled entries; duplicate source terms are allowed. Null is a versioned deletion; [] is a present empty glossary. */
            entries: components["schemas"]["BookGlossaryEntry"][] | null;
        };
        BookGlossaryView: {
            providerId: string;
            bookId: string;
            targetLanguage: components["schemas"]["TargetLanguage"];
            version: number;
            entries: components["schemas"]["BookGlossaryEntry"][] | null;
            /**
             * Format: date-time
             * @description UTC timestamp, null exactly for absent version 0 (which also has null entries).
             */
            updatedAt: string | null;
        };
        BookGlossaryProblem: {
            type: string;
            title: string;
            status: number;
            detail: string;
            instance?: string;
            /** @enum {string} */
            code?: "BOOK_GLOSSARY_INVALID" | "BOOK_GLOSSARY_CONFLICT" | "BOOK_GLOSSARY_MUTATION_REUSED" | "BOOK_GLOSSARY_EXHAUSTED" | "BOOK_GLOSSARY_LIMIT";
            current?: components["schemas"]["BookGlossaryView"];
        };
    };
    responses: {
        /** @description Request rejected; no partial changes. */
        BookGlossaryError: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["BookGlossaryProblem"];
            };
        };
    };
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    queryBookGlossary: {
        parameters: {
            query?: never;
            header: {
                /** @description Use /api/v1/csrf with matching session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path?: never;
            cookie?: never;
        };
        /** @description Strict JSON at most 16 KiB. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["BookGlossaryQuery"];
            };
        };
        responses: {
            /** @description Current snapshot or absent version 0; read-only. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BookGlossaryView"];
                };
            };
            400: components["responses"]["BookGlossaryError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing or invalid CSRF token. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            413: components["responses"]["BookGlossaryError"];
        };
    };
    getBookGlossary: {
        parameters: {
            query: {
                providerId: string;
                bookId: string;
                targetLanguage: components["schemas"]["TargetLanguage"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current account snapshot; absent is version 0, entries null, updatedAt null. No row is created. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BookGlossaryView"];
                };
            };
            400: components["responses"]["BookGlossaryError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    putBookGlossary: {
        parameters: {
            query?: never;
            header: {
                /** @description Use /api/v1/csrf with matching session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path?: never;
            cookie?: never;
        };
        /** @description Strict JSON at most 1 MiB. Retain exact payload and mutationId until acknowledged. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutBookGlossaryRequest"];
            };
        };
        responses: {
            /** @description Persisted snapshot or exact latest mutation replay. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BookGlossaryView"];
                };
            };
            400: components["responses"]["BookGlossaryError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing or invalid CSRF token. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description BOOK_GLOSSARY_CONFLICT includes current; BOOK_GLOSSARY_EXHAUSTED does not. Preserve pending local data. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["BookGlossaryProblem"];
                };
            };
            413: components["responses"]["BookGlossaryError"];
            /** @description At most 120 writes per account per minute, including replays. */
            429: {
                headers: {
                    "Retry-After": number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["BookGlossaryProblem"];
                };
            };
        };
    };
}
