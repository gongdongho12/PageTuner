package com.dongholab.pagetuner.ui

import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.ui.screen.serverJobFailureLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerJobFailureLabelTest {
    @Test fun unknownServerTextCannotBecomeAnUnredactedProviderError() {
        assertEquals(R.string.server_job_error_provider, serverJobFailureLabel("secret-provider-exception"))
        assertEquals(R.string.server_job_error_provider, serverJobFailureLabel("TRANSLATION_FAILED"))
        assertEquals(R.string.server_job_error_credentials, serverJobFailureLabel("PROVIDER_CREDENTIALS"))
        assertEquals(R.string.server_job_error_limit, serverJobFailureLabel("QUOTA_EXCEEDED"))
    }
}
