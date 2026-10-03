package com.dongholab.pagetuner.translation.sync

enum class PortableIdentityFailure { Mismatch, Unavailable, NotFound }
class PortableIdentityVerificationException(val reason: PortableIdentityFailure) : Exception("Portable document verification failed: $reason")
