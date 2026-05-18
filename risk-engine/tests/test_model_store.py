import json

import pytest
import torch
from sklearn.ensemble import GradientBoostingClassifier

from kinetix_risk.ml.model_store import ModelStore
from kinetix_risk.ml.vol_predictor import VolatilityLSTM


pytestmark = pytest.mark.unit


class TestModelStore:
    def test_save_and_load_model(self, tmp_path):
        store = ModelStore(tmp_path / "models")
        model = VolatilityLSTM(input_size=1, hidden_size=32, num_layers=1)
        store.save_vol_model("EQUITY", model, "v1")

        loaded = store.load_vol_model("EQUITY", version="v1", hidden_size=32, num_layers=1)

        for (name1, p1), (name2, p2) in zip(
            model.state_dict().items(), loaded.state_dict().items()
        ):
            assert name1 == name2
            assert torch.equal(p1, p2)

    def test_save_creates_manifest(self, tmp_path):
        store = ModelStore(tmp_path / "models")
        model = VolatilityLSTM(input_size=1, hidden_size=32, num_layers=1)
        store.save_vol_model("EQUITY", model, "v1")

        manifest_path = tmp_path / "models" / "manifest.json"
        assert manifest_path.exists()
        manifest = json.loads(manifest_path.read_text())
        assert "vol_EQUITY" in manifest
        assert manifest["vol_EQUITY"]["latest"] == "v1"
        assert manifest["vol_EQUITY"]["versions"]["v1"]["hidden_size"] == 32
        assert manifest["vol_EQUITY"]["versions"]["v1"]["num_layers"] == 1

    def test_load_latest_version(self, tmp_path):
        store = ModelStore(tmp_path / "models")
        model_v1 = VolatilityLSTM(input_size=1, hidden_size=32, num_layers=1)
        model_v2 = VolatilityLSTM(input_size=1, hidden_size=32, num_layers=1)
        # Modify v2 so it's distinguishable
        with torch.no_grad():
            model_v2.linear.weight.fill_(99.0)

        store.save_vol_model("EQUITY", model_v1, "v1")
        store.save_vol_model("EQUITY", model_v2, "v2")

        loaded = store.load_vol_model("EQUITY", hidden_size=32, num_layers=1)
        assert torch.equal(loaded.linear.weight, model_v2.linear.weight)

    def test_load_nonexistent_model_raises(self, tmp_path):
        store = ModelStore(tmp_path / "models")
        with pytest.raises(FileNotFoundError):
            store.load_vol_model("NONEXISTENT")

    def test_save_credit_model_joblib(self, tmp_path):
        store = ModelStore(tmp_path / "models")
        clf = GradientBoostingClassifier(n_estimators=10, random_state=42)
        import numpy as np
        X = np.random.default_rng(42).normal(size=(50, 3))
        y = (X[:, 0] > 0).astype(int)
        clf.fit(X, y)

        store.save_credit_model(clf, "v1")
        loaded = store.load_credit_model(version="v1")

        X_test = np.random.default_rng(99).normal(size=(5, 3))
        assert list(clf.predict(X_test)) == list(loaded.predict(X_test))
