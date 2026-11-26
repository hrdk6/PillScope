package com.pillscope.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException
import java.io.IOException

sealed interface ApiCallError {
    val userMessage: String

    data class Unauthorized(
        override val userMessage: String = "Session expired or not signed in. Please sign in to sync.",
    ) : ApiCallError

    data class Validation(
        override val userMessage: String,
    ) : ApiCallError

    data class Conflict(
        override val userMessage: String,
    ) : ApiCallError

    data class RateLimited(
        override val userMessage: String = "Too many requests. Please wait a moment and try again.",
    ) : ApiCallError

    data class Server(
        val statusCode: Int,
        override val userMessage: String = "Server error (HTTP $statusCode). Please try again.",
    ) : ApiCallError

    data class Offline(
        override val userMessage: String = "Offline — showing cached data and queuing changes.",
    ) : ApiCallError
}

fun Throwable.toApiCallError(json: Json): ApiCallError {
    return when (this) {
        is HttpException -> {
            val code = code()
            val detail = extractFastApiDetail(response()?.errorBody()?.string(), json)
            when (code) {
                401, 403 -> ApiCallError.Unauthorized(
                    detail ?: "Authentication required. Please sign in again.",
                )
                409 -> ApiCallError.Conflict(
                    detail ?: "This record already exists or conflicted with another update.",
                )
                400, 422 -> ApiCallError.Validation(
                    detail ?: "Please check the entered details and try again.",
                )
                429 -> ApiCallError.RateLimited(
                    detail ?: "Too many requests. Please wait a moment and retry.",
                )
                else -> ApiCallError.Server(
                    statusCode = code,
                    userMessage = detail ?: "Server unavailable (HTTP $code). Please try again.",
                )
            }
        }
        is IOException -> ApiCallError.Offline()
        else -> ApiCallError.Server(
            statusCode = 0,
            userMessage = message?.takeIf { it.isNotBlank() } ?: "Unexpected network error.",
        )
    }
}

private fun extractFastApiDetail(rawBody: String?, json: Json): String? {
    if (rawBody.isNullOrBlank()) return null
    return runCatching {
        val root = json.parseToJsonElement(rawBody) as? JsonObject ?: return null
        when (val detail = root["detail"]) {
            is JsonPrimitive -> detail.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            is JsonArray -> detail.mapNotNull { entry ->
                (entry as? JsonObject)?.get("msg")?.let { msgEl ->
                    (msgEl as? JsonPrimitive)?.contentOrNull?.trim()
                }
            }.joinToString("; ").takeIf { it.isNotEmpty() }
            else -> null
        }
    }.getOrNull()
}
