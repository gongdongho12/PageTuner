package com.dongholab.pagetuner.portable

/** A committed panel/options selection owns one terminal lease, never a reusable generation. */
internal class PortableAccountExportLease {
    private enum class State { NEW, ACTIVE, CLOSED }
    private var state = State.NEW

    @Synchronized fun activate() {
        if (state == State.NEW) state = State.ACTIVE
    }

    @Synchronized fun close() { state = State.CLOSED }

    @Synchronized fun check() {
        if (state != State.ACTIVE) throw PortableExportExpired()
    }

    /** A retained callback can only issue guards from its own still-active committed lease. */
    @Synchronized fun issue(): () -> Unit {
        check()
        return ::check
    }
}
