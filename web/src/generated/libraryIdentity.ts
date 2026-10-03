// Generated from contracts/library-identity-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/library-identity/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["verifyLibraryIdentity"];
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
        OriginalDocumentIdentity: {
            /** @enum {integer} */
            version: 1;
            /** @enum {string} */
            kind: "ORIGINAL";
            contentProviderId: string;
            bookId: string;
            chapterId: string;
            sourceRevision: string;
            sourceLanguage: string;
            paragraphHash: string;
        };
        TranslationDocumentIdentity: {
            /** @enum {integer} */
            version: 1;
            /** @enum {string} */
            kind: "TRANSLATION";
            contentProviderId: string;
            bookId: string;
            chapterId: string;
            sourceRevision: string;
            sourceLanguage: string;
            paragraphHash: string;
            targetLanguage: string;
            translationProviderId: string;
            modelId: string;
            promptRevision: string;
            glossaryRevision: string;
            artifactId: string;
            revision: string;
            payloadHash: string;
        };
        LibraryDocumentIdentity: components["schemas"]["OriginalDocumentIdentity"] | components["schemas"]["TranslationDocumentIdentity"];
        VerifyLibraryIdentityRequest: {
            /** @enum {string} */
            kind: "ORIGINAL" | "TRANSLATION";
            /** Format: uuid */
            recordId: string;
            identity: components["schemas"]["LibraryDocumentIdentity"];
        };
        VerifyLibraryIdentityResponse: {
            /** @enum {string} */
            kind: "ORIGINAL" | "TRANSLATION";
            /** Format: uuid */
            recordId: string;
            identity: components["schemas"]["LibraryDocumentIdentity"];
            /** @enum {boolean} */
            verified: true;
        };
        LibraryIdentityProblem: {
            status: number;
            /** @enum {string} */
            code: "LIBRARY_IDENTITY_INVALID" | "LIBRARY_IDENTITY_NOT_FOUND" | "LIBRARY_IDENTITY_MISMATCH" | "LIBRARY_IDENTITY_UNAVAILABLE";
            detail?: string;
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
    verifyLibraryIdentity: {
        parameters: {
            query?: never;
            header: {
                /** @description Token from /api/v1/csrf with its session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["VerifyLibraryIdentityRequest"];
            };
        };
        responses: {
            /** @description Owned immutable record matches every identity field and ordered paragraph hash; no synchronization binding is created. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VerifyLibraryIdentityResponse"];
                };
            };
            /** @description Strict JSON, identity, kind or UUID is invalid. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["LibraryIdentityProblem"];
                };
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["LibraryIdentityProblem"];
                };
            };
            /** @description CSRF rejected. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["LibraryIdentityProblem"];
                };
            };
            /** @description Owned record does not exist; foreign and missing records are indistinguishable. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["LibraryIdentityProblem"];
                };
            };
            /** @description Identity mismatch or stored identity unavailable. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["LibraryIdentityProblem"];
                };
            };
            /** @description Request exceeds 64 KiB. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["LibraryIdentityProblem"];
                };
            };
        };
    };
}
