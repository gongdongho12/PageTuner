// Generated from contracts/workflow-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/translation-providers/check": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Authenticated CSRF-protected check of one fixed short English sample using the shared translation runtime. The default source language is auto. No chapter, job, checkpoint, translated text or credential is saved. Paid providers may charge for this sample. At most one live worker per account and four globally; starts for an account are separated by ten seconds. Cooldown tracking is limited to 512 accounts. A 20-second deadline returns a FAILED timeout and cancels the worker, whose slot remains reserved until cleanup completes. Explicit cancellation of the controller future reaches the worker; HTTP disconnect detection depends on the servlet container, so a disconnected check may continue until its deadline. Every response is non-cacheable. */
        post: operations["checkTranslationProvider"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/novel-sources": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listNovelSources"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/novels/catalog": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Browse or search using the selected provider. Source adapters own URL classification and remote pagination. */
        get: operations["getNovelCatalog"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/novels/detail": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Retrieve book metadata and a page of the complete provider chapter index. */
        get: operations["getNovelDetail"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/chapters": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description List source metadata for this user. Paragraph bodies are intentionally omitted. */
        get: operations["listStoredChapters"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/chapters/import": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Fetch readable source paragraphs and atomically save an immutable source revision for this user. Retrying the same content reuses the stored revision. Requires the cookie and CSRF token from GET /api/v1/csrf in translation-v1.openapi.json. */
        post: operations["importChapter"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/chapters/{recordId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getStoredChapter"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/translation-providers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Return supported providers and safe defaults. Configured indicates usable server credentials, never their values. */
        get: operations["listTranslationProviders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/translation-jobs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listTranslationJobs"];
        put?: never;
        /** @description Create or reuse a translation execution. Repeated matching idempotency requests reuse a job; different settings under the same key return 409. At most four active jobs per user. Custom API keys are memory-only and must be supplied again after interruption. Reuse settings from the failed job with retryOf to copy valid checkpoints. */
        post: operations["createTranslationJob"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/translation-jobs/{jobId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Poll for execution status. Navigating away does not cancel a job. */
        get: operations["getTranslationJob"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/translation-jobs/{jobId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Cancel this user's queued or running job. Terminal jobs return their existing status. Cancellation does not delete source content or checkpoints. */
        post: operations["cancelTranslationJob"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/chapters/upload": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Save extracted local document text as an immutable original belonging to the authenticated user. Requires the session CSRF token. No file binary, credentials or device notes are accepted. */
        post: operations["uploadLocalChapter"];
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
        TranslationProviderCheckRequest: {
            providerKind: components["schemas"]["ProviderKind"];
            /**
             * @description auto is recommended because the check uses a fixed English sample; it does not change the reader's source-language setting.
             * @default auto
             */
            sourceLanguage: string;
            /** @default ko */
            targetLanguage: string;
            /** @description Optional transient override of the configured server key. CR/LF are forbidden. */
            apiKey?: string;
            /** @description LLM endpoint only; must match the existing server allowlist exactly after trailing-slash normalization. */
            endpoint?: string;
            model?: string;
        };
        TranslationProviderCheckResponse: {
            /** @enum {string} */
            status: "SUCCESS" | "FAILED";
            /** @enum {string} */
            code: "PROVIDER_CHECK_OK" | "PROVIDER_CHECK_TIMEOUT" | "PROVIDER_CHECK_CANCELLED" | "TRANSLATION_AUTHENTICATION_FAILED" | "TRANSLATION_RATE_LIMITED" | "TRANSLATION_QUOTA_EXCEEDED" | "TRANSLATION_BAD_REQUEST" | "TRANSLATION_SERVER_ERROR" | "TRANSLATION_NETWORK_ERROR" | "TRANSLATION_INVALID_RESPONSE" | "TRANSLATION_CONFIGURATION_ERROR" | "TRANSLATION_FAILED";
            /** @description A fixed safe Korean message chosen from the public code; never raw provider details. */
            message: string;
            providerKind: components["schemas"]["ProviderKind"];
            sourceLanguage: string;
            targetLanguage: string;
            /** @description Resolved model identity from the shared translation runtime, which may be non-empty even for a provider without a configurable model. */
            model: string;
        };
        /** @description Zero-based server pagination. totalPages=ceil(totalItems/size); hasNext=(page+1<totalPages). */
        Pagination: {
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
        NovelSource: {
            id: string;
            displayName: string;
            /** Format: uri */
            defaultCatalogUrl: string | null;
            remoteSearch: boolean;
            requiresUrl: boolean;
            filters: components["schemas"]["SourceFilters"];
        };
        NovelSourceList: {
            items: components["schemas"]["NovelSource"][];
        };
        NovelBook: {
            bookId: string;
            title: string;
            /** Format: uri */
            url: string;
            authors: string[];
            sourceLanguage: string;
            /** Format: uri */
            coverUrl: string | null;
            description: string | null;
            /** Format: int32 */
            chapterCount: number | null;
            tags: string[];
        };
        /** @description A provider-owned, one-based catalog page. Unknown totals are null. UI pagination within this result is separate from fetching another provider page. */
        NovelCatalog: {
            sourceId: string;
            /** Format: uri */
            url: string;
            /** Format: int32 */
            currentPage: number;
            /** Format: int32 */
            totalPages: number | null;
            /** Format: int32 */
            totalItems: number | null;
            hasPreviousPage: boolean;
            hasNextPage: boolean;
            items: components["schemas"]["NovelBook"][];
        };
        NovelChapter: {
            chapterId: string;
            /** Format: int32 */
            number: number;
            title: string;
            /** Format: uri */
            url: string;
            sourceLanguage: string;
        };
        NovelDetail: {
            sourceId: string;
            bookId: string;
            title: string;
            /** Format: uri */
            url: string;
            author: string;
            sourceLanguage: string;
            status: string;
            /** Format: int32 */
            totalChapters: number;
            summary: string;
            tags: string[];
            /** Format: uri */
            coverUrl: string | null;
            chapters: components["schemas"]["NovelChapter"][];
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
        /** @description Stable source paragraph identity and original text. Ordinals preserve source order independently of rendered pages. */
        SourceParagraph: {
            paragraphId: string;
            /** Format: int32 */
            ordinal: number;
            text: string;
        };
        /** @description Immutable imported chapter revision belonging to the authenticated user. Paragraphs are returned only for import and detail. */
        StoredChapter: {
            /** Format: uuid */
            recordId: string;
            providerId: string;
            bookId: string;
            bookTitle: string;
            /** @description Original source URL; empty for an uploaded local document. */
            bookUrl: string;
            chapterId: string;
            chapterTitle: string;
            /** @description Original source URL; empty for an uploaded local document. */
            chapterUrl: string;
            sourceLanguage: string;
            sourceRevision: string;
            /** Format: date-time */
            createdAt: string;
            paragraphs: components["schemas"]["SourceParagraph"][];
        };
        /** @description Imported chapter metadata without paragraph bodies. Fetch /chapters/{recordId} before reading. */
        ChapterSummary: {
            /** Format: uuid */
            recordId: string;
            providerId: string;
            bookId: string;
            bookTitle: string;
            /** @description Original source URL; empty for an uploaded local document. */
            bookUrl: string;
            chapterId: string;
            chapterTitle: string;
            /** @description Original source URL; empty for an uploaded local document. */
            chapterUrl: string;
            sourceLanguage: string;
            sourceRevision: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: int32 */
            paragraphCount: number;
        };
        ChapterListResponse: {
            items: components["schemas"]["ChapterSummary"][];
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
        ImportChapterRequest: {
            /**
             * Format: uri
             * @description Readable chapter URL, fetched on the server through the source provider.
             */
            url: string;
            /**
             * Format: uri
             * @description Optional matching book detail URL. The source chapter must belong to this book.
             */
            bookUrl?: string | null;
        };
        /** @enum {string} */
        ProviderKind: "GOOGLE_CLOUD" | "GOOGLE_WEB_TRANSLATE_HTML" | "DEEPSEEK" | "OPENAI_COMPATIBLE_LLM";
        TranslationProvider: {
            id: components["schemas"]["ProviderKind"];
            displayName: string;
            configured: boolean;
            requiresKey: boolean;
            defaultEndpoint: string;
            defaultModel: string;
        };
        TranslationProviderList: {
            providers: components["schemas"]["TranslationProvider"][];
        };
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
        /** @description The normalized settings used for this job, without credentials. Reuse these values with providerKind and retryOf to resume committed paragraphs. */
        TranslationJobSettings: {
            sourceLanguage: string;
            targetLanguage: string;
            endpoint: string;
            model: string;
            glossary: components["schemas"]["GlossaryEntry"][];
        };
        CreateTranslationJobRequest: {
            /** Format: uuid */
            chapterRecordId: string;
            providerKind: components["schemas"]["ProviderKind"];
            /** @description Target language must differ from source and must not be auto. */
            targetLanguage: string;
            /** Format: uuid */
            idempotencyKey: string;
            /** @description Defaults to the imported chapter language. */
            sourceLanguage?: string | null;
            /** @description LLM endpoint; must be allowed by server configuration. Never fetched by the browser. */
            endpoint?: string | null;
            model?: string | null;
            /** @description Memory-only credential for this execution. Must never be logged or written to job records, offline storage, URLs or response bodies. */
            apiKey?: string | null;
            /** @description Source terms must be unique after case folding and trimming. */
            glossary?: components["schemas"]["GlossaryEntry"][];
            /**
             * Format: uuid
             * @description Retry a FAILED, CANCELLED or INTERRUPTED job with the same chapter revision and normalized settings. The server copies committed paragraph checkpoints.
             */
            retryOf?: string | null;
        };
        /** @description Server-owned execution. COMPLETED references a full immutable saved translation; partial translations are not published. Cancellation preserves imported source and committed checkpoints. Expired workers become INTERRUPTED. */
        TranslationJob: {
            /** Format: uuid */
            jobId: string;
            /** @enum {string} */
            status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED" | "INTERRUPTED";
            /** Format: uuid */
            chapterRecordId: string;
            bookTitle: string;
            chapterTitle: string;
            providerKind: components["schemas"]["ProviderKind"];
            targetLanguage: string;
            settings: components["schemas"]["TranslationJobSettings"];
            /** Format: int32 */
            completedParagraphs: number;
            /** Format: int32 */
            totalParagraphs: number;
            /** Format: uuid */
            translationRecordId: string | null;
            errorCode: string | null;
            errorMessage: string | null;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
            canRetry: boolean;
        };
        TranslationJobListResponse: {
            items: components["schemas"]["TranslationJob"][];
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
        SourceFilterOption: {
            value: string;
            label: string;
        };
        SourceFilters: {
            genres: components["schemas"]["SourceFilterOption"][];
            sort: components["schemas"]["SourceFilterOption"][];
            status: components["schemas"]["SourceFilterOption"][];
            directions: components["schemas"]["SourceFilterOption"][];
        };
        /** @description Uploads extracted local document text only. Binary file assets are never accepted. Total paragraph text must not exceed 1,000,000 UTF-16 code units. */
        UploadedChapterRequest: {
            bookId: string;
            bookTitle: string;
            chapterId: string;
            chapterTitle: string;
            sourceLanguage: string;
            paragraphs: components["schemas"]["SourceParagraph"][];
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
    checkTranslationProvider: {
        parameters: {
            query?: never;
            header: {
                /** @description Use the token/header name from GET /api/v1/csrf. */
                "X-CSRF-TOKEN": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TranslationProviderCheckRequest"];
            };
        };
        responses: {
            /** @description A successful check or a safely classified provider failure. No provider response body, translated sample, key or endpoint is exposed. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationProviderCheckResponse"];
                };
            };
            /** @description Invalid settings. Known problem codes: INVALID_PROVIDER, PROVIDER_NOT_CONFIGURED, ENDPOINT_NOT_ALLOWED. Other input validation problems have no code. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description CSRF token or permission missing. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description The JSON request exceeds 16 KiB. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description PROVIDER_CHECK_BUSY: concurrent worker or cooldown limit reached. */
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
    listNovelSources: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NovelSourceList"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getNovelCatalog: {
        parameters: {
            query: {
                sourceId: string;
                url?: string;
                query?: string;
                page?: number;
                /** @description Pass a supported value from the selected source filters unchanged. */
                genre?: string;
                /** @description Pass a supported value from the selected source filters unchanged. */
                orderBy?: string;
                /** @description Pass a supported value from the selected source filters unchanged. */
                order?: string;
                /** @description Pass a supported value from the selected source filters unchanged. */
                status?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NovelCatalog"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getNovelDetail: {
        parameters: {
            query: {
                url: string;
                /** @description Zero-based result page. */
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NovelDetail"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listStoredChapters: {
        parameters: {
            query?: {
                /** @description Zero-based result page. */
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChapterListResponse"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    importChapter: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ImportChapterRequest"];
            };
        };
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StoredChapter"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getStoredChapter: {
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
            /** @description Successful response. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StoredChapter"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listTranslationProviders: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationProviderList"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listTranslationJobs: {
        parameters: {
            query?: {
                /** @description Zero-based result page. */
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationJobListResponse"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    createTranslationJob: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateTranslationJobRequest"];
            };
        };
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationJob"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getTranslationJob: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                jobId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationJob"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    cancelTranslationJob: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                jobId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TranslationJob"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    uploadLocalChapter: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UploadedChapterRequest"];
            };
        };
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    /** @description no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StoredChapter"];
                };
            };
            /** @description Invalid URL, pagination, provider or settings. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Forbidden or session CSRF token invalid. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Item not found for this authenticated user. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Conflicting idempotency request, retry settings or job state. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Too many active jobs. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Source provider failed or requires source-site authentication. */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["WorkflowProblem"];
                };
            };
            /** @description Source provider timed out. */
            504: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
