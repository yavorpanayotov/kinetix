"""The :class:`AuditLogger` — emits exactly one audit line per call.

Every chat / brief / query / push call path builds an
:class:`~kinetix_insights.audit.audit_record.AuditRecord` and hands it
to :meth:`AuditLogger.emit`, which writes it as a single structured JSON
log line through Python's :mod:`logging`.

The logger is a thin façade over ``logging.getLogger(AUDIT_LOGGER_NAME)``
— a dedicated, well-known logger name so a deployment can route audit
lines to their own handler / Loki stream without entangling them with
ordinary service logs. The audit payload travels on the log record's
``audit`` attribute; :class:`~kinetix_insights.audit.json_formatter.
JsonLogFormatter` lifts those fields to the top of the emitted JSON.

The logger holds no per-request state, so a single instance is safe to
share across concurrent requests — every :meth:`emit` is independent.
"""

from __future__ import annotations

import logging

from kinetix_insights.audit.audit_record import AuditRecord

# Well-known logger name for the audit stream. A deployment can attach a
# dedicated handler to this logger to route audit lines independently of
# ordinary service logs.
AUDIT_LOGGER_NAME = "kinetix_insights.audit"


class AuditLogger:
    """Writes one structured audit log line per :meth:`emit` call.

    Construction is cheap and side-effect free — it only resolves the
    named :mod:`logging` logger. Callers typically build one instance
    and reuse it.
    """

    def __init__(self, *, logger: logging.Logger | None = None) -> None:
        """Construct the audit logger.

        Args:
            logger: An injectable :class:`logging.Logger`. Defaults to
                ``logging.getLogger(AUDIT_LOGGER_NAME)``; tests may pass
                their own to capture output.
        """

        self._logger = logger or logging.getLogger(AUDIT_LOGGER_NAME)

    def emit(self, record: AuditRecord) -> None:
        """Emit ``record`` as exactly one structured JSON log line.

        The audit payload is attached to the log record's ``audit``
        attribute so :class:`~kinetix_insights.audit.json_formatter.
        JsonLogFormatter` can render it as the top-level JSON object.
        The human-readable message is a stable marker — handlers using
        the JSON formatter ignore it; a plain-text fallback handler
        still gets a useful line.
        """

        self._logger.info(
            "ai_insights_audit",
            extra={"audit": record.to_dict()},
        )
