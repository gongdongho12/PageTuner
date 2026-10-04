// Generated from contracts/pdf-content-v1.openapi.json. Do not edit.
export interface paths {
    "/api/v1/pdf-content": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["uploadPdfContent"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/pdf-content/{recordId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getPdfContent"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/pdf-content/{recordId}/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["verifyPdfContent"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/pdf-content/{recordId}/original": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["downloadOriginalPdfContent"];
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
        /** @description Paragraph IDs are unique and nonblank. All strings reject unpaired surrogates and retain exact content, including empty text. */
        PdfContentParagraph: {
            paragraphId: string;
            text: string;
        };
        /** @description Both nullable fields are required. Exactly one PDF role, with paragraphId null. Ordered image references may repeat. */
        PdfContentAssetReference: {
            path: string;
            /** @enum {string} */
            role: "pdf" | "image";
            paragraphId: string | null;
            alt: string | null;
        };
        PdfContentPayload: {
            path: string;
            /** @enum {string} */
            mimeType: "application/pdf" | "image/png" | "image/jpeg" | "image/webp" | "image/gif";
            /**
             * Format: byte
             * @description Canonical padded RFC 4648 standard alphabet. No whitespace, URL alphabet, missing padding or nonzero unused bits. Actual bytes must hash to path.
             */
            base64: string;
        };
        /** @description PDF storage only. Unique decoded payload bytes total at most 4194304, counting the sole original PDF once. All payloads referenced, no duplicate paths. Sum of language, paragraph IDs/texts, and non-null reference paragraphIds/alts is at most 262144 UTF-16 units. Paths/MIME are separately bounded. No source provenance, notes, positions, organization, glossary, extensions or caller-asserted proof. */
        PdfContentDocument: {
            /** @enum {integer} */
            version: 1;
            /** @description Exact case and spelling; never normalize. */
            language: string;
            paragraphs: components["schemas"]["PdfContentParagraph"][];
            assets: components["schemas"]["PdfContentAssetReference"][];
            payloads: components["schemas"]["PdfContentPayload"][];
        };
        /** @description Same account/uploadId with the same full ordered content replays the original receipt. Reuse with different content is 409, even if proof is unchanged by payload-array reordering. */
        PdfContentUpload: {
            /** Format: uuid */
            uploadId: string;
            content: components["schemas"]["PdfContentDocument"];
        };
        PdfContentAssetProof: {
            path: string;
            /** @enum {string} */
            role: "pdf" | "image";
            paragraphId: string | null;
            alt: string | null;
            /** @enum {string} */
            mimeType: "application/pdf" | "image/png" | "image/jpeg" | "image/webp" | "image/gif";
            byteLength: number;
            sha256: string;
        };
        /** @description Strict PDF subset of portable-content-proof-v1; all required fields, exact ordered references, validated framed digest. Actual stored bytes are recomputed before success. Unique payload lengths total at most 4 MiB; up to 64 unique paths. A proof is not PDF decoding, page-count validation, provenance, or sync authority. */
        PdfContentProof: {
            /** @enum {integer} */
            version: 1;
            /** @enum {string} */
            representation: "PDF";
            /** @description Exact case and spelling; never normalize. */
            language: string;
            paragraphHash: string;
            originalFileByteLength: number;
            originalFileSha256: string;
            assets: components["schemas"]["PdfContentAssetProof"][];
            sha256: string;
        };
        /** @description Immutable account-owned content snapshot; ID is not an ORIGINAL/TRANSLATION record ID. */
        PdfContentReceipt: {
            /** Format: uuid */
            recordId: string;
            /** Format: date-time */
            createdAt: string;
            proof: components["schemas"]["PdfContentProof"];
        };
        PdfContentView: {
            /** Format: uuid */
            recordId: string;
            /** Format: date-time */
            createdAt: string;
            content: components["schemas"]["PdfContentDocument"];
            proof: components["schemas"]["PdfContentProof"];
        };
        VerifyPdfContentRequest: {
            proof: components["schemas"]["PdfContentProof"];
        };
        /** @description Content bytes and complete proof match this owned snapshot. No binding or S1-S3 mutation. */
        VerifyPdfContentResponse: {
            /** Format: uuid */
            recordId: string;
            /** @enum {boolean} */
            verified: true;
            proof: components["schemas"]["PdfContentProof"];
        };
        PdfContentProblem: {
            type: string;
            title: string;
            status: number;
            detail: string;
            instance?: string;
            /** @enum {string} */
            code?: "PDF_CONTENT_INVALID" | "PDF_CONTENT_UPLOAD_REUSED" | "PDF_CONTENT_NOT_FOUND" | "PDF_CONTENT_MISMATCH" | "PDF_CONTENT_UNAVAILABLE" | "PDF_CONTENT_TOO_LARGE" | "PDF_CONTENT_ENCODING";
        };
    };
    responses: {
        /** @description Request rejected or stored content unavailable. No partial writes or inferred binding. */
        PdfContentError: {
            headers: {
                "Cache-Control"?: "no-store";
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["PdfContentProblem"];
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
    uploadPdfContent: {
        parameters: {
            query?: never;
            header: {
                /** @description Matching authenticated session CSRF token. */
                "X-CSRF-TOKEN": string;
                /** @description Absent or identity only; compressed/stacked encodings are rejected before JSON decoding. */
                "Content-Encoding"?: "identity";
            };
            path?: never;
            cookie?: never;
        };
        /** @description Strict uncompressed JSON at most 8 MiB, including chunked bodies. Duplicate/unknown/missing fields and trailing JSON fail. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["PdfContentUpload"];
            };
        };
        responses: {
            /** @description Stored immutable snapshot or exact upload replay. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PdfContentReceipt"];
                };
            };
            400: components["responses"]["PdfContentError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing/invalid CSRF or forbidden request. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing and foreign owned snapshot IDs are indistinguishable. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["PdfContentProblem"];
                };
            };
            409: components["responses"]["PdfContentError"];
            413: components["responses"]["PdfContentError"];
            415: components["responses"]["PdfContentError"];
        };
    };
    getPdfContent: {
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
            /** @description Content and proof recomputed from actual owned stored bytes. Response stays within 12 MiB JSON because references occur in both content and proof; upload request limit stays 8 MiB. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PdfContentView"];
                };
            };
            400: components["responses"]["PdfContentError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing/invalid CSRF or forbidden request. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing and foreign owned snapshot IDs are indistinguishable. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["PdfContentProblem"];
                };
            };
            409: components["responses"]["PdfContentError"];
            413: components["responses"]["PdfContentError"];
            415: components["responses"]["PdfContentError"];
        };
    };
    verifyPdfContent: {
        parameters: {
            query?: never;
            header: {
                /** @description Matching authenticated session CSRF token. */
                "X-CSRF-TOKEN": string;
                /** @description Absent or identity only; compressed/stacked encodings are rejected before JSON decoding. */
                "Content-Encoding"?: "identity";
            };
            path: {
                recordId: string;
            };
            cookie?: never;
        };
        /** @description Strict uncompressed JSON at most 2 MiB. Reads only; validates every proof field and recomputes owned stored content. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["VerifyPdfContentRequest"];
            };
        };
        responses: {
            /** @description Full proof matches; no records or bindings are created. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VerifyPdfContentResponse"];
                };
            };
            400: components["responses"]["PdfContentError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing/invalid CSRF or forbidden request. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing and foreign owned snapshot IDs are indistinguishable. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["PdfContentProblem"];
                };
            };
            409: components["responses"]["PdfContentError"];
            413: components["responses"]["PdfContentError"];
            415: components["responses"]["PdfContentError"];
        };
    };
    downloadOriginalPdfContent: {
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
            /** @description Exact original bytes after complete stored-content revalidation. File validity/page count are not attested. */
            200: {
                headers: {
                    "Cache-Control"?: "no-store";
                    /** @description attachment; filename="<recordId>.pdf" (server-generated fixed UUID name). */
                    "Content-Disposition"?: string;
                    "X-Content-Type-Options"?: "nosniff";
                    [name: string]: unknown;
                };
                content: {
                    "application/pdf": string;
                };
            };
            400: components["responses"]["PdfContentError"];
            /** @description Authentication required. */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing/invalid CSRF or forbidden request. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Missing and foreign owned snapshot IDs are indistinguishable. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["PdfContentProblem"];
                };
            };
            409: components["responses"]["PdfContentError"];
            413: components["responses"]["PdfContentError"];
            415: components["responses"]["PdfContentError"];
        };
    };
}
