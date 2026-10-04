package com.dongholab.pagetuner.server.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExternalPostgresTestDatabaseTest {
    @Test
    fun `only an explicit disposable loopback database opts out of Testcontainers`() {
        assertNull(ExternalPostgresTestDatabase.fromEnvironment(emptyMap()))
        for (host in listOf("127.0.0.1", "localhost", "[::1]")) {
            val url = "jdbc:postgresql://$host:55432/pagetuner_test_local"
            val config = requireNotNull(ExternalPostgresTestDatabase.fromEnvironment(environment(url)))
            assertEquals(url, config.url)
            assertEquals("test-user", config.user)
        }
    }

    @Test
    fun `remote existing database names and alternate connection options are rejected before I O`() {
        for (url in listOf(
            "jdbc:postgresql://db.example/pagetuner_test",
            "jdbc:postgresql://127.0.0.1/pagetuner",
            "jdbc:postgresql://127.0.0.1/pagetuner_test?host=remote.example",
            "jdbc:postgresql://user:password@127.0.0.1/pagetuner_test",
            "jdbc:postgresql://127.0.0.1/pagetuner_test#fragment",
            "jdbc:postgresql://127.0.0.1/pagetuner_test%2Fproduction",
            "jdbc:postgresql:pagetuner_test",
            "",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ExternalPostgresTestDatabase.fromEnvironment(environment(url))
            }
        }
    }

    @Test
    fun `partial environment configuration cannot silently fall back to Docker`() {
        for (environment in listOf(
            mapOf("PAGETUNER_TEST_DATABASE_USER" to "test-user"),
            mapOf("PAGETUNER_TEST_DATABASE_URL" to "jdbc:postgresql://localhost/pagetuner_test"),
            environment("jdbc:postgresql://localhost/pagetuner_test") - "PAGETUNER_TEST_DATABASE_PASSWORD",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ExternalPostgresTestDatabase.fromEnvironment(environment)
            }
        }
    }

    private fun environment(url: String) = mapOf(
        "PAGETUNER_TEST_DATABASE_URL" to url,
        "PAGETUNER_TEST_DATABASE_USER" to "test-user",
        "PAGETUNER_TEST_DATABASE_PASSWORD" to "test-password",
    )
}
