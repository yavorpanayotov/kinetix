import numpy as np
import torch

from kinetix_risk.ml.data_generator import (
    generate_synthetic_returns,
    generate_vol_training_data,
)
from kinetix_risk.ml.vol_predictor import VolatilityLSTM, train_vol_model

import pytest

pytestmark = pytest.mark.unit


class TestSyntheticDataGeneration:
    def test_generate_returns_shape(self):
        returns = generate_synthetic_returns(0.20, 500, seed=42)
        assert returns.shape == (500,)

    def test_generate_returns_realized_vol_close_to_target(self):
        target_vol = 0.20
        returns = generate_synthetic_returns(target_vol, 5000, seed=42)
        realized_vol = np.std(returns) * np.sqrt(252)
        assert abs(realized_vol - target_vol) / target_vol < 0.05

    def test_generate_vol_training_data_shapes(self):
        X, y = generate_vol_training_data(0.20, 500, window_size=60, seed=42)
        assert X.shape == (440, 60, 1)
        assert y.shape == (440,)

    def test_training_labels_are_positive(self):
        _, y = generate_vol_training_data(0.20, 500, window_size=60, seed=42)
        assert np.all(y > 0)


class TestVolatilityLSTM:
    def test_model_forward_pass_shape(self):
        model = VolatilityLSTM(input_size=1, hidden_size=32, num_layers=1)
        x = torch.randn(8, 60, 1)
        output = model(x)
        assert output.shape == (8, 1)

    def test_model_output_is_positive(self):
        model = VolatilityLSTM(input_size=1, hidden_size=32, num_layers=1)
        x = torch.randn(16, 60, 1)
        output = model(x)
        assert torch.all(output > 0)


class TestVolModelTraining:
    def test_train_reduces_loss(self):
        X, y = generate_vol_training_data(0.20, 300, window_size=60, seed=42)
        _, losses = train_vol_model(X, y, hidden_size=32, num_layers=1, epochs=20, batch_size=32, lr=0.005, seed=42)
        assert losses[-1] < losses[0]

    def test_trained_model_predicts_near_target_vol(self):
        target_vol = 0.20
        X, y = generate_vol_training_data(target_vol, 500, window_size=60, seed=42)
        model, _ = train_vol_model(X, y, hidden_size=32, num_layers=1, epochs=30, batch_size=32, lr=0.005, seed=42)
        test_X, _ = generate_vol_training_data(target_vol, 200, window_size=60, seed=99)
        with torch.no_grad():
            preds = model(torch.FloatTensor(test_X))
        mean_pred = preds.mean().item()
        assert abs(mean_pred - target_vol) / target_vol < 0.30

    def test_different_asset_classes_produce_different_vols(self):
        X_eq, y_eq = generate_vol_training_data(0.20, 500, window_size=60, seed=42)
        model_eq, _ = train_vol_model(X_eq, y_eq, hidden_size=32, num_layers=1, epochs=30, batch_size=32, lr=0.005, seed=42)

        X_fi, y_fi = generate_vol_training_data(0.06, 500, window_size=60, seed=42)
        model_fi, _ = train_vol_model(X_fi, y_fi, hidden_size=32, num_layers=1, epochs=30, batch_size=32, lr=0.005, seed=42)

        test_X_eq, _ = generate_vol_training_data(0.20, 200, window_size=60, seed=99)
        test_X_fi, _ = generate_vol_training_data(0.06, 200, window_size=60, seed=99)

        with torch.no_grad():
            eq_pred = model_eq(torch.FloatTensor(test_X_eq)).mean().item()
            fi_pred = model_fi(torch.FloatTensor(test_X_fi)).mean().item()

        assert eq_pred > fi_pred
