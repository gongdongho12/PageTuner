// Generated from contracts/accounts-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/accounts/languages": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listAccountLanguages"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounts/csrf": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getPublicAccountCsrf"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounts/register": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["registerAccount"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounts/me/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Change the authenticated account password after checking currentPassword. Passwords are never trimmed. Maximum request body: 4096 UTF-8 bytes. Five attempts per account per 15-minute window, with a bounded process-local limiter. Every subsequent request revalidates Basic credentials; existing session cookies carry CSRF tokens only and cannot authenticate the old password. On success discard the old credentials and sign in with the new password. Account identity, profile and library ownership remain unchanged. */
        post: operations["changeMyPassword"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/accounts/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMyAccount"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** @description Idempotently replace displayName, locale and targetLanguage; all three are required. Obtain an authenticated token from GET /api/v1/csrf. */
        patch: operations["updateMyAccount"];
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        ChangePasswordRequest: {
            /** @description Exact current password; whitespace is preserved. Never persisted or logged in plaintext. */
            currentPassword: string;
            /** @description At least 10 Unicode code points, at most 72 UTF-8 bytes, no ISO control characters, and different from currentPassword. Whitespace is preserved. */
            newPassword: string;
        };
        AccountProblem: {
            type: string;
            title: string;
            status: number;
            detail: string;
            /** @description Stable account error code when supplied. Never contains credentials. */
            code?: string;
        };
        AccountProfile: {
            /** Format: uuid */
            accountId: string;
            username: string;
            displayName: string;
            /** @description Valid BCP 47 preference. ko-* resolves to ko, en-* to en; other registered or future locales currently fall back to en. */
            locale: string;
            /** @description Concrete BCP 47 translation target; auto is not permitted. */
            targetLanguage: string;
            /** @description Available interface pack used for this preference. Initially ko or en; additional packs may be registered without changing the account shape. */
            effectiveLocale: string;
        };
        AccountPreferences: {
            displayName: string;
            /** @description Valid BCP 47 preference. ko-* resolves to ko, en-* to en; other registered or future locales currently fall back to en. */
            locale: string;
            /** @description Concrete BCP 47 translation target; auto is not permitted. */
            targetLanguage: string;
        };
        RegisterAccountRequest: {
            /** @description Trimmed and normalized to lowercase. */
            username: string;
            /** @description Whitespace is preserved. At most 72 UTF-8 bytes; control characters are prohibited. */
            password: string;
            displayName: string;
            /** @description Valid BCP 47 preference. ko-* resolves to ko, en-* to en; other registered or future locales currently fall back to en. */
            locale: string;
            /** @description Concrete BCP 47 translation target; auto is not permitted. */
            targetLanguage: string;
        };
        AccountLanguage: {
            tag: string;
            nativeName: string;
            displayName: string;
            available: boolean;
            fallbackTag: string;
        };
        AccountLanguages: {
            items: components["schemas"]["AccountLanguage"][];
            defaultTag: string;
        };
        CsrfResponse: {
            token: string;
            headerName: string;
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
    listAccountLanguages: {
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
                    "application/json": components["schemas"]["AccountLanguages"];
                };
            };
            /** @description Invalid account or language preference. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Invalid CSRF token or forbidden. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Username is unavailable. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getPublicAccountCsrf: {
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
                    "application/json": components["schemas"]["CsrfResponse"];
                };
            };
            /** @description Invalid account or language preference. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Invalid CSRF token or forbidden. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Username is unavailable. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    registerAccount: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RegisterAccountRequest"];
            };
        };
        responses: {
            /** @description Successful response. */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccountProfile"];
                };
            };
            /** @description Invalid account or language preference. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Invalid CSRF token or forbidden. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Username is unavailable. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    changeMyPassword: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangePasswordRequest"];
            };
        };
        responses: {
            /** @description Password changed; empty response body. */
            204: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description CURRENT_PASSWORD_INCORRECT, PASSWORD_UNCHANGED or INVALID_PASSWORD. Malformed JSON also returns 400. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["AccountProblem"];
                };
            };
            /** @description Current Basic credentials are required. */
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
            /** @description Account no longer exists. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description PASSWORD_CHANGE_CONFLICT: another request replaced the password before this update committed. Sign in again. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["AccountProblem"];
                };
            };
            /** @description Request body exceeds 4096 bytes, including chunked requests. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description PASSWORD_CHANGE_LIMIT: five attempts in this account's 15-minute window. The existing LOGIN_LIMIT can also reject repeated failed Basic authentication. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["AccountProblem"];
                };
            };
        };
    };
    getMyAccount: {
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
                    "application/json": components["schemas"]["AccountProfile"];
                };
            };
            /** @description Invalid account or language preference. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Invalid CSRF token or forbidden. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Username is unavailable. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    updateMyAccount: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AccountPreferences"];
            };
        };
        responses: {
            /** @description Successful response. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccountProfile"];
                };
            };
            /** @description Invalid account or language preference. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Invalid CSRF token or forbidden. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Username is unavailable. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
