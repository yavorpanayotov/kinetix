"""Prometheus instrumentation for stress-test results.

Records the per-scenario, per-book P&L impact of a completed stress test into
the ``stress_test_loss`` gauge so Grafana can chart per-scenario losses and
flag breaches.
"""

from kinetix_risk.metrics import stress_test_loss
from kinetix_risk.models import StressTestResult


def record_stress_test_loss(result: StressTestResult, book_id: str) -> None:
    """Set the ``stress_test_loss`` gauge from a completed stress-test result.

    The gauge records ``pnl_impact`` directly — a negative value is a loss,
    so the worst scenario for a book is the minimum of this gauge.
    """
    stress_test_loss.labels(
        scenario_name=result.scenario_name,
        book_id=book_id,
    ).set(result.pnl_impact)
