// Generated from contracts/local-sharing-v1.openapi.json. Do not edit.
export interface paths {
    "/api/share/v1/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getSharingStatus"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/share/v1/pair": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["pairSharingSession"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/share/v1/session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["disconnectSharingSession"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/share/v1/books": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listSharedBooks"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/share/v1/books/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        get: operations["getSharedDocument"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/share/v1/books/{id}/assets/{assetId}": {
        parameters: {
            query: {
                revision: string;
            };
            header?: never;
            path: {
                id: string;
                assetId: string;
            };
            cookie?: never;
        };
        get: operations["getSharedAsset"];
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
        SharingStatus: {
            /** @enum {integer} */
            version: 1;
            /** @enum {boolean} */
            readOnly: true;
            /** Format: int64 */
            expiresAt: number;
        };
        PairRequest: {
            code: string;
        };
        PairResponse: {
            token: string;
            /** Format: int64 */
            expiresAt: number;
        };
        SharedBookSummary: {
            id: string;
            title: string;
            /** @enum {string} */
            format: "txt" | "markdown" | "epub" | "pdf";
            /** @enum {string} */
            edition: "original" | "translation";
        };
        SharedLibraryPage: {
            items: components["schemas"]["SharedBookSummary"][];
            total: number;
            offset: number;
            limit: number;
        };
        SharedParagraph: {
            paragraphId: string;
            text: string;
        };
        SharedOutlineItem: {
            title: string;
            paragraphId: string;
        };
        SharedReadingAnchor: {
            paragraphId: string;
            /** @description Canonical paragraph UTF-16 code-unit offset, never a screen page number. */
            characterOffset: number;
        };
        SharedAsset: {
            id: string;
            mimeType: string;
            /** Format: int64 */
            byteLength: number;
            /** @enum {string} */
            role: "pdf" | "image";
            paragraphId?: string | null;
            alt: string;
        };
        SharedDocument: {
            id: string;
            title: string;
            /** @enum {string} */
            format: "txt" | "markdown" | "epub" | "pdf";
            /** @enum {string} */
            edition: "original" | "translation";
            language: string;
            /** @description Opaque snapshot revision; asset requests must use this exact value. */
            revision: string;
            paragraphs: components["schemas"]["SharedParagraph"][];
            outline: components["schemas"]["SharedOutlineItem"][];
            assets: components["schemas"]["SharedAsset"][];
            anchor?: components["schemas"]["SharedReadingAnchor"] | null;
        };
        SharingError: {
            code: string;
            message: string;
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
    getSharingStatus: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingStatus"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
        };
    };
    pairSharingSession: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PairRequest"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PairResponse"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
        };
    };
    disconnectSharingSession: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description This browser session has been invalidated. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
        };
    };
    listSharedBooks: {
        parameters: {
            query?: {
                offset?: number;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharedLibraryPage"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
        };
    };
    getSharedDocument: {
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
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharedDocument"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
        };
    };
    getSharedAsset: {
        parameters: {
            query: {
                revision: string;
            };
            header?: never;
            path: {
                id: string;
                assetId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Pinned PDF or bitmap bytes; MIME type matches the document asset descriptor. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/octet-stream": string;
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
            /** @description Request rejected; code is stable and messages contain no paths or credentials. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SharingError"];
                };
            };
        };
    };
}
