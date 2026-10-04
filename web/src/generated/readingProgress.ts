// Generated from contracts/reading-progress-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/reading-progress/{kind}/{recordId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                kind: components["schemas"]["ReadingProgressKind"];
                recordId: string;
            };
            cookie?: never;
        };
        get: operations["getReadingProgress"];
        put: operations["putReadingProgress"];
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
        /** @enum {string} */
        ReadingProgressKind: "ORIGINAL" | "TRANSLATION";
        ReadingProgressAnchor: {
            /** @description Persisted paragraph ID; no ISO control characters. Limit measured in UTF-16 code units. */
            paragraphId: string;
            /** @description UTF-16 code units in the original or translated paragraph text, including paragraph end but excluding the middle of a surrogate pair. */
            characterOffset: number;
        };
        ReadingProgressView: {
            kind: components["schemas"]["ReadingProgressKind"];
            /** Format: uuid */
            recordId: string;
            version: number;
            anchor: components["schemas"]["ReadingProgressAnchor"] | null;
            /**
             * Format: date-time
             * @description Server UTC timestamp; informational, never a concurrency token.
             */
            updatedAt: string | null;
        };
        PutReadingProgressRequest: {
            expectedVersion: number;
            /**
             * Format: uuid
             * @description New UUID for a new update. Preserve the whole request for response-loss retries.
             */
            mutationId: string;
            anchor: components["schemas"]["ReadingProgressAnchor"];
        };
        ReadingProgressProblem: {
            type?: string;
            title?: string;
            status: number;
            detail?: string;
            instance?: string;
            code?: string;
        };
        ReadingProgressConflict: {
            type?: string;
            title?: string;
            /** @enum {integer} */
            status: 409;
            detail?: string;
            instance?: string;
            /** @enum {string} */
            code: "READING_PROGRESS_CONFLICT";
            current: components["schemas"]["ReadingProgressView"];
        };
    };
    responses: {
        /** @description Current reading position. Version zero has null anchor and updatedAt; positive versions have both values. */
        ReadingProgressSuccess: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ReadingProgressView"];
            };
        };
        /** @description Invalid request, inaccessible document or exceeded body limit. Error codes are described in reading-progress-v1.md. */
        ReadingProgressError: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["ReadingProgressProblem"];
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
    getReadingProgress: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                kind: components["schemas"]["ReadingProgressKind"];
                recordId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["ReadingProgressSuccess"];
            400: components["responses"]["ReadingProgressError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            404: components["responses"]["ReadingProgressError"];
        };
    };
    putReadingProgress: {
        parameters: {
            query?: never;
            header: {
                /** @description Use /api/v1/csrf and the matching session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path: {
                kind: components["schemas"]["ReadingProgressKind"];
                recordId: string;
            };
            cookie?: never;
        };
        /** @description At most 8 KiB, including chunked JSON. Retry a lost response with the exact same request. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutReadingProgressRequest"];
            };
        };
        responses: {
            200: components["responses"]["ReadingProgressSuccess"];
            400: components["responses"]["ReadingProgressError"];
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
            404: components["responses"]["ReadingProgressError"];
            /** @description Stale expectedVersion. Keep the pending local position and ask which position to retain. */
            409: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ReadingProgressConflict"];
                };
            };
            413: components["responses"]["ReadingProgressError"];
            /** @description Per-account write limit; retain the pending position and obey Retry-After. */
            429: {
                headers: {
                    "Cache-Control"?: "no-store";
                    "Retry-After": number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ReadingProgressProblem"];
                };
            };
        };
    };
}
