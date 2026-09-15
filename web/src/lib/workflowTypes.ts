import type { components } from "../generated/workflow";

type Schemas = components["schemas"];
export type Page<T> = Schemas["Pagination"] & { items: T[] };
export type NovelSource = Schemas["NovelSource"];
export type CatalogFilters = Partial<
  Record<"genre" | "orderBy" | "order" | "status", string>
>;
export type NovelBook = Schemas["NovelBook"];
export type NovelCatalog = Schemas["NovelCatalog"];
export type NovelChapter = Schemas["NovelChapter"];
export type NovelDetail = Schemas["NovelDetail"];
export type StoredChapter = Schemas["StoredChapter"];
export type ChapterSummary = Schemas["ChapterSummary"];
export type ChapterListResponse = Schemas["ChapterListResponse"];
export type ProviderKind = Schemas["ProviderKind"];
export type TranslationProvider = Schemas["TranslationProvider"];
export type TranslationJobSettings = Schemas["TranslationJobSettings"];
export type TranslationJob = Schemas["TranslationJob"];
export type TranslationJobListResponse = Schemas["TranslationJobListResponse"];
export type StartTranslation = Schemas["CreateTranslationJobRequest"];
export type UploadChapter = Schemas["UploadedChapterRequest"];
