// Generated from contracts/library-organization-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/library-organization/{kind}/{recordId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                kind: components["schemas"]["LibraryOrganizationKind"];
                /** @description Complete hyphenated UUID of an owned server record. */
                recordId: string;
            };
            cookie?: never;
        };
        get: operations["getLibraryOrganization"];
        put: operations["putLibraryOrganization"];
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
        LibraryOrganization: {
            /** @description 0-200 UTF-16 units, canonical trimmed string; empty means no folder. No controls or unpaired surrogates. See the normative Markdown contract. */
            folder: string;
            /** @description Order preserved, exact case-sensitive uniqueness. No Unicode normalization. */
            tags: string[];
            favorite: boolean;
        };
        LibraryOrganizationView: {
            kind: components["schemas"]["LibraryOrganizationKind"];
            /** Format: uuid */
            recordId: string;
            version: number;
            /**
             * Format: date-time
             * @description Server UTC timestamp; informational, never a concurrency token.
             */
            updatedAt: string | null;
            organization: components["schemas"]["LibraryOrganization"] | null;
        };
        PutLibraryOrganizationRequest: {
            expectedVersion: number;
            /**
             * Format: uuid
             * @description Generate for each new update; preserve the entire request when retrying response loss.
             */
            mutationId: string;
            organization: components["schemas"]["LibraryOrganization"];
        };
        LibraryOrganizationProblem: {
            type?: string;
            title?: string;
            status: number;
            detail?: string;
            instance?: string;
            code?: string;
        };
        LibraryOrganizationConflict: {
            type?: string;
            title?: string;
            /** @enum {integer} */
            status: 409;
            detail?: string;
            instance?: string;
            /** @enum {string} */
            code: "LIBRARY_ORGANIZATION_CONFLICT";
            current: components["schemas"]["LibraryOrganizationView"];
        };
        LibraryOrganizationExhausted: {
            type?: string;
            title?: string;
            /** @enum {integer} */
            status: 409;
            detail?: string;
            instance?: string;
            /** @enum {string} */
            code: "LIBRARY_ORGANIZATION_EXHAUSTED";
        };
        /** @enum {string} */
        LibraryOrganizationKind: "ORIGINAL" | "TRANSLATION";
    };
    responses: {
        /** @description Current organization for the owned document. Version zero has null organization and updatedAt; positive versions have both. GET never creates defaults. */
        LibraryOrganizationSuccess: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["LibraryOrganizationView"];
            };
        };
        /** @description Invalid request, missing/foreign document or body limit. See the normative Markdown error codes. */
        LibraryOrganizationError: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["LibraryOrganizationProblem"];
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
    getLibraryOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                kind: components["schemas"]["LibraryOrganizationKind"];
                /** @description Complete hyphenated UUID of an owned server record. */
                recordId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["LibraryOrganizationSuccess"];
            400: components["responses"]["LibraryOrganizationError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            404: components["responses"]["LibraryOrganizationError"];
        };
    };
    putLibraryOrganization: {
        parameters: {
            query?: never;
            header: {
                /** @description Use /api/v1/csrf and its matching session cookie. */
                "X-CSRF-TOKEN": string;
            };
            path: {
                kind: components["schemas"]["LibraryOrganizationKind"];
                /** @description Complete hyphenated UUID of an owned server record. */
                recordId: string;
            };
            cookie?: never;
        };
        /** @description At most 8 KiB, including chunked JSON. Preserve the complete request for response-loss retries. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutLibraryOrganizationRequest"];
            };
        };
        responses: {
            200: components["responses"]["LibraryOrganizationSuccess"];
            400: components["responses"]["LibraryOrganizationError"];
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
            404: components["responses"]["LibraryOrganizationError"];
            /** @description CONFLICT includes current; preserve pending local organization for an explicit choice. EXHAUSTED is terminal and omits current. */
            409: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["LibraryOrganizationConflict"] | components["schemas"]["LibraryOrganizationExhausted"];
                };
            };
            413: components["responses"]["LibraryOrganizationError"];
            /** @description Per-account write limit. Preserve pending values and obey Retry-After. */
            429: {
                headers: {
                    "Cache-Control"?: "no-store";
                    "Retry-After": number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["LibraryOrganizationProblem"];
                };
            };
        };
    };
}
