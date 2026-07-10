package com.easydb.launcher

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.authorization
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val TOKEN_ENV = "EASYDB_KERNEL_TOKEN"
private const val SSE_TICKET_TTL_MS = 30_000L

object KernelSecurity {
    private val random = SecureRandom()
    private val sseTickets = ConcurrentHashMap<String, Long>()

    val token: String = System.getenv(TOKEN_ENV)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: error("$TOKEN_ENV is required")

    fun issueSseTicket(): String {
        purgeExpiredTickets()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sseTickets[ticket] = System.currentTimeMillis() + SSE_TICKET_TTL_MS
        return ticket
    }

    fun consumeSseTicket(ticket: String?): Boolean {
        if (ticket.isNullOrBlank()) return false
        purgeExpiredTickets()
        val expiresAt = sseTickets.remove(ticket) ?: return false
        return expiresAt >= System.currentTimeMillis()
    }

    fun tokenMatches(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = token.toByteArray(Charsets.UTF_8)
        val actual = candidate.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun purgeExpiredTickets() {
        val now = System.currentTimeMillis()
        sseTickets.entries.removeIf { it.value < now }
    }
}

fun Application.configureKernelCors() {
    install(CORS) {
        allowHost("localhost", schemes = listOf("tauri"))
        allowHost("tauri.localhost", schemes = listOf("http", "https"))
        allowHost("localhost:1420", schemes = listOf("http"))
        allowHost("127.0.0.1:1420", schemes = listOf("http"))
        allowHost("localhost:5173", schemes = listOf("http"))
        allowHost("127.0.0.1:5173", schemes = listOf("http"))
        allowHost("localhost:5174", schemes = listOf("http"))
        allowHost("127.0.0.1:5174", schemes = listOf("http"))
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        exposeHeader(HttpHeaders.ContentDisposition)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }
}

fun Application.configureKernelSecurity() {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (!path.startsWith("/api") || call.request.httpMethod == HttpMethod.Options) {
            return@intercept
        }

        if (path == "/api/tracker/events") {
            val ticket = call.request.queryParameters["ticket"]
            if (KernelSecurity.consumeSseTicket(ticket)) {
                return@intercept
            }
        }

        val bearer = call.request.authorization()
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()

        if (!KernelSecurity.tokenMatches(bearer)) {
            call.respondText(
                """{"success":false,"error":{"code":"UNAUTHORIZED","message":"Unauthorized"}}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized
            )
            finish()
        }
    }
}
