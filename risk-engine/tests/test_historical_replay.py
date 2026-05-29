import numpy as np
import pytest

from kinetix_risk.models import AssetClass, PositionRisk
from kinetix_risk.historical_replay import (
    HistoricalReplayRequest,
    HistoricalReplayRunResult,
    PositionReplayImpact,
    run_historical_replay,
    ASSET_CLASS_PROXY_RETURNS,
)

# Alias for test readability — run_historical_replay returns HistoricalReplayRunResult
HistoricalReplayResult = HistoricalReplayRunResult


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _five_day_returns(values: list[float]) -> np.ndarray:
    """Convert a list of 5 daily return values to an ndarray."""
    return np.array(values, dtype=float)


def _make_positions(*entries) -> list[PositionRisk]:
    return [PositionRisk(instrument_id=iid, asset_class=ac, market_value=mv, currency="USD")
            for iid, ac, mv in entries]


def _equity_returns_5d() -> np.ndarray:
    # Synthetic 5-day equity return scenario: mild rally then correction
    return _five_day_returns([0.01, 0.02, -0.03, 0.01, -0.02])


def _fi_returns_5d() -> np.ndarray:
    return _five_day_returns([-0.005, 0.003, 0.004, -0.002, 0.001])


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

@pytest.mark.unit
class TestHistoricalReplayRequest:
    def test_request_stores_positions_and_returns(self):
        positions = _make_positions(("AAPL", AssetClass.EQUITY, 1_000_000.0))
        returns = {"AAPL": _equity_returns_5d()}
        request = HistoricalReplayRequest(
            scenario_name="TEST_5D",
            positions=positions,
            instrument_returns=returns,
        )
        assert request.scenario_name == "TEST_5D"
        assert len(request.positions) == 1
        assert "AAPL" in request.instrument_returns


@pytest.mark.unit
class TestRunHistoricalReplay:
    def test_applies_actual_returns_to_current_positions(self):
        """Historical replay applies actual daily returns to current position market values."""
        positions = _make_positions(("AAPL", AssetClass.EQUITY, 1_000_000.0))
        # 5 days: returns sum to -0.01 cumulatively (compound)
        returns = {"AAPL": _five_day_returns([0.01, -0.01, 0.005, -0.015, 0.005])}
        request = HistoricalReplayRequest(
            scenario_name="FIVE_DAY",
            positions=positions,
            instrument_returns=returns,
        )
        result = run_historical_replay(request)

        assert isinstance(result, HistoricalReplayResult)
        assert result.scenario_name == "FIVE_DAY"
        assert len(result.position_impacts) == 1
        assert not result.position_impacts[0].proxy_used

    def test_total_pnl_is_sum_of_position_pnls(self):
        """Total P&L equals the sum of individual position P&Ls."""
        positions = _make_positions(
            ("AAPL", AssetClass.EQUITY, 500_000.0),
            ("UST10Y", AssetClass.FIXED_INCOME, 300_000.0),
        )
        returns = {
            "AAPL": _equity_returns_5d(),
            "UST10Y": _fi_returns_5d(),
        }
        request = HistoricalReplayRequest(
            scenario_name="MULTI",
            positions=positions,
            instrument_returns=returns,
        )
        result = run_historical_replay(request)
        expected_total = sum(p.pnl_impact for p in result.position_impacts)
        assert result.total_pnl_impact == pytest.approx(expected_total, abs=1e-6)

    def test_instrument_without_returns_uses_proxy(self):
        """Instruments without historical data fall back to asset class proxy returns, flagged as proxy_used."""
        positions = _make_positions(("UNKNOWN_FI", AssetClass.FIXED_INCOME, 200_000.0))
        # Provide no returns for UNKNOWN_FI
        request = HistoricalReplayRequest(
            scenario_name="PROXY_TEST",
            positions=positions,
            instrument_returns={},
        )
        result = run_historical_replay(request)
        assert len(result.position_impacts) == 1
        impact = result.position_impacts[0]
        assert impact.proxy_used is True
        assert impact.instrument_id == "UNKNOWN_FI"

    def test_proxy_returns_exist_for_all_asset_classes(self):
        """Every AssetClass has a corresponding proxy return series."""
        for ac in AssetClass:
            assert ac in ASSET_CLASS_PROXY_RETURNS, f"Missing proxy for {ac}"
            series = ASSET_CLASS_PROXY_RETURNS[ac]
            assert len(series) > 0

    def test_pnl_impact_direction_matches_returns(self):
        """A uniformly negative return day produces a negative P&L impact."""
        positions = _make_positions(("AAPL", AssetClass.EQUITY, 1_000_000.0))
        # All 5 days are negative returns
        negative_returns = _five_day_returns([-0.01, -0.02, -0.01, -0.015, -0.005])
        request = HistoricalReplayRequest(
            scenario_name="ALL_DOWN",
            positions=positions,
            instrument_returns={"AAPL": negative_returns},
        )
        result = run_historical_replay(request)
        assert result.total_pnl_impact < 0.0

    def test_pnl_impact_is_sum_of_daily_impacts(self):
        """Cumulative P&L is computed as sum of (return * market_value) across all days."""
        mv = 1_000_000.0
        daily = [0.01, 0.02, -0.03, 0.01, -0.02]
        positions = _make_positions(("AAPL", AssetClass.EQUITY, mv))
        request = HistoricalReplayRequest(
            scenario_name="MATH_CHECK",
            positions=positions,
            instrument_returns={"AAPL": np.array(daily)},
        )
        result = run_historical_replay(request)
        expected = sum(r * mv for r in daily)
        assert result.total_pnl_impact == pytest.approx(expected, rel=1e-6)

    def test_empty_positions_raises(self):
        """Running a replay with no positions raises ValueError."""
        request = HistoricalReplayRequest(
            scenario_name="EMPTY",
            positions=[],
            instrument_returns={},
        )
        with pytest.raises(ValueError, match="empty"):
            run_historical_replay(request)

    def test_result_contains_scenario_date_range(self):
        """Result carries the window boundaries when provided on the request."""
        positions = _make_positions(("AAPL", AssetClass.EQUITY, 100_000.0))
        request = HistoricalReplayRequest(
            scenario_name="GFC_WINDOW",
            positions=positions,
            instrument_returns={"AAPL": _equity_returns_5d()},
            window_start="2008-09-15",
            window_end="2008-09-19",
        )
        result = run_historical_replay(request)
        assert result.window_start == "2008-09-15"
        assert result.window_end == "2008-09-19"

    def test_day_by_day_breakdown_length_matches_return_series(self):
        """Each position impact includes a per-day breakdown with one entry per return day."""
        n_days = 5
        positions = _make_positions(("AAPL", AssetClass.EQUITY, 500_000.0))
        request = HistoricalReplayRequest(
            scenario_name="BREAKDOWN",
            positions=positions,
            instrument_returns={"AAPL": np.zeros(n_days)},
        )
        result = run_historical_replay(request)
        assert len(result.position_impacts[0].daily_pnl) == n_days
