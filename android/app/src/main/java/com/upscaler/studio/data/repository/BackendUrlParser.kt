package com.upscaler.studio.data.repository

import java.net.Inet4Address
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ParsedBackendConfig(
    val baseUrl: String,
    val authToken: String? = null
)

object BackendUrlParser {

    /**
     * Parses either a direct HTTP(S) URL or an `upscaler://connect?url=...&token=...` deep-link / QR payload.
     * Validates scheme and host, normalizes trailing slashes, and rejects insecure cleartext for public hosts.
     */
    fun parsePairingInput(rawInput: String): Result<ParsedBackendConfig> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Backend URL cannot be empty."))
        }

        return try {
            if (trimmed.startsWith("upscaler://", ignoreCase = true) || trimmed.startsWith("ryn://", ignoreCase = true)) {
                val candidateUri = parseUriStrict(trimmed)
                if (!candidateUri.host.equals("connect", ignoreCase = true)) {
                    return Result.failure(IllegalArgumentException("Unsupported pairing link host."))
                }
                val params = parseQueryParams(candidateUri.rawQuery ?: "")
                val embeddedUrl = params["url"]?.trim()
                    ?: return Result.failure(IllegalArgumentException("Pairing link is missing the 'url' parameter."))
                val token = params["token"]?.trim()?.takeIf { it.isNotEmpty() }
                val normalized = normalizeBackendUrl(embeddedUrl).getOrElse { return Result.failure(it) }
                Result.success(ParsedBackendConfig(baseUrl = normalized, authToken = token))
            } else {
                val normalized = normalizeBackendUrl(trimmed).getOrElse { return Result.failure(it) }
                val queryPart = trimmed.substringAfter("?", "")
                val params = parseQueryParams(queryPart)
                val token = params["token"]?.trim()?.takeIf { it.isNotEmpty() }
                Result.success(ParsedBackendConfig(baseUrl = normalized, authToken = token))
            }
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Malformed backend URL: ${e.message ?: "invalid syntax"}"))
        }
    }

    /**
     * Validates and normalizes an HTTP/HTTPS backend URL.
     * Preserves HTTP for intentional local development targets while requiring/upgrading HTTPS for public hosts.
     */
    fun normalizeBackendUrl(rawUrl: String): Result<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Backend URL cannot be empty."))
        }
        if (trimmed.any { it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("Backend URL must not contain spaces."))
        }

        val withScheme = if ("://" in trimmed) {
            trimmed
        } else {
            val hostPart = trimmed.substringBefore("/").substringBefore(":")
            val defaultScheme = if (isLocalHost(hostPart)) "http://" else "https://"
            "$defaultScheme$trimmed"
        }

        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Malformed URL syntax."))
        }

        val rawScheme = uri.scheme?.lowercase() ?: ""
        if (rawScheme != "http" && rawScheme != "https") {
            return Result.failure(IllegalArgumentException("Only HTTP and HTTPS backend URLs are supported."))
        }

        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Backend URL must include a valid hostname or IP."))
        }

        val effectiveScheme = if (rawScheme == "http" && !isLocalHost(host)) {
            // Upgrade known public tunnel domains automatically; reject unexpected cleartext public hosts
            if (host.endsWith(".trycloudflare.com") || host.endsWith(".ngrok-free.app") || host.endsWith(".ngrok.io")) {
                "https"
            } else {
                return Result.failure(IllegalArgumentException("Public backend connections must use HTTPS."))
            }
        } else {
            rawScheme
        }

        val port = uri.port
        val defaultPort = if (effectiveScheme == "https") 443 else 80
        val portSuffix = if (port != -1 && port != defaultPort) ":$port" else ""
        val ipv6FormattedHost = if (":" in host && !host.startsWith("[")) "[$host]" else host

        return Result.success("$effectiveScheme://$ipv6FormattedHost$portSuffix")
    }

    fun isLocalHost(host: String): Boolean {
        val clean = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (clean == "localhost" || clean == "127.0.0.1" || clean == "::1" || clean == "10.0.2.2" || clean.endsWith(".local")) {
            return true
        }
        // RFC1918 IPv4 check
        val parts = clean.split(".")
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 10) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 127) return true
        }
        return false
    }

    private fun parseUriStrict(raw: String): URI {
        return URI(raw)
    }

    fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
            val key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name())
            result[key] = value
        }
        return result
    }
}

