package com.dongholab.pagetuner.portable

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** One SAF launcher owns every callback; the request carries the already prepared file's MIME. */
class PortableCreateDocument : ActivityResultContract<PortableExportRequest, Uri?>() {
    override fun createIntent(context: Context, input: PortableExportRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType).putExtra(Intent.EXTRA_TITLE, input.filename)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}
