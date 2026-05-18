import math

import numpy as np
import pytest

from kinetix_risk.market_data_consumer import MarketDataBundle, consume_market_data
from kinetix_risk.models import AssetClass


pytestmark = pytest.mark.unit


class TestConsumeMarketDataEmpty:
    def test_empty_market_data_returns_none_values(self):
        bundle = consume_market_data([])
        assert bundle.volatility_provider is None
        assert bundle.correlation_matrix is None
        assert bundle.spot_prices == {}


class TestConsumeMarketDataVolatility:
    def test_historical_prices_produce_annualized_volatility(self):
        # Simulate daily prices for an equity instrument: 100, 101, 99, 102, 100.5
        prices = [100.0, 101.0, 99.0, 102.0, 100.5]
        market_data = [
            {
                "data_type": "HISTORICAL_PRICES",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "time_series": [
                    {"timestamp_seconds": 1000000 + i * 86400, "value": p}
                    for i, p in enumerate(prices)
                ],
            }
        ]

        bundle = consume_market_data(market_data)
        assert bundle.volatility_provider is not None

        vol = bundle.volatility_provider(AssetClass.EQUITY)
        # Manually compute: log returns, std, annualise
        log_returns = [math.log(prices[i] / prices[i - 1]) for i in range(1, len(prices))]
        expected_vol = float(np.std(log_returns, ddof=1)) * math.sqrt(252)
        assert abs(vol - expected_vol) < 1e-10

    def test_multiple_instruments_same_asset_class_averages_volatility(self):
        prices_a = [100.0, 102.0, 104.0, 103.0, 105.0]
        prices_b = [50.0, 51.0, 49.0, 50.5, 52.0]
        market_data = [
            {
                "data_type": "HISTORICAL_PRICES",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "time_series": [
                    {"timestamp_seconds": 1000000 + i * 86400, "value": p}
                    for i, p in enumerate(prices_a)
                ],
            },
            {
                "data_type": "HISTORICAL_PRICES",
                "instrument_id": "GOOGL",
                "asset_class": "EQUITY",
                "time_series": [
                    {"timestamp_seconds": 1000000 + i * 86400, "value": p}
                    for i, p in enumerate(prices_b)
                ],
            },
        ]

        bundle = consume_market_data(market_data)
        vol = bundle.volatility_provider(AssetClass.EQUITY)

        def _annualized_vol(prices):
            lr = [math.log(prices[i] / prices[i - 1]) for i in range(1, len(prices))]
            return float(np.std(lr, ddof=1)) * math.sqrt(252)

        expected = (_annualized_vol(prices_a) + _annualized_vol(prices_b)) / 2
        assert abs(vol - expected) < 1e-10

    def test_spot_price_alone_produces_no_volatility(self):
        market_data = [
            {
                "data_type": "SPOT_PRICE",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "scalar": 170.50,
            }
        ]

        bundle = consume_market_data(market_data)
        assert bundle.volatility_provider is None
        assert bundle.spot_prices == {"AAPL": 170.50}

    def test_insufficient_prices_skipped(self):
        market_data = [
            {
                "data_type": "HISTORICAL_PRICES",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "time_series": [{"timestamp_seconds": 1000000, "value": 100.0}],
            }
        ]

        bundle = consume_market_data(market_data)
        assert bundle.volatility_provider is None


class TestConsumeMarketDataCorrelation:
    def test_provided_correlation_matrix(self):
        market_data = [
            {
                "data_type": "CORRELATION_MATRIX",
                "instrument_id": "",
                "asset_class": "",
                "matrix": {
                    "rows": 2,
                    "cols": 2,
                    "values": [1.0, 0.5, 0.5, 1.0],
                    "labels": ["EQUITY", "FX"],
                },
            }
        ]

        bundle = consume_market_data(market_data)
        assert bundle.correlation_matrix is not None
        expected = np.array([[1.0, 0.5], [0.5, 1.0]])
        np.testing.assert_array_almost_equal(bundle.correlation_matrix, expected)


class TestConsumeMarketDataPartial:
    def test_partial_data_falls_back_for_missing_asset_classes(self):
        prices = [100.0, 101.0, 99.0, 102.0, 100.5]
        market_data = [
            {
                "data_type": "HISTORICAL_PRICES",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "time_series": [
                    {"timestamp_seconds": 1000000 + i * 86400, "value": p}
                    for i, p in enumerate(prices)
                ],
            },
        ]

        bundle = consume_market_data(market_data)
        assert bundle.volatility_provider is not None

        # Equity should have a computed vol
        equity_vol = bundle.volatility_provider(AssetClass.EQUITY)
        assert equity_vol > 0

        # FX should fall back to default (0.10)
        fx_vol = bundle.volatility_provider(AssetClass.FX)
        assert fx_vol == 0.10


class TestConsumeMarketDataVolSurface:
    def test_point_list_format_produces_vol_surface(self):
        market_data = [
            {
                "data_type": "VOLATILITY_SURFACE",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "points": [
                    {"strike": 100.0, "maturity_days": 30, "implied_vol": 0.20},
                    {"strike": 110.0, "maturity_days": 30, "implied_vol": 0.22},
                ],
            }
        ]
        bundle = consume_market_data(market_data)
        assert "AAPL" in bundle.vol_surfaces
        assert len(bundle.vol_surfaces["AAPL"].points) == 2

    def test_matrix_format_produces_vol_surface(self):
        """Vol surface sent as proto Matrix (rows=maturities, cols=strikes, labels=maturities+strikes)."""
        market_data = [
            {
                "data_type": "VOLATILITY_SURFACE",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "matrix": {
                    "rows": 2,
                    "cols": 3,
                    "values": [0.20, 0.22, 0.25, 0.18, 0.20, 0.23],
                    "labels": ["30", "60", "100.0", "110.0", "120.0"],
                },
            }
        ]
        bundle = consume_market_data(market_data)
        assert "AAPL" in bundle.vol_surfaces
        surface = bundle.vol_surfaces["AAPL"]
        assert len(surface.points) == 6
        # Check first point: maturity=30, strike=100.0, vol=0.20
        p0 = surface.points[0]
        assert p0.maturity_days == 30
        assert p0.strike == 100.0
        assert p0.implied_vol == 0.20
        # Check last point: maturity=60, strike=120.0, vol=0.23
        p5 = surface.points[5]
        assert p5.maturity_days == 60
        assert p5.strike == 120.0
        assert p5.implied_vol == 0.23

    def test_matrix_format_with_bilinear_interpolation(self):
        """Verify the reconstructed surface supports vol_at() interpolation."""
        market_data = [
            {
                "data_type": "VOLATILITY_SURFACE",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "matrix": {
                    "rows": 2,
                    "cols": 2,
                    "values": [0.20, 0.25, 0.18, 0.22],
                    "labels": ["30", "90", "100.0", "150.0"],
                },
            }
        ]
        bundle = consume_market_data(market_data)
        surface = bundle.vol_surfaces["AAPL"]
        # Exact corner
        assert surface.vol_at(100.0, 30) == pytest.approx(0.20)
        # Interpolated
        mid_vol = surface.vol_at(125.0, 60)
        assert 0.18 < mid_vol < 0.25

    def test_matrix_format_with_insufficient_labels_ignored(self):
        market_data = [
            {
                "data_type": "VOLATILITY_SURFACE",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "matrix": {
                    "rows": 2,
                    "cols": 2,
                    "values": [0.20, 0.25, 0.18, 0.22],
                    "labels": ["30"],  # Not enough labels
                },
            }
        ]
        bundle = consume_market_data(market_data)
        assert "AAPL" not in bundle.vol_surfaces

    def test_point_format_takes_precedence_over_matrix(self):
        market_data = [
            {
                "data_type": "VOLATILITY_SURFACE",
                "instrument_id": "AAPL",
                "asset_class": "EQUITY",
                "points": [
                    {"strike": 100.0, "maturity_days": 30, "implied_vol": 0.20},
                ],
                "matrix": {
                    "rows": 1,
                    "cols": 1,
                    "values": [0.99],
                    "labels": ["30", "100.0"],
                },
            }
        ]
        bundle = consume_market_data(market_data)
        assert bundle.vol_surfaces["AAPL"].points[0].implied_vol == 0.20


class TestMarketDataConsumerZeroPrices:
    def test_zero_price_in_history_skipped_gracefully(self):
        prices = [100.0, 0.0, 102.0, 103.0, 104.0]
        market_data = [{
            "data_type": "HISTORICAL_PRICES",
            "instrument_id": "AAPL",
            "asset_class": "EQUITY",
            "time_series": [
                {"timestamp_seconds": 1000000 + i * 86400, "value": p}
                for i, p in enumerate(prices)
            ],
        }]
        bundle = consume_market_data(market_data)
        assert bundle.volatility_provider is not None

    def test_negative_price_in_history_skipped_gracefully(self):
        prices = [100.0, -1.0, 102.0, 103.0, 104.0]
        market_data = [{
            "data_type": "HISTORICAL_PRICES",
            "instrument_id": "AAPL",
            "asset_class": "EQUITY",
            "time_series": [
                {"timestamp_seconds": 1000000 + i * 86400, "value": p}
                for i, p in enumerate(prices)
            ],
        }]
        bundle = consume_market_data(market_data)
        assert bundle.volatility_provider is not None
