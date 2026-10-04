package com.dongholab.pagetuner.server.organization

import com.dongholab.pagetuner.core.model.library.LibraryFilter

/** Values stay bound parameters; only these fixed table aliases and kind constants enter SQL. */
internal class LibraryFilterSql(filter: LibraryFilter, kind: LibraryOrganizationKind) {
    val join: String
    val predicates: String
    val arguments: List<Any>

    init {
        filter.validate()
        join = if (filter.folder != null || filter.tag != null || filter.favorite != null) {
            "left join library_organization organization on organization.user_id=document.user_id " +
                "and organization.kind='${kind.name}' and organization.record_id=document.id"
        } else ""
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()
        filter.q?.takeIf(String::isNotEmpty)?.let { search ->
            val literal = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
            conditions += "(document.book_title ilike ? escape '!' or document.chapter_title ilike ? escape '!')"
            values += literal; values += literal
        }
        filter.folder?.let {
            conditions += "coalesce(organization.organization_json::jsonb ->> 'folder', '') = ?"
            values += it
        }
        filter.tag?.let {
            conditions += "(organization.organization_json::jsonb -> 'tags') @> jsonb_build_array(cast(? as text))"
            values += it
        }
        filter.favorite?.let {
            conditions += "coalesce((organization.organization_json::jsonb ->> 'favorite')::boolean, false) = ?"
            values += it
        }
        predicates = conditions.joinToString(separator = "", transform = { " and $it" })
        arguments = values
    }
}

/** Spring's Boolean converter also accepts yes/1; this contract deliberately accepts only true/false. */
internal fun libraryFilterRequest(q: String?, folder: String?, tag: String?, favorite: String?): LibraryFilter =
    LibraryFilter(q, folder, tag, favorite?.let {
        require(it == "true" || it == "false") { "Invalid library favorite filter." }
        it == "true"
    }).also(LibraryFilter::validate)
