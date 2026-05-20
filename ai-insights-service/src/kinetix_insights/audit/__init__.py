"""Structured audit logging for the AI Insights service (checkbox 10.3).

Every chat / brief / query / push call emits exactly one structured
audit log line — a single-line JSON object — through Python's
:mod:`logging`. The JSON formatter is Loki-compatible so a log shipper
parses each record as one object per line.

The package keeps one concern per file:

* :mod:`audit_record` — the :class:`AuditRecord` value object.
* :mod:`json_formatter` — the Loki-compatible :class:`JsonLogFormatter`.
* :mod:`audit_logger` — the :class:`AuditLogger` emission facility.
"""
