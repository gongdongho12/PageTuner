// Generated from contracts/source-book-favorites-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/source-book-favorites": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getSourceBookFavoriteChanges"];
        put: operations["putSourceBookFavorite"];
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
        SourceBookFavoriteMetadata: {
            /** @description UTF-16 units; canonical ECMAScript trim, no controls or unpaired surrogates. */
            title: string;
            authors: string[];
            language: string;
            /**
             * Format: uri
             * @description Absolute HTTP(S) visible ASCII URI, host required, no credentials, backslash or whitespace; port absent or0..65535.
             */
            url: string;
        };
        PutSourceBookFavoriteRequest: {
            /** @description Exact original provider ID, canonical UTF-16 text. */
            providerId: string;
            /** @description Exact original book ID, canonical UTF-16 text. Never truncate or infer from title/URL. */
            bookId: string;
            expectedVersion: number;
            /** Format: uuid */
            mutationId: string;
            deleted: boolean;
            /** @description Null exactly when deleted is true. */
            book: components["schemas"]["SourceBookFavoriteMetadata"] | null;
        };
        SourceBookFavoriteItem: {
            providerId: string;
            bookId: string;
            version: number;
            changeRevision: number;
            deleted: boolean;
            book: components["schemas"]["SourceBookFavoriteMetadata"] | null;
            /**
             * Format: date-time
             * @description UTC timestamp; null only for absent version0 conflict current.
             */
            updatedAt: string | null;
        };
        SourceBookFavoriteChanges: {
            items: components["schemas"]["SourceBookFavoriteItem"][];
            nextAfterRevision: number;
            watermark: number;
            hasMore: boolean;
        };
        SourceBookFavoriteProblem: {
            type: string;
            title: string;
            status: number;
            detail: string;
            instance?: string;
            /** @enum {string} */
            code?: "SOURCE_BOOK_FAVORITE_INVALID" | "SOURCE_BOOK_FAVORITE_MUTATION_REUSED" | "SOURCE_BOOK_FAVORITE_CONFLICT" | "SOURCE_BOOK_FAVORITE_REVISION_EXHAUSTED" | "SOURCE_BOOK_FAVORITE_LIMIT";
            current?: components["schemas"]["SourceBookFavoriteItem"];
        };
    };
    responses: {
        /** @description Request rejected. */
        SourceBookFavoriteError: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["SourceBookFavoriteProblem"];
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
    getSourceBookFavoriteChanges: {
        parameters: {
            query?: {
                afterRevision?: number;
                /** @description Continue the captured watermark; between afterRevision and current committed revision. */
                untilRevision?: number;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Immutable committed changes. Persist page and cursor atomically. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SourceBookFavoriteChanges"];
                };
            };
            400: components["responses"]["SourceBookFavoriteError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    putSourceBookFavorite: {
        parameters: {
            query?: never;
            header: {
                /** @description Use /api/v1/csrf and matching session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path?: never;
            cookie?: never;
        };
        /** @description Strict JSON at most 32 KiB; preserve exact request for replay. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutSourceBookFavoriteRequest"];
            };
        };
        responses: {
            /** @description Created, updated, deleted, restored or exact latest mutation replay. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SourceBookFavoriteItem"];
                };
            };
            400: components["responses"]["SourceBookFavoriteError"];
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
            /** @description CONFLICT includes authoritative current; REVISION_EXHAUSTED does not. Preserve local pending edits. */
            409: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["SourceBookFavoriteProblem"];
                };
            };
            413: components["responses"]["SourceBookFavoriteError"];
            /** @description Per-account write limit. */
            429: {
                headers: {
                    "Retry-After": number;
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["SourceBookFavoriteProblem"];
                };
            };
        };
    };
}
