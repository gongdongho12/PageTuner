// Generated from contracts/reading-translation-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/reading-translations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["startReadingTranslation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reading-translations/{requestId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                requestId: string;
            };
            cookie?: never;
        };
        get: operations["getReadingTranslation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reading-translations/{requestId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                requestId: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cancelReadingTranslation"];
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
        ProviderKind: "GOOGLE_CLOUD" | "GOOGLE_WEB_TRANSLATE_HTML" | "DEEPSEEK" | "OPENAI_COMPATIBLE_LLM";
        /** @description Defaults preserve the original source/target contract. Kind and display aliases affect only reader display, while enabled and caseSensitive also affect the shared translation fingerprint. Display options never rewrite a stored translation artifact. */
        GlossaryEntry: {
            source: string;
            target: string;
            /**
             * @description Omitted means Character.
             * @enum {string}
             */
            kind?: "Character" | "Place" | "Term";
            /** @description Omitted means no display alias. */
            displayTerm?: string;
            /** @description Omitted means false. */
            caseSensitive?: boolean;
            /** @description Omitted means true. */
            enabled?: boolean;
        };
        /** @description RFC 9457 problem response. Display safe messages; never echo provider exception details or credentials. */
        WorkflowProblem: {
            /** Format: uri */
            type?: string;
            title?: string;
            /** Format: int32 */
            status?: number;
            detail?: string;
            /** Format: uri */
            instance?: string;
            code?: string;
        };
        /** @description Nonempty half-open UTF-16 range in the owned immutable source, without splitting surrogate pairs. */
        ReadingFragment: {
            paragraphId: string;
            start: number;
            end: number;
        };
        /** @description Nonempty half-open UTF-16 range in the owned immutable source, without splitting surrogate pairs. */
        ReadingTranslationItem: {
            paragraphId: string;
            start: number;
            end: number;
            text: string;
        };
        ReadingTranslationRequest: {
            /** Format: uuid */
            requestId: string;
            /** Format: uuid */
            chapterRecordId: string;
            sourceRevision: string;
            /** @description At most 24000 total source characters. Source text is extracted by the server. */
            fragments: components["schemas"]["ReadingFragment"][];
            /** @default GOOGLE_WEB_TRANSLATE_HTML */
            providerKind: components["schemas"]["ProviderKind"];
            /**
             * @description Target language must differ from source and must not be auto.
             * @default ko
             */
            targetLanguage: string;
            /** @description Defaults to the imported chapter language. */
            sourceLanguage?: string | null;
            /** @description LLM endpoint; must be allowed by server configuration. Never fetched by the browser. */
            endpoint?: string | null;
            model?: string | null;
            /** @description Memory-only credential for this execution. Must never be logged or written to job records, offline storage, URLs or response bodies. */
            apiKey?: string | null;
            /** @description Source terms must be unique after case folding and trimming. */
            glossary?: components["schemas"]["GlossaryEntry"][];
            /** @default 210 */
            readingWordsPerMinute: number;
            /**
             * @default READING
             * @enum {string}
             */
            paceMode: "READING" | "FAST" | "OFFLINE_PREFETCH";
        };
        ReadingTranslationResponse: {
            /** Format: uuid */
            requestId: string;
            /** @enum {string} */
            status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
            /** Format: uuid */
            chapterRecordId: string;
            sourceRevision: string;
            sourceHash: string;
            providerKind: components["schemas"]["ProviderKind"];
            /** @description Target language must differ from source and must not be auto. */
            targetLanguage: string;
            completedFragments: number;
            totalFragments: number;
            /** @description Empty unless completed. Exact requested range order; maximum 96000 translated characters in total. */
            items: components["schemas"]["ReadingTranslationItem"][];
            errorCode: string | null;
            /** Format: date-time */
            updatedAt: string;
            /** @enum {string} */
            scope: "READING_PREVIEW";
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
    startReadingTranslation: {
        parameters: {
            query?: never;
            header: {
                /** @description Fetch /api/v1/csrf and send its token with the same-session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReadingTranslationRequest"];
            };
        };
        responses: {
            /** @description Temporary reading state, never a full-chapter artifact. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReadingTranslationResponse"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
        };
    };
    getReadingTranslation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                requestId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Temporary reading state, never a full-chapter artifact. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReadingTranslationResponse"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
        };
    };
    cancelReadingTranslation: {
        parameters: {
            query?: never;
            header: {
                /** @description Fetch /api/v1/csrf and send its token with the same-session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path: {
                requestId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Temporary reading state, never a full-chapter artifact. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReadingTranslationResponse"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication, source/range validation, request conflict or capacity error. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
        };
    };
}
