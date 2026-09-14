package com.dongholab.pagetuner.server.translation

import java.net.URI

/** Explicit opt-in for a disposable local database; these tests delete table contents. */
internal class ExternalPostgresTestDatabase(val url: String, val user: String, val password: String) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): ExternalPostgresTestDatabase? {
            val url = environment["PAGETUNER_TEST_DATABASE_URL"]
            val user = environment["PAGETUNER_TEST_DATABASE_USER"]
            val password = environment["PAGETUNER_TEST_DATABASE_PASSWORD"]
            if (url == null) {
                require(user == null && password == null) {
                    "Set PAGETUNER_TEST_DATABASE_URL with the explicit test database credentials, or leave all three unset."
                }
                return null
            }
            require(url.startsWith("jdbc:postgresql://")) { "The test database must use an explicit PostgreSQL JDBC URL." }
            val uri = URI(url.removePrefix("jdbc:"))
            require(uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")) {
                "The external test database must be on loopback."
            }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Test database credentials and connection options must not be embedded in the JDBC URL."
            }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid test database port." }
            require(uri.rawPath.orEmpty().matches(Regex("/pagetuner_test(?:_[a-z0-9_]+)?"))) {
                "The disposable database name must be pagetuner_test or start with pagetuner_test_. Its translation tables will be cleared."
            }
            require(!user.isNullOrBlank() && password != null) {
                "Set PAGETUNER_TEST_DATABASE_USER and PAGETUNER_TEST_DATABASE_PASSWORD for the disposable database."
            }
            return ExternalPostgresTestDatabase(url, user, password)
        }
    }
}
