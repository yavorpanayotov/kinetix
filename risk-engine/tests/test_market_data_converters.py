from google.protobuf.timestamp_pb2 import Timestamp

from kinetix.risk import risk_calculation_pb2
from kinetix_risk.converters import proto_market_data_to_domain

import pytest

pytestmark = pytest.mark.unit


class TestProtoMarketDataToDomain:
    def test_time_series_conversion(self):
        proto_md = risk_calculation_pb2.MarketDataValue(
            data_type=risk_calculation_pb2.HISTORICAL_PRICES,
            instrument_id="AAPL",
            asset_class="EQUITY",
            time_series=risk_calculation_pb2.TimeSeries(
                points=[
                    risk_calculation_pb2.TimeSeriesPoint(
                        timestamp=Timestamp(seconds=1000000),
                        value=100.0,
                    ),
                    risk_calculation_pb2.TimeSeriesPoint(
                        timestamp=Timestamp(seconds=1086400),
                        value=101.5,
                    ),
                ]
            ),
        )

        result = proto_market_data_to_domain([proto_md])
        assert len(result) == 1
        item = result[0]
        assert item["data_type"] == "HISTORICAL_PRICES"
        assert item["instrument_id"] == "AAPL"
        assert item["asset_class"] == "EQUITY"
        assert len(item["time_series"]) == 2
        assert item["time_series"][0]["timestamp_seconds"] == 1000000
        assert item["time_series"][0]["value"] == 100.0

    def test_matrix_conversion(self):
        proto_md = risk_calculation_pb2.MarketDataValue(
            data_type=risk_calculation_pb2.CORRELATION_MATRIX,
            instrument_id="",
            asset_class="",
            matrix=risk_calculation_pb2.Matrix(
                rows=2,
                cols=2,
                values=[1.0, 0.3, 0.3, 1.0],
                labels=["EQUITY", "FX"],
            ),
        )

        result = proto_market_data_to_domain([proto_md])
        assert len(result) == 1
        item = result[0]
        assert item["data_type"] == "CORRELATION_MATRIX"
        assert item["matrix"]["rows"] == 2
        assert item["matrix"]["cols"] == 2
        assert item["matrix"]["values"] == [1.0, 0.3, 0.3, 1.0]
        assert item["matrix"]["labels"] == ["EQUITY", "FX"]

    def test_scalar_extraction(self):
        proto_md = risk_calculation_pb2.MarketDataValue(
            data_type=risk_calculation_pb2.SPOT_PRICE,
            instrument_id="AAPL",
            asset_class="EQUITY",
            scalar=170.50,
        )

        result = proto_market_data_to_domain([proto_md])
        assert len(result) == 1
        item = result[0]
        assert item["data_type"] == "SPOT_PRICE"
        assert item["scalar"] == 170.50
