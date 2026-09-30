// Generated from contracts/reader-preferences-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/reader-preferences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getReaderPreferences"];
        put: operations["putReaderPreferences"];
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
        ReaderPreferences: {
            /** @description Logical CSS pixels / Android sp. */
            fontSize: number;
            /** @description Line height multiplier times 100; e.g. 195 means 1.95. */
            lineHeightPercent: number;
            /** @description Logical CSS pixels / Android dp. */
            pageMargin: number;
            /** @enum {string} */
            touchDirection: "left-previous" | "left-next" | "buttons-only";
            /**
             * @description List screens only; the reader body stays paged.
             * @enum {string}
             */
            listMode: "paged" | "scroll";
        };
        ReaderPreferencesView: {
            version: number;
            preferences: components["schemas"]["ReaderPreferences"] | null;
            /**
             * Format: date-time
             * @description Server UTC timestamp; informational, never a concurrency token.
             */
            updatedAt: string | null;
        };
        PutReaderPreferencesRequest: {
            expectedVersion: number;
            /**
             * Format: uuid
             * @description Generate for each new update; preserve the entire request when retrying response loss.
             */
            mutationId: string;
            preferences: components["schemas"]["ReaderPreferences"];
        };
        ReaderPreferencesProblem: {
            type?: string;
            title?: string;
            status: number;
            detail?: string;
            instance?: string;
            code?: string;
        };
        ReaderPreferencesConflict: {
            type?: string;
            title?: string;
            /** @enum {integer} */
            status: 409;
            detail?: string;
            instance?: string;
            /** @enum {string} */
            code: "READER_PREFERENCES_CONFLICT";
            current: components["schemas"]["ReaderPreferencesView"];
        };
        ReaderPreferencesExhausted: {
            type?: string;
            title?: string;
            /** @enum {integer} */
            status: 409;
            detail?: string;
            instance?: string;
            /** @enum {string} */
            code: "READER_PREFERENCES_EXHAUSTED";
        };
    };
    responses: {
        /** @description Current account preferences. Version zero has null preferences and updatedAt; positive versions have both. GET never creates defaults. */
        ReaderPreferencesSuccess: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ReaderPreferencesView"];
            };
        };
        /** @description Invalid request or exceeded body limit. Error codes are described in reader-preferences-v1.md. */
        ReaderPreferencesError: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["ReaderPreferencesProblem"];
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
    getReaderPreferences: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["ReaderPreferencesSuccess"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    putReaderPreferences: {
        parameters: {
            query?: never;
            header: {
                /** @description Use /api/v1/csrf and its matching session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path?: never;
            cookie?: never;
        };
        /** @description At most 8 KiB, including chunked JSON. Preserve the complete request for response-loss retries. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutReaderPreferencesRequest"];
            };
        };
        responses: {
            200: components["responses"]["ReaderPreferencesSuccess"];
            400: components["responses"]["ReaderPreferencesError"];
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
            /** @description CONFLICT includes current; preserve pending local preferences for an explicit choice. EXHAUSTED is terminal and omits current. */
            409: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ReaderPreferencesConflict"] | components["schemas"]["ReaderPreferencesExhausted"];
                };
            };
            413: components["responses"]["ReaderPreferencesError"];
            /** @description Per-account write limit. Preserve pending values and obey Retry-After. */
            429: {
                headers: {
                    "Cache-Control"?: "no-store";
                    "Retry-After": number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ReaderPreferencesProblem"];
                };
            };
        };
    };
}
