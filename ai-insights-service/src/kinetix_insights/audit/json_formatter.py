"""A Loki-compatible JSON :class:`logging.Formatter`.

The observability stack (ADR-0008: Prometheus + Loki + Tempo) ships
container stdout to Loki, which parses each *physical line* as one
structured record when the line is a JSON object. :class:`JsonLogFormatter`
renders a :class:`logging.LogRecord` as exactly that: a single-line JSON
object with no embedded newlines.

For an audit record the structured payload travels on the log record as
the ``audit`` attribute (set by :class:`~kinetix_insights.audit.
audit_logger.AuditLogger`). When present, the formatter emits the audit
fields at the top level of the JSON object so a Loki query can filter on
``user_id`` / ``endpoint`` directly. For an ordinary log record (no
``audit`` attribute) it falls back to a minimal ``{level, logger,
message, timestamp}`` shape, so the same formatter can front any handler.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone


class JsonLogFormatter(logging.Formatter):
    """Renders a :class:`logging.LogRecord` as one-line JSON.

    When the record carries a structured ``audit`` mapping (an
    :class:`~kinetix_insights.audit.audit_record.AuditRecord` dict) its
    keys are merged into the top-level object. Otherwise a compact
    ``{level, logger, message, timestamp}`` object is produced.
    """

    def format(self, record: logging.LogRecord) -> str:
        """Return ``record`` as a single-line JSON object string."""

        audit = getattr(record, "audit", None)
        if isinstance(audit, dict):
            payload: dict[str, object] = dict(audit)
        else:
            payload = {
                "level": record.levelname,
                "logger": record.name,
                "message": record.getMessage(),
                "timestamp": datetime.fromtimestamp(
                    record.created, tz=timezone.utc
                ).isoformat(),
            }
        # ``separators`` drops spaces for a compact line; the default
        # ``json.dumps`` never emits a newline, so the record is one line.
        return json.dumps(payload, separators=(",", ":"), default=str)
