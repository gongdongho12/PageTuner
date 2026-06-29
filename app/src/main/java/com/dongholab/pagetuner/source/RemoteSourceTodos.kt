package com.dongholab.pagetuner.source

import androidx.annotation.StringRes
import com.dongholab.pagetuner.R

data class RemoteSourceTodo(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    @param:StringRes val phaseRes: Int,
)

object RemoteSourceTodos {
    val items: List<RemoteSourceTodo> = listOf(
        RemoteSourceTodo(
            titleRes = R.string.source_google_drive_title,
            descriptionRes = R.string.source_google_drive_description,
            phaseRes = R.string.source_phase_todo,
        ),
        RemoteSourceTodo(
            titleRes = R.string.source_ftp_title,
            descriptionRes = R.string.source_ftp_description,
            phaseRes = R.string.source_phase_todo,
        ),
        RemoteSourceTodo(
            titleRes = R.string.source_web_catalog_title,
            descriptionRes = R.string.source_web_catalog_description,
            phaseRes = R.string.source_phase_spec_draft,
        ),
    )
}
