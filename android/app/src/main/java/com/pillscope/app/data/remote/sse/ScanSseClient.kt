package com.pillscope.app.data.remote.sse

import com.pillscope.app.data.remote.dto.CitationsEventDto
import com.pillscope.app.data.remote.dto.ClinicalSummaryEventDto
import com.pillscope.app.data.remote.dto.DeterministicSafetyEventDto
import com.pillscope.app.data.remote.dto.InteractionsEventDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.remote.dto.ScanCompleteEventDto
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ScanSseEvent {
    data class DeterministicSafety(
        val eventId: String?,
        val payload: DeterministicSafetyEventDto,
    ) : ScanSseEvent

    data class Interactions(
        val eventId: String?,
        val payload: InteractionsEventDto,
    ) : ScanSseEvent

    data class Citations(
        val eventId: String?,
        val payload: CitationsEventDto,
    ) : ScanSseEvent

    data class ClinicalSummary(
        val eventId: String?,
        val payload: ClinicalSummaryEventDto,
    ) : ScanSseEvent

    data class Complete(
        val eventId: String?,
        val payload: ScanCompleteEventDto,
    ) : ScanSseEvent

    data class StreamError(
        val message: String,
        val recoverable: Boolean = true,
    ) : ScanSseEvent
}

class ScanSseParser(
    private val json: Json = defaultSseJson,
) {
    fun parseEvent(eventName: String?, eventId: String?, rawData: String): ScanSseEvent? {
        if (rawData.isBlank()) return null
        return try {
            when (eventName?.trim()) {
                "deterministic_safety" -> ScanSseEvent.DeterministicSafety(
                    eventId = eventId,
                    payload = json.decodeFromString<DeterministicSafetyEventDto>(rawData),
                )
                "interactions" -> ScanSseEvent.Interactions(
                    eventId = eventId,
                    payload = json.decodeFromString<InteractionsEventDto>(rawData),
                )
                "citations" -> ScanSseEvent.Citations(
                    eventId = eventId,
                    payload = json.decodeFromString<CitationsEventDto>(rawData),
                )
                "clinical_summary" -> ScanSseEvent.ClinicalSummary(
                    eventId = eventId,
                    payload = json.decodeFromString<ClinicalSummaryEventDto>(rawData),
                )
                "complete" -> ScanSseEvent.Complete(
                    eventId = eventId,
                    payload = json.decodeFromString<ScanCompleteEventDto>(rawData),
                )
                "error" -> ScanSseEvent.StreamError(
                    message = extractSseErrorMessage(rawData),
                    recoverable = true,
                )
                else -> null
            }
        } catch (exc: Exception) {
            ScanSseEvent.StreamError(
                message = "Failed to parse SSE event '${eventName ?: "unknown"}': ${exc.message}",
                recoverable = true,
            )
        }
    }

    private fun extractSseErrorMessage(rawData: String): String {
        val parsed = runCatching {
            val obj = json.parseToJsonElement(rawData) as? kotlinx.serialization.json.JsonObject
            (obj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (obj?.get("detail") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() } ?: rawData
    }

    fun parseWireStream(rawWireText: String): List<ScanSseEvent> {
        val reader = BufferedReader(StringReader(rawWireText))
        val parsed = mutableListOf<ScanSseEvent>()
        var currentId: String? = null
        var currentEvent: String? = null
        val dataLines = mutableListOf<String>()

        fun flushFrame() {
            if (dataLines.isNotEmpty()) {
                parseEvent(
                    eventName = currentEvent,
                    eventId = currentId,
                    rawData = dataLines.joinToString("\n"),
                )?.let(parsed::add)
            }
            currentId = null
            currentEvent = null
            dataLines.clear()
        }

        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> flushFrame()
                line.startsWith(":") -> Unit
                line.startsWith("id:") -> currentId = line.substring(3).trim()
                line.startsWith("event:") -> currentEvent = line.substring(6).trim()
                line.startsWith("data:") -> dataLines.add(line.substring(5).removePrefix(" "))
            }
        }
        flushFrame()
        return parsed
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val defaultSseJson: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

interface ScanSseClient {
    fun streamAnalysis(request: ScanAnalyzeRequestDto): Flow<ScanSseEvent>
}

class OkHttpScanSseClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    private val parser: ScanSseParser = ScanSseParser(),
    private val json: Json = ScanSseParser.defaultSseJson,
) : ScanSseClient {
    override fun streamAnalysis(request: ScanAnalyzeRequestDto): Flow<ScanSseEvent> = callbackFlow {
        val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val bodyJson = json.encodeToString(request)
        val httpRequest = Request.Builder()
            .url("${normalizedBase}api/v1/scans/analyze")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val receivedComplete = AtomicBoolean(false)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                parser.parseEvent(eventName = type, eventId = id, rawData = data)?.let { event ->
                    if (event is ScanSseEvent.Complete) {
                        receivedComplete.set(true)
                        trySend(event)
                        eventSource.cancel()
                        close()
                    } else {
                        trySend(event)
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                if (receivedComplete.get()) {
                    close()
                    return
                }
                val statusInfo = response?.code?.let { "HTTP $it" }
                val errMessage = t?.message ?: statusInfo ?: "SSE connection failed"
                trySend(ScanSseEvent.StreamError(message = errMessage, recoverable = false))
                close()
            }
        }

        val eventSource = EventSources.createFactory(okHttpClient).newEventSource(httpRequest, listener)
        awaitClose {
            eventSource.cancel()
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
