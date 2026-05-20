import json
import logging

import pytest

from kinetix_risk.log_formatter import JsonLogFormatter

pytestmark = pytest.mark.unit


def make_record(
    name: str = "kinetix_risk.test",
    level: int = logging.INFO,
    msg: str = "hello",
    args: tuple = (),
    extra: dict | None = None,
) -> logging.LogRecord:
    record = logging.LogRecord(
        name=name,
        level=level,
        pathname=__file__,
        lineno=42,
        msg=msg,
        args=args,
        exc_info=None,
    )
    for key, value in (extra or {}).items():
        setattr(record, key, value)
    return record


class TestJsonLogFormatter:
    def test_formats_record_as_parseable_json(self):
        formatter = JsonLogFormatter()
        output = formatter.format(make_record())

        parsed = json.loads(output)  # raises if not valid JSON
        assert isinstance(parsed, dict)

    def test_includes_expected_top_level_keys(self):
        formatter = JsonLogFormatter()
        parsed = json.loads(formatter.format(make_record()))

        assert "timestamp" in parsed
        assert "level" in parsed
        assert "logger" in parsed
        assert "message" in parsed

    def test_level_reflects_record_level_name(self):
        formatter = JsonLogFormatter()
        parsed = json.loads(formatter.format(make_record(level=logging.WARNING)))

        assert parsed["level"] == "WARNING"

    def test_logger_reflects_record_name(self):
        formatter = JsonLogFormatter()
        parsed = json.loads(formatter.format(make_record(name="kinetix_risk.server")))

        assert parsed["logger"] == "kinetix_risk.server"

    def test_message_reflects_formatted_message(self):
        formatter = JsonLogFormatter()
        record = make_record(msg="var for %s is %d", args=("BANK-1", 42))
        parsed = json.loads(formatter.format(record))

        assert parsed["message"] == "var for BANK-1 is 42"

    def test_timestamp_is_iso_8601(self):
        formatter = JsonLogFormatter()
        parsed = json.loads(formatter.format(make_record()))

        from datetime import datetime

        # Parses without error -> valid ISO-8601 timestamp.
        datetime.fromisoformat(parsed["timestamp"])

    def test_extra_fields_appear_as_top_level_keys(self):
        formatter = JsonLogFormatter()
        record = make_record(
            extra={"book_id": "BANK-1", "correlation_id": "abc-123", "calculation_type": "VAR"},
        )
        parsed = json.loads(formatter.format(record))

        assert parsed["book_id"] == "BANK-1"
        assert parsed["correlation_id"] == "abc-123"
        assert parsed["calculation_type"] == "VAR"

    def test_standard_record_attributes_are_not_leaked(self):
        formatter = JsonLogFormatter()
        parsed = json.loads(formatter.format(make_record()))

        # Internal LogRecord attributes must not pollute the JSON output.
        for noisy_key in ("msg", "args", "levelno", "pathname", "stack_info", "processName"):
            assert noisy_key not in parsed

    def test_exception_info_is_included(self):
        formatter = JsonLogFormatter()
        try:
            raise ValueError("boom")
        except ValueError:
            import sys

            exc_info = sys.exc_info()
        record = logging.LogRecord(
            name="kinetix_risk.test",
            level=logging.ERROR,
            pathname=__file__,
            lineno=1,
            msg="calculation failed",
            args=(),
            exc_info=exc_info,
        )
        parsed = json.loads(formatter.format(record))

        assert "exception" in parsed
        assert "ValueError" in parsed["exception"]
        assert "boom" in parsed["exception"]

    def test_non_serialisable_extra_degrades_gracefully(self):
        formatter = JsonLogFormatter()

        class Unserialisable:
            def __str__(self) -> str:
                return "custom-repr"

        record = make_record(extra={"weird": Unserialisable()})
        parsed = json.loads(formatter.format(record))

        assert parsed["weird"] == "custom-repr"
