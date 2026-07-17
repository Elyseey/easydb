package com.easydb.drivers.tdengine

import com.easydb.common.ConnectionConfig
import java.net.URI
import java.util.Locale

class TdengineIntegrationTestConfig private constructor(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val database: String,
    val precisionDatabases: Map<String, String>,
    val allowDdl: Boolean
) {
    override fun toString(): String =
        "TdengineIntegrationTestConfig(jdbcUrl=<redacted>, username=$username, password=<redacted>, " +
            "database=$database, precisionDatabases=${precisionDatabases.keys.sorted()}, allowDdl=$allowDdl)"

    fun toConnectionConfig(targetDatabase: String = database): ConnectionConfig {
        val uri = URI.create(jdbcUrl.substring("jdbc:".length))
        val host = requireNotNull(uri.host) { "EASYDB_TDENGINE_JDBC_URL must contain a host" }
        val port = uri.port.takeIf { it != -1 } ?: 6041

        return ConnectionConfig(
            id = "tdengine-integration-test",
            name = "TDengine integration test",
            dbType = "tdengine",
            host = host,
            port = port,
            username = username,
            password = password,
            database = targetDatabase
        )
    }

    companion object {
        private const val ENABLED_ENV = "EASYDB_TDENGINE_INTEGRATION_TEST"
        private const val JDBC_URL_ENV = "EASYDB_TDENGINE_JDBC_URL"
        private const val USERNAME_ENV = "EASYDB_TDENGINE_USERNAME"
        private const val PASSWORD_ENV = "EASYDB_TDENGINE_PASSWORD"
        private const val DATABASE_ENV = "EASYDB_TDENGINE_TEST_DATABASE"
        private const val PRECISION_DATABASES_ENV = "EASYDB_TDENGINE_PRECISION_DATABASES"
        private const val ALLOW_DDL_ENV = "EASYDB_TDENGINE_ALLOW_DDL"
        private const val SAFE_DATABASE_PREFIX = "EASYDB_TEST"

        private val credentialInUrl = Regex(
            "(?i)(?:[?;&]|^)(?:user|username|password|pwd|bearerToken)\\s*=|://[^/@\\s]+:[^/@\\s]+@"
        )

        fun fromEnvironment(
            environment: Map<String, String> = System.getenv()
        ): TdengineIntegrationTestConfig {
            require(environment[ENABLED_ENV].equals("true", ignoreCase = true)) {
                "$ENABLED_ENV must be true before any TDengine integration test can run"
            }

            val jdbcUrl = environment.required(JDBC_URL_ENV)
            require(jdbcUrl.startsWith("jdbc:TAOS-WS://", ignoreCase = true)) {
                "$JDBC_URL_ENV must use the jdbc:TAOS-WS:// scheme"
            }
            require(!jdbcUrl.contains('\n') && !jdbcUrl.contains('\r')) {
                "$JDBC_URL_ENV must be a single line"
            }
            require(!credentialInUrl.containsMatchIn(jdbcUrl)) {
                "$JDBC_URL_ENV must not contain credentials; use the dedicated username/password variables"
            }

            val username = environment.required(USERNAME_ENV)
            require(!username.equals("root", ignoreCase = true)) {
                "$USERNAME_ENV must be a dedicated non-root test account"
            }

            val database = environment.required(DATABASE_ENV)
            val precisionDatabases = parsePrecisionDatabases(environment.required(PRECISION_DATABASES_ENV))
            val allowDdl = environment[ALLOW_DDL_ENV].toStrictBoolean(ALLOW_DDL_ENV)
            if (allowDdl) {
                require(database.uppercase(Locale.ROOT).startsWith(SAFE_DATABASE_PREFIX)) {
                    "$DATABASE_ENV must start with $SAFE_DATABASE_PREFIX when DDL tests are enabled"
                }
                precisionDatabases.values.forEach { precisionDatabase ->
                    require(precisionDatabase.uppercase(Locale.ROOT).startsWith(SAFE_DATABASE_PREFIX)) {
                        "$PRECISION_DATABASES_ENV databases must start with $SAFE_DATABASE_PREFIX when DDL tests are enabled"
                    }
                }
            }

            return TdengineIntegrationTestConfig(
                jdbcUrl = jdbcUrl,
                username = username,
                password = environment.required(PASSWORD_ENV),
                database = database,
                precisionDatabases = precisionDatabases,
                allowDdl = allowDdl
            )
        }

        private fun parsePrecisionDatabases(value: String): Map<String, String> {
            val entries = value.split(',').associate { entry ->
                val parts = entry.split('=', limit = 2).map(String::trim)
                require(parts.size == 2 && parts.all { it.isNotEmpty() }) {
                    "$PRECISION_DATABASES_ENV must use ms=<db>,us=<db>,ns=<db>"
                }
                parts[0].lowercase(Locale.ROOT) to parts[1]
            }
            require(entries.keys == setOf("ms", "us", "ns")) {
                "$PRECISION_DATABASES_ENV must define exactly ms, us and ns"
            }
            require(entries.values.toSet().size == entries.size) {
                "$PRECISION_DATABASES_ENV must use a different database for each precision"
            }
            return entries
        }

        private fun Map<String, String>.required(name: String): String =
            this[name]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("$name is required when TDengine integration tests are enabled")

        private fun String?.toStrictBoolean(name: String): Boolean = when {
            isNullOrBlank() -> false
            equals("true", ignoreCase = true) -> true
            equals("false", ignoreCase = true) -> false
            else -> throw IllegalArgumentException("$name must be true or false")
        }
    }
}
