// Generated from contracts/catalog-translations-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/catalog-translations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["startCatalogTranslation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/catalog-translations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getCatalogTranslation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/catalog-translations/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cancelCatalogTranslation"];
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
        CatalogEntry: {
            key: string;
            title: string;
            description: string | null;
        };
        /** @description At most 24 visible catalog entries and 24,000 UTF-16 characters. Credentials are memory-only. Retransmission with the same request ID reuses the existing task; editing the input requires a new ID. */
        CatalogTranslationRequest: {
            /** Format: uuid */
            requestId: string;
            items: components["schemas"]["CatalogEntry"][];
            /** @default auto */
            sourceLanguage: string;
            /** @default ko */
            targetLanguage: string;
            /**
             * @default GOOGLE_WEB_TRANSLATE_HTML
             * @enum {string}
             */
            providerKind: "GOOGLE_WEB_TRANSLATE_HTML" | "GOOGLE_CLOUD" | "DEEPSEEK" | "OPENAI_COMPATIBLE_LLM";
            endpoint?: string | null;
            model?: string | null;
            apiKey?: string | null;
        };
        CatalogTranslationItem: {
            key: string;
            title: string;
            description: string | null;
            targetLanguage: string;
        };
        /** @description Account-scoped, memory-only display result. Terminal records are retained for up to 15 minutes and may be evicted earlier at capacity. All records disappear on restart. Partial translations are not exposed. Cancellation prevents late publication. */
        CatalogTranslationResponse: {
            /** Format: uuid */
            requestId: string;
            /** @enum {string} */
            status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
            /** @description SHA-256 of each input row's concatenated UTF-16 length-prefixed key/title/description (null becomes empty), rows joined by LF. */
            sourceHash: string;
            providerKind: string;
            targetLanguage: string;
            completedSegments: number;
            totalSegments: number;
            items: components["schemas"]["CatalogTranslationItem"][];
            errorCode: string | null;
            /** Format: date-time */
            updatedAt: string;
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
    startCatalogTranslation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CatalogTranslationRequest"];
            };
        };
        responses: {
            /** @description Current task state */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CatalogTranslationResponse"];
                };
            };
            /** @description Invalid input */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Authentication required */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description CSRF rejected */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Request identity conflict */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description One active catalog task per account; 16 active tasks server-wide */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getCatalogTranslation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current task state */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CatalogTranslationResponse"];
                };
            };
            /** @description Expired or belongs to another account */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    cancelCatalogTranslation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current task state */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CatalogTranslationResponse"];
                };
            };
            /** @description Invalid input */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Authentication required */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description CSRF rejected */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Request identity conflict */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description One active catalog task per account; 16 active tasks server-wide */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
