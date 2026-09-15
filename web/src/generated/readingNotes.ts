// Generated from contracts/reading-notes-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/reading-notes/{kind}/{recordId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                kind: components["schemas"]["ReadingNoteDocumentKind"];
                recordId: string;
            };
            cookie?: never;
        };
        get: operations["readingNoteChanges"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/reading-notes/{kind}/{recordId}/{noteId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                kind: components["schemas"]["ReadingNoteDocumentKind"];
                recordId: string;
                noteId: string;
            };
            cookie?: never;
        };
        get?: never;
        put: operations["putReadingNote"];
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
        ReadingNoteDocumentKind: "ORIGINAL" | "TRANSLATION";
        /** @enum {string} */
        ReadingNoteKind: "BOOKMARK" | "NOTE" | "HIGHLIGHT";
        ReadingNoteAnchor: {
            /** @description Persisted paragraph ID, no ISO controls; UTF-16 length. */
            paragraphId: string;
            /** @description UTF-16 offset; never splits a surrogate pair. */
            characterOffset: number;
        };
        ReadingNoteRange: {
            start: components["schemas"]["ReadingNoteAnchor"];
            end: components["schemas"]["ReadingNoteAnchor"];
        };
        ReadingNoteInput: {
            kind: components["schemas"]["ReadingNoteKind"];
            /** @description Nonblank, UTF-16 length. */
            title: string;
            /** @description Nonblank for NOTE; UTF-16 length. */
            text: string;
            anchor: components["schemas"]["ReadingNoteAnchor"];
            /** @description HIGHLIGHT requires start=anchor, nonblank exact selected text <=4000 UTF-16 units; null for other kinds. */
            range: components["schemas"]["ReadingNoteRange"] | null;
            /**
             * Format: date-time
             * @description UTC Z timestamp, year0001..9999 and optional1..9fractionaldigits. Metadata only.
             */
            createdAt: string;
        };
        ReadingNote: {
            kind: components["schemas"]["ReadingNoteKind"];
            title: string;
            text: string;
            anchor: components["schemas"]["ReadingNoteAnchor"];
            range: components["schemas"]["ReadingNoteRange"] | null;
            /** Format: date-time */
            createdAt: string;
            /** @description Server-derived exact highlight text, or <=1000 UTF-16 units after an ordinary anchor. */
            excerpt: string;
        };
        PutReadingNoteRequest: {
            expectedVersion: number;
            /** Format: uuid */
            mutationId: string;
            deleted: boolean;
            /** @description Null iff deleted=true. */
            note: components["schemas"]["ReadingNoteInput"] | null;
        };
        ReadingNoteItem: {
            /** Format: uuid */
            noteId: string;
            version: number;
            changeRevision: number;
            deleted: boolean;
            note: components["schemas"]["ReadingNote"] | null;
            /**
             * Format: date-time
             * @description Null only for a never-created version-zero conflict view.
             */
            updatedAt: string | null;
        };
        ReadingNoteChanges: {
            kind: components["schemas"]["ReadingNoteDocumentKind"];
            /** Format: uuid */
            recordId: string;
            items: components["schemas"]["ReadingNoteItem"][];
            nextAfterRevision: number;
            watermark: number;
            hasMore: boolean;
        };
        ReadingNoteProblem: {
            type?: string;
            title?: string;
            status: number;
            detail?: string;
            instance?: string;
            code?: string;
            current?: components["schemas"]["ReadingNoteItem"];
        };
    };
    responses: {
        /** @description Invalid request, inaccessible document, or body limit. */
        ReadingNoteError: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["ReadingNoteProblem"];
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
    readingNoteChanges: {
        parameters: {
            query?: {
                afterRevision?: number;
                /** @description Captured watermark for subsequent pages; must be between afterRevision and current committed document revision. */
                untilRevision?: number;
                limit?: number;
            };
            header?: never;
            path: {
                kind: components["schemas"]["ReadingNoteDocumentKind"];
                recordId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Immutable changes at a fixed committed watermark. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReadingNoteChanges"];
                };
            };
            400: components["responses"]["ReadingNoteError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            404: components["responses"]["ReadingNoteError"];
        };
    };
    putReadingNote: {
        parameters: {
            query?: never;
            header: {
                /** @description Use /api/v1/csrf and its matching session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path: {
                kind: components["schemas"]["ReadingNoteDocumentKind"];
                recordId: string;
                noteId: string;
            };
            cookie?: never;
        };
        /** @description At most 32 KiB. Retry uncertain requests without changing their mutation ID or payload. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutReadingNoteRequest"];
            };
        };
        responses: {
            /** @description Committed note or tombstone; an exact latest-mutation retry returns the same item. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReadingNoteItem"];
                };
            };
            400: components["responses"]["ReadingNoteError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description CSRF token required. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            404: components["responses"]["ReadingNoteError"];
            /** @description Stale expectedVersion includes current; safe-integer exhaustion has code READING_NOTE_REVISION_EXHAUSTED without current. */
            409: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ReadingNoteProblem"];
                };
            };
            413: components["responses"]["ReadingNoteError"];
            /** @description Account write limit; retain pending changes and obey Retry-After. */
            429: {
                headers: {
                    "Retry-After": number;
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["ReadingNoteProblem"];
                };
            };
        };
    };
}
