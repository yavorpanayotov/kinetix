"""Structured JSON log formatting for the risk-engine.

A stdlib-only ``logging.Formatter`` that emits each log record as a single
line of JSON. This makes fields such as ``book_id``, ``correlation_id`` and
``calculation_type`` queryable in Loki instead of being buried in a
printf-formatted message string.

No third-party dependency is used — ``structlog`` and ``python-json-logger``
are deliberately avoided to stay within the project guardrails.
"""

import json
import logging
from datetime import datetime, timezone

# Attributes present on every ``logging.LogRecord`` that describe the record
# itself rather than caller-supplied context. Anything *not* in this set is
# treated as an ``extra`` field and promoted to a top-level JSON key.
_STANDARD_LOGRECORD_ATTRS: frozenset[str] = frozenset(
    {
        "args",
        "asctime",
        "created",
        "exc_info",
        "exc_text",
        "filename",
        "funcName",
        "levelname",
        "levelno",
        "lineno",
        "module",
        "msecs",
        "message",
        "msg",
        "name",
        "pathname",
        "process",
        "processName",
        "relativeCreated",
        "stack_info",
        "taskName",
        "thread",
        "threadName",
    }
)


class JsonLogFormatter(logging.Formatter):
    """Format a :class:`logging.LogRecord` as a single JSON line.

    The emitted object always carries ``timestamp``, ``level``, ``logger`` and
    ``message``. Any non-standard attributes set on the record — typically via
    ``logger.info(..., extra={...})`` — are included as top-level keys. When the
    record carries exception information an ``exception`` key holds the
    formatted traceback.
    """

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "timestamp": datetime.fromtimestamp(
                record.created, tz=timezone.utc
            ).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }

        for key, value in record.__dict__.items():
            if key not in _STANDARD_LOGRECORD_ATTRS and not key.startswith("_"):
                payload[key] = value

        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)

        if record.stack_info:
            payload["stack_info"] = self.formatStack(record.stack_info)

        # ``default=str`` ensures non-serialisable extras degrade gracefully
        # rather than raising and dropping the log line entirely.
        return json.dumps(payload, default=str)
