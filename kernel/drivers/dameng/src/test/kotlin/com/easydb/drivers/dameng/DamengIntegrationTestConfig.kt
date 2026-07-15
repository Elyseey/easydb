package com.easydb.drivers.dameng

import java.util.Locale

class DamengIntegrationTestConfig private constructor(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val schema: String,
    val allowDdl: Boolean
) {
    override fun toString(): String =
        "DamengIntegrationTestConfig(jdbcUrl=<redacted>, username=$username, password=<redacted>, schema=$schema, allowDdl=$allowDdl)"

    companion object {
        private const val ENABLED_ENV = "EASYDB_DM_INTEGRATION_TEST"
        private const val JDBC_URL_ENV = "EASYDB_DM_JDBC_URL"
        private const val USERNAME_ENV = "EASYDB_DM_USERNAME"
        private const val PASSWORD_ENV = "EASYDB_DM_PASSWORD"
        private const val SCHEMA_ENV = "EASYDB_DM_TEST_SCHEMA"
        private const val ALLOW_DDL_ENV = "EASYDB_DM_ALLOW_DDL"
        private const val SAFE_SCHEMA_PREFIX = "EASYDB_TEST_"

        private val credentialInUrl = Regex(
            "(?i)(?:[?;&]|^)(?:user|username|password|pwd)\\s*=|://[^/@\\s]+:[^/@\\s]+@"
        )

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): DamengIntegrationTestConfig {
            require(environment[ENABLED_ENV].isTrue()) {
                "$ENABLED_ENV must be true before any Dameng integration test can run"
            }

            val jdbcUrl = environment.required(JDBC_URL_ENV)
            require(jdbcUrl.startsWith("jdbc:dm://", ignoreCase = true)) {
                "$JDBC_URL_ENV must use the jdbc:dm:// scheme"
            }
            require(!jdbcUrl.contains('\n') && !jdbcUrl.contains('\r')) {
                "$JDBC_URL_ENV must be a single line"
            }
            require(!credentialInUrl.containsMatchIn(jdbcUrl)) {
                "$JDBC_URL_ENV must not contain credentials; use the dedicated username/password variables"
            }

            val username = environment.required(USERNAME_ENV)
            require(!username.uppercase(Locale.ROOT).startsWith("SYS")) {
                "$USERNAME_ENV must be a dedicated non-system test account"
            }

            val password = environment.required(PASSWORD_ENV)
            val schema = environment.required(SCHEMA_ENV)
            val allowDdl = environment[ALLOW_DDL_ENV].toStrictBoolean(ALLOW_DDL_ENV)
            if (allowDdl) {
                require(schema.uppercase(Locale.ROOT).startsWith(SAFE_SCHEMA_PREFIX)) {
                    "$SCHEMA_ENV must start with $SAFE_SCHEMA_PREFIX when DDL tests are enabled"
                }
            }

            return DamengIntegrationTestConfig(jdbcUrl, username, password, schema, allowDdl)
        }

        private fun Map<String, String>.required(name: String): String =
            this[name]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("$name is required when Dameng integration tests are enabled")

        private fun String?.isTrue(): Boolean = this.equals("true", ignoreCase = true)

        private fun String?.toStrictBoolean(name: String): Boolean = when {
            isNullOrBlank() -> false
            equals("true", ignoreCase = true) -> true
            equals("false", ignoreCase = true) -> false
            else -> throw IllegalArgumentException("$name must be true or false")
        }
    }
}
