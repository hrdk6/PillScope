from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any, cast

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import ReadableSpan, TracerProvider
from opentelemetry.sdk.trace.export import (
    BatchSpanProcessor,
    SimpleSpanProcessor,
    SpanExporter,
    SpanExportResult,
)

_configured = False


class InMemorySpanCollector(SpanExporter):
    """Thread-safe in-memory OTel span collector for tests and local eval telemetry inspection."""

    def __init__(self) -> None:
        self._spans: list[ReadableSpan] = []

    def export(self, spans: Any) -> SpanExportResult:
        self._spans.extend(spans)
        return SpanExportResult.SUCCESS

    def shutdown(self) -> None:
        return None

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        return True

    @property
    def spans(self) -> list[ReadableSpan]:
        return list(self._spans)

    def clear(self) -> None:
        self._spans.clear()


def _build_otlp_exporter(
    endpoint: str,
    headers: dict[str, str] | None = None,
) -> SpanExporter | None:
    try:
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import (
            OTLPSpanExporter,
        )

        return cast(SpanExporter, OTLPSpanExporter(endpoint=endpoint, headers=headers))
    except ImportError:
        return None


def configure_telemetry(
    *,
    service_name: str = "pillscope-backend",
    otlp_endpoint: str = "",
    langfuse_public_key: str = "",
    langfuse_secret_key: str = "",
    langfuse_host: str = "https://cloud.langfuse.com",
    extra_exporter: SpanExporter | None = None,
) -> TracerProvider:
    global _configured
    current_provider = trace.get_tracer_provider()
    if isinstance(current_provider, TracerProvider):
        if extra_exporter is not None:
            current_provider.add_span_processor(SimpleSpanProcessor(extra_exporter))
        _configured = True
        return current_provider

    resource = Resource.create({"service.name": service_name})
    provider = TracerProvider(resource=resource)

    if otlp_endpoint:
        otlp = _build_otlp_exporter(otlp_endpoint)
        if otlp is not None:
            provider.add_span_processor(BatchSpanProcessor(otlp))

    if langfuse_public_key and langfuse_secret_key:
        import base64

        token = base64.b64encode(f"{langfuse_public_key}:{langfuse_secret_key}".encode()).decode()
        lf_endpoint = f"{langfuse_host.rstrip('/')}/api/public/otel/v1/traces"
        lf_exporter = _build_otlp_exporter(
            lf_endpoint,
            headers={"Authorization": f"Basic {token}"},
        )
        if lf_exporter is not None:
            provider.add_span_processor(BatchSpanProcessor(lf_exporter))

    if extra_exporter is not None:
        provider.add_span_processor(SimpleSpanProcessor(extra_exporter))

    trace.set_tracer_provider(provider)
    _configured = True
    return provider


def get_tracer(instrumentation_name: str = "pillscope") -> trace.Tracer:
    if not _configured:
        configure_telemetry()
    return trace.get_tracer(instrumentation_name)


def current_trace_id_hex() -> str | None:
    span = trace.get_current_span()
    ctx = span.get_span_context()
    if ctx is None or not ctx.is_valid:
        return None
    return format(ctx.trace_id, "032x")


@contextmanager
def traced_span(
    name: str,
    attributes: dict[str, Any] | None = None,
    instrumentation_name: str = "pillscope",
) -> Iterator[trace.Span]:
    tracer = get_tracer(instrumentation_name)
    with tracer.start_as_current_span(name) as span:
        if attributes:
            for key, value in attributes.items():
                if value is not None:
                    span.set_attribute(key, value)
        yield span
