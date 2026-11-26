import time

from opentelemetry.trace import Status, StatusCode
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from eval.tracing.otel import current_trace_id_hex, get_tracer


class OpenTelemetryMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self._app = app
        self._tracer = get_tracer("pillscope.http")

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return

        method = str(scope.get("method", "GET"))
        path = str(scope.get("path", "/"))
        started = time.perf_counter()
        status_code = 500

        with self._tracer.start_as_current_span(f"HTTP {method} {path}") as span:
            span.set_attribute("http.request.method", method)
            span.set_attribute("url.path", path)
            trace_id = current_trace_id_hex()

            async def _send_wrapper(message: Message) -> None:
                nonlocal status_code
                if message["type"] == "http.response.start":
                    status_code = int(message["status"])
                    if trace_id:
                        raw_headers = list(message.get("headers") or [])
                        raw_headers.append((b"x-trace-id", trace_id.encode("ascii")))
                        message["headers"] = raw_headers
                await send(message)

            try:
                await self._app(scope, receive, _send_wrapper)
            except Exception as exc:
                span.record_exception(exc)
                span.set_status(Status(StatusCode.ERROR, str(exc)))
                raise
            finally:
                duration_ms = round((time.perf_counter() - started) * 1000.0, 2)
                span.set_attribute("http.response.status_code", status_code)
                span.set_attribute("pillscope.http.duration_ms", duration_ms)
                if status_code >= 500:
                    span.set_status(Status(StatusCode.ERROR, f"HTTP {status_code}"))
