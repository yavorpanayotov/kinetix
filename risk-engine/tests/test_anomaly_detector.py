import numpy as np

from kinetix_risk.ml.anomaly_detector import (
    AnomalyResult,
    generate_anomaly_training_data,
    train_anomaly_detector,
)

import pytest

pytestmark = pytest.mark.unit


class TestAnomalyTrainingData:
    def test_generate_normal_data_shape(self):
        data = generate_anomaly_training_data(1000, seed=42)
        assert data.shape == (1000, 1)

    def test_normal_data_has_reasonable_range(self):
        data = generate_anomaly_training_data(1000, seed=42, mean=0.0, std=1.0)
        assert data.mean() < 1.0
        assert data.mean() > -1.0
        assert data.std() < 2.0


class TestAnomalyDetector:
    def test_train_produces_fitted_detector(self):
        data = generate_anomaly_training_data(500, seed=42)
        detector = train_anomaly_detector(data, contamination=0.05, seed=42)
        assert detector is not None
        assert detector.model is not None

    def test_normal_data_not_flagged(self):
        data = generate_anomaly_training_data(500, seed=42, mean=0.0, std=1.0)
        detector = train_anomaly_detector(data, contamination=0.05, seed=42)
        # Test on normal data
        normal_values = list(np.random.default_rng(99).normal(0, 1, 100))
        results = detector.detect(normal_values)
        anomaly_rate = sum(1 for r in results if r.is_anomaly) / len(results)
        assert anomaly_rate < 0.20

    def test_extreme_values_flagged_as_anomalies(self):
        data = generate_anomaly_training_data(500, seed=42, mean=0.0, std=1.0)
        detector = train_anomaly_detector(data, contamination=0.05, seed=42)
        extreme_values = [100.0, -100.0, 50.0, -50.0]
        results = detector.detect(extreme_values)
        flagged = sum(1 for r in results if r.is_anomaly)
        assert flagged == len(extreme_values)

    def test_detect_single_value(self):
        data = generate_anomaly_training_data(500, seed=42)
        detector = train_anomaly_detector(data, contamination=0.05, seed=42)
        result = detector.detect_single(0.0)
        assert isinstance(result, AnomalyResult)
        assert isinstance(result.is_anomaly, bool)
        assert isinstance(result.anomaly_score, float)

    def test_anomaly_score_convention(self):
        data = generate_anomaly_training_data(500, seed=42, mean=0.0, std=1.0)
        detector = train_anomaly_detector(data, contamination=0.05, seed=42)
        normal_result = detector.detect_single(0.0)
        extreme_result = detector.detect_single(100.0)
        # Isolation Forest: lower score = more anomalous
        assert extreme_result.anomaly_score < normal_result.anomaly_score
