// Generated from contracts/json-catalog-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/catalogs/json": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getJsonCatalog"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/catalog-files": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getCatalogFile"];
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
        JsonCatalogLink: {
            rel: string;
            href: string;
            type: string | null;
        };
        JsonCatalogTranslationHints: {
            sourceLanguage: string;
            targetLanguages: string[];
        };
        JsonCatalogEntry: {
            id: string;
            title: string;
            authors: string[];
            /** @enum {string} */
            format: "txt" | "markdown" | "epub" | "pdf";
            href: string;
            language: string | null;
            type: string | null;
            /** Format: int64 */
            size: number | null;
            checksum: string | null;
            updatedAt: string | null;
            cover: string | null;
            translationHints: components["schemas"]["JsonCatalogTranslationHints"];
        };
        JsonCatalogDocument: {
            /** @enum {string} */
            version: "pagetuner.catalog.v0";
            id: string;
            title: string;
            catalogUrl: string;
            updatedAt: string | null;
            links: components["schemas"]["JsonCatalogLink"][];
            items: components["schemas"]["JsonCatalogEntry"][];
        };
        ProblemDetail: {
            status: number;
            detail: string;
            title?: string;
            type?: string;
            instance?: string;
            code?: string;
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
    getJsonCatalog: {
        parameters: {
            query: {
                /** @description Public HTTPS URL on port 443, without credentials or fragments. Every redirect and socket DNS answer is revalidated. */
                url: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Normalized catalog; source JSON capped at 5 MiB, 1000 entries and 100 links. Cache-Control: no-store. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["JsonCatalogDocument"];
                };
            };
            /** @description Invalid or non-public HTTPS URL */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ProblemDetail"];
                };
            };
            /** @description Authentication required */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Remote catalog invalid, unavailable, empty or above byte limit */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ProblemDetail"];
                };
            };
            /** @description Remote request deadline exceeded */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ProblemDetail"];
                };
            };
        };
    };
    getCatalogFile: {
        parameters: {
            query: {
                /** @description Public HTTPS URL on port 443, without credentials or fragments. Every redirect and socket DNS answer is revalidated. */
                url: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Nonempty original bytes, at most 32 MiB after decompression. Content-Disposition attachment, X-Content-Type-Options nosniff, Cache-Control no-store. No remote cookies or credentials are forwarded. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/octet-stream": string;
                };
            };
            /** @description Invalid or non-public HTTPS URL */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ProblemDetail"];
                };
            };
            /** @description Authentication required */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Remote catalog invalid, unavailable, empty or above byte limit */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ProblemDetail"];
                };
            };
            /** @description Remote request deadline exceeded */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ProblemDetail"];
                };
            };
        };
    };
}
