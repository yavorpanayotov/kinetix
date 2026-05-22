"""Prometheus instrumentation for Greek-component P&L attribution.

Records a completed :class:`~kinetix_risk.pnl_attribution.GreekPnlAttribution`
into the ``pnl_attribution_greek_pnl`` (per-Greek), ``pnl_attribution_dollar_delta``
and ``pnl_attribution_dollar_gamma`` gauges so Grafana can show a desk whether a
move was delta-, gamma-, vega- or theta-driven.
"""

from kinetix_risk.metrics import (
    pnl_attribution_dollar_delta,
    pnl_attribution_dollar_gamma,
    pnl_attribution_greek_pnl,
)
from kinetix_risk.pnl_attribution import GreekPnlAttribution


def record_greek_pnl_attribution(result: GreekPnlAttribution, book_id: str) -> None:
    """Set every P&L-decomposition gauge from a completed attribution result.

    Populates one ``pnl_attribution_greek_pnl`` series per Greek component plus
    the ``pnl_attribution_dollar_delta`` / ``pnl_attribution_dollar_gamma``
    cash-sensitivity gauges for the given book.
    """
    pnl_attribution_greek_pnl.labels(book_id=book_id, greek="delta").set(result.delta_pnl)
    pnl_attribution_greek_pnl.labels(book_id=book_id, greek="gamma").set(result.gamma_pnl)
    pnl_attribution_greek_pnl.labels(book_id=book_id, greek="vega").set(result.vega_pnl)
    pnl_attribution_greek_pnl.labels(book_id=book_id, greek="theta").set(result.theta_pnl)
    pnl_attribution_greek_pnl.labels(book_id=book_id, greek="rho").set(result.rho_pnl)

    pnl_attribution_dollar_delta.labels(book_id=book_id).set(result.dollar_delta)
    pnl_attribution_dollar_gamma.labels(book_id=book_id).set(result.dollar_gamma)
