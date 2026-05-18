import numpy as np
from sklearn.metrics import roc_auc_score

from kinetix_risk.ml.credit_model import (
    CreditDefaultModel,
    generate_credit_training_data,
    probability_to_rating,
    train_credit_model,
)

import pytest

pytestmark = pytest.mark.unit


class TestSyntheticCreditData:
    def test_generate_training_data_shapes(self):
        X, y = generate_credit_training_data(1000, seed=42)
        assert X.shape == (1000, 7)
        assert y.shape == (1000,)

    def test_labels_are_binary(self):
        _, y = generate_credit_training_data(1000, seed=42)
        assert set(np.unique(y)) <= {0, 1}

    def test_default_rate_is_reasonable(self):
        _, y = generate_credit_training_data(5000, seed=42)
        default_rate = y.mean()
        assert 0.05 < default_rate < 0.40


class TestCreditModelTraining:
    def test_train_produces_fitted_model(self):
        X, y = generate_credit_training_data(1000, seed=42)
        model = train_credit_model(X, y, n_estimators=50, seed=42)
        assert isinstance(model, CreditDefaultModel)

    def test_model_auc_above_threshold(self):
        X, y = generate_credit_training_data(2000, seed=42)
        model = train_credit_model(X, y, n_estimators=100, seed=42)
        probs = model.predict_probability(X)
        auc = roc_auc_score(y, probs)
        assert auc > 0.70

    def test_predict_probability_range(self):
        X, y = generate_credit_training_data(1000, seed=42)
        model = train_credit_model(X, y, n_estimators=50, seed=42)
        probs = model.predict_probability(X)
        assert np.all(probs >= 0)
        assert np.all(probs <= 1)

    def test_predict_single_sample(self):
        X, y = generate_credit_training_data(1000, seed=42)
        model = train_credit_model(X, y, n_estimators=50, seed=42)
        prob = model.predict_probability(X[:1])
        assert prob.shape == (1,)


class TestRiskRating:
    def test_high_quality_rating(self):
        assert probability_to_rating(0.001) == "AAA"

    def test_investment_grade_rating(self):
        rating = probability_to_rating(0.01)
        assert rating in ("AA", "A")

    def test_speculative_rating(self):
        rating = probability_to_rating(0.10)
        assert rating in ("BB", "B")

    def test_default_rating(self):
        rating = probability_to_rating(0.50)
        assert rating in ("CCC", "D")
