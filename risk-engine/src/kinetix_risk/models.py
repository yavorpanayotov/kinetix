from dataclasses import dataclass
from enum import Enum

import numpy as np


class AssetClass(Enum):
    EQUITY = "EQUITY"
    FIXED_INCOME = "FIXED_INCOME"
    FX = "FX"
    COMMODITY = "COMMODITY"
    DERIVATIVE = "DERIVATIVE"


class OptionType(str, Enum):
    CALL = "CALL"
    PUT = "PUT"


@dataclass(frozen=True)
class OptionPosition:
    instrument_id: str
    underlying_id: str
    option_type: OptionType
    strike: float
    expiry_days: int  # days to expiry
    spot_price: float
    implied_vol: float
    risk_free_rate: float = 0.05
    quantity: float = 1.0
    currency: str = "USD"
    dividend_yield: float = 0.0
    contract_multiplier: float = 1.0
    asset_class: AssetClass = AssetClass.EQUITY
    instrument_type: str = ""
    # Position-service-reported market value (already qty- and multiplier-scaled).
    # Required by the VaR pipeline, which addresses every position uniformly
    # via .market_value; SA-CCR still recomputes from Black-Scholes for accuracy.
    market_value: float = 0.0


class CalculationType(Enum):
    HISTORICAL = "HISTORICAL"
    PARAMETRIC = "PARAMETRIC"
    MONTE_CARLO = "MONTE_CARLO"


class ConfidenceLevel(Enum):
    CL_95 = 0.95
    CL_975 = 0.975
    CL_99 = 0.99


@dataclass(frozen=True)
class PositionRisk:
    instrument_id: str
    asset_class: AssetClass
    market_value: float
    currency: str
    credit_subtype: str | None = None  # "CREDIT_IG" or "CREDIT_HY" for SA-CCR supervisory factor lookup
    instrument_type: str = ""  # e.g. "CASH_EQUITY", "INTEREST_RATE_SWAP"


@dataclass(frozen=True)
class BondPosition(PositionRisk):
    face_value: float = 0.0
    coupon_rate: float = 0.0
    coupon_frequency: int = 2
    maturity_date: str = ""
    issuer: str = ""
    credit_rating: str = "UNRATED"
    seniority: str = "SENIOR_UNSECURED"
    day_count_convention: str = "ACT/ACT"


@dataclass(frozen=True)
class FuturePosition(PositionRisk):
    underlying_id: str = ""
    expiry_date: str = ""
    contract_size: float = 1.0


@dataclass(frozen=True)
class FxPosition(PositionRisk):
    base_currency: str = ""
    quote_currency: str = ""
    delivery_date: str = ""
    forward_rate: float = 0.0


@dataclass(frozen=True)
class SwapPosition(PositionRisk):
    notional: float = 0.0
    fixed_rate: float = 0.0
    float_index: str = ""
    float_spread: float = 0.0
    effective_date: str = ""
    maturity_date: str = ""
    pay_receive: str = "PAY_FIXED"
    fixed_frequency: int = 2
    float_frequency: int = 4
    day_count_convention: str = "ACT/360"


@dataclass(frozen=True)
class AssetClassExposure:
    asset_class: AssetClass
    total_market_value: float
    volatility: float


@dataclass(frozen=True)
class ComponentBreakdown:
    asset_class: AssetClass
    var_contribution: float
    percentage_of_total: float


@dataclass(frozen=True)
class VaRResult:
    var_value: float
    expected_shortfall: float
    component_breakdown: list[ComponentBreakdown]


@dataclass(frozen=True)
class AssetClassImpact:
    asset_class: AssetClass
    base_exposure: float
    stressed_exposure: float
    pnl_impact: float


class ScenarioCategory(str, Enum):
    REGULATORY_MANDATED = "REGULATORY_MANDATED"
    INTERNAL_APPROVED = "INTERNAL_APPROVED"
    SUPERVISORY_REQUESTED = "SUPERVISORY_REQUESTED"


@dataclass(frozen=True)
class StressScenario:
    name: str
    description: str
    vol_shocks: dict[AssetClass, float]
    correlation_override: np.ndarray | None
    price_shocks: dict[AssetClass, float]
    category: ScenarioCategory = ScenarioCategory.INTERNAL_APPROVED


@dataclass(frozen=True)
class PositionStressImpact:
    instrument_id: str
    asset_class: AssetClass
    base_market_value: float
    stressed_market_value: float
    pnl_impact: float
    percentage_of_total: float


@dataclass(frozen=True)
class StressTestResult:
    scenario_name: str
    base_var: float
    stressed_var: float
    pnl_impact: float
    asset_class_impacts: list[AssetClassImpact]
    position_impacts: list[PositionStressImpact] = ()


@dataclass(frozen=True)
class GreeksResult:
    book_id: str
    delta: dict[AssetClass, float]
    gamma: dict[AssetClass, float]
    vega: dict[AssetClass, float]
    theta: float
    rho: float
    vanna: dict[AssetClass, float] | None = None
    volga: dict[AssetClass, float] | None = None
    charm: dict[AssetClass, float] | None = None


@dataclass(frozen=True)
class ValuationResult:
    var_result: VaRResult | None
    greeks_result: GreeksResult | None
    computed_outputs: list[str]
    pv_value: float | None = None
    position_greeks: dict | None = None
    degradation_flags: list[str] = ()


class FrtbRiskClass(Enum):
    GIRR = "GIRR"
    CSR_NON_SEC = "CSR_NON_SEC"
    CSR_SEC_CTP = "CSR_SEC_CTP"
    CSR_SEC_NON_CTP = "CSR_SEC_NON_CTP"
    EQUITY = "EQUITY"
    COMMODITY = "COMMODITY"
    FX = "FX"


@dataclass(frozen=True)
class SensitivityInput:
    risk_class: FrtbRiskClass
    delta: float
    vega: float
    curvature: float


@dataclass(frozen=True)
class RiskClassCharge:
    risk_class: FrtbRiskClass
    delta_charge: float
    vega_charge: float
    curvature_charge: float
    total_charge: float


@dataclass(frozen=True)
class TenorCharge:
    """Per-tenor breakdown for GIRR delta charge (BCBS 352 Table 1)."""

    tenor_label: str
    sensitivity: float
    risk_weight: float
    weighted_sensitivity: float


@dataclass(frozen=True)
class GirrRiskClassCharge(RiskClassCharge):
    """GIRR risk class charge with per-tenor detail per BCBS 352."""

    tenor_charges: list[TenorCharge] = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        # Provide a default empty list when not supplied (frozen dataclass workaround)
        if self.tenor_charges is None:
            object.__setattr__(self, "tenor_charges", [])


@dataclass(frozen=True)
class SbmResult:
    risk_class_charges: list[RiskClassCharge]
    total_sbm_charge: float


@dataclass(frozen=True)
class CreditPositionRisk(PositionRisk):
    credit_rating: str = "UNRATED"
    seniority: str = "SENIOR_UNSECURED"
    maturity_years: float = 1.0
    sector: str = "OTHER"


@dataclass(frozen=True)
class DrcResult:
    gross_jtd: float
    hedge_benefit: float
    net_drc: float


@dataclass(frozen=True)
class EnhancedDrcResult:
    gross_jtd: float
    hedge_benefit: float
    sector_concentration_charge: float
    net_drc: float
    jtd_by_rating: dict[str, float]
    jtd_by_seniority: dict[str, float]


@dataclass(frozen=True)
class RraoResult:
    exotic_notional: float
    other_notional: float
    total_rrao: float


@dataclass(frozen=True)
class FrtbResult:
    book_id: str
    sbm: SbmResult
    drc: DrcResult
    rrao: RraoResult
    total_capital_charge: float


@dataclass(frozen=True)
class BookVaRContribution:
    book_id: str
    var_contribution: float
    percentage_of_total: float
    standalone_var: float
    diversification_benefit: float
    marginal_var: float = 0.0  # VaR per unit of book exposure (dVaR/dw_i)
    incremental_var: float = 0.0  # Change in portfolio VaR when this book is removed


@dataclass(frozen=True)
class CrossBookVaRResult:
    var_result: VaRResult
    book_contributions: list[BookVaRContribution]
    total_standalone_var: float
    diversification_benefit: float


@dataclass(frozen=True)
class StressedDiversificationResult:
    base_result: CrossBookVaRResult
    stressed_result: CrossBookVaRResult
    base_diversification_benefit: float
    stressed_diversification_benefit: float
    benefit_erosion: float  # base_benefit - stressed_benefit (how much diversification was lost)
    benefit_erosion_pct: float  # benefit_erosion / base_benefit * 100
    stress_correlation: float


class TrafficLightZone(str, Enum):
    GREEN = "GREEN"
    YELLOW = "YELLOW"
    RED = "RED"


@dataclass(frozen=True)
class BacktestResult:
    total_days: int
    violation_count: int
    violation_rate: float
    expected_violation_rate: float
    kupiec_statistic: float
    kupiec_p_value: float
    kupiec_pass: bool
    christoffersen_statistic: float
    christoffersen_p_value: float
    christoffersen_pass: bool
    traffic_light_zone: TrafficLightZone
    violations: list[dict]
