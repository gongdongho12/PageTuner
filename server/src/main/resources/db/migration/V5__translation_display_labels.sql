alter table translation_artifact add column book_title text;
alter table translation_artifact add column chapter_title text;
alter table translation_artifact alter column provider_book_id type text;
alter table translation_artifact alter column chapter_id type text;
alter table translation_artifact alter column translation_provider_id type text;
alter table translation_artifact alter column model_id type text;
