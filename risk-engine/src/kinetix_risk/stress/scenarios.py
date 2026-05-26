import numpy as np

from kinetix_risk.models import AssetClass, ScenarioCategory, StressScenario

# Re-export for convenience — callers can import ScenarioCategory from here.
__all__ = ["HISTORICAL_SCENARIOS", "ScenarioCategory", "get_scenario", "list_scenarios"]

# High-correlation matrix for crisis periods (near-1 correlations)
_CRISIS_CORRELATION = np.array([
    #  EQUITY    FI      FX     COMM    DERIV
    [  1.00,   0.60,   0.70,   0.80,   0.90],  # EQUITY
    [  0.60,   1.00,   0.40,   0.30,   0.50],  # FIXED_INCOME
    [  0.70,   0.40,   1.00,   0.60,   0.65],  # FX
    [  0.80,   0.30,   0.60,   1.00,   0.75],  # COMMODITY
    [  0.90,   0.50,   0.65,   0.75,   1.00],  # DERIVATIVE
])

_COVID_CORRELATION = np.array([
    #  EQUITY    FI      FX     COMM    DERIV
    [  1.00,   0.50,   0.60,   0.70,   0.85],  # EQUITY
    [  0.50,   1.00,   0.30,   0.20,   0.40],  # FIXED_INCOME
    [  0.60,   0.30,   1.00,   0.55,   0.60],  # FX
    [  0.70,   0.20,   0.55,   1.00,   0.70],  # COMMODITY
    [  0.85,   0.40,   0.60,   0.70,   1.00],  # DERIVATIVE
])

_EURO_CRISIS_CORRELATION = np.array([
    #  EQUITY    FI      FX     COMM    DERIV
    [  1.00,   0.30,   0.65,   0.50,   0.80],  # EQUITY
    [  0.30,   1.00,   0.20,   0.10,   0.25],  # FIXED_INCOME
    [  0.65,   0.20,   1.00,   0.45,   0.55],  # FX
    [  0.50,   0.10,   0.45,   1.00,   0.50],  # COMMODITY
    [  0.80,   0.25,   0.55,   0.50,   1.00],  # DERIVATIVE
])

_EM_CONTAGION_CORRELATION = np.array([
    #  EQUITY    FI      FX     COMM    DERIV
    [  1.00,   0.45,   0.75,   0.55,   0.85],  # EQUITY
    [  0.45,   1.00,   0.35,   0.15,   0.40],  # FIXED_INCOME
    [  0.75,   0.35,   1.00,   0.50,   0.70],  # FX
    [  0.55,   0.15,   0.50,   1.00,   0.60],  # COMMODITY
    [  0.85,   0.40,   0.70,   0.60,   1.00],  # DERIVATIVE
])

HISTORICAL_SCENARIOS: dict[str, StressScenario] = {
    "GFC_2008": StressScenario(
        # @guidance: Calibrated to Lehman week (Sep 15-19 2008). S&P 500 fell ~18%
        # peak-to-trough in the acute phase; VIX spiked to 80. IG credit spreads
        # widened 200-300bp. Source: BIS Working Paper No. 333, Fed stress-test
        # scenarios, BCBS Basel III calibration period.
        name="GFC_2008",
        description="Global Financial Crisis 2008: severe equity and commodity selloff with vol spike",
        vol_shocks={
            AssetClass.EQUITY: 3.0,
            AssetClass.FIXED_INCOME: 1.5,
            AssetClass.FX: 2.0,
            AssetClass.COMMODITY: 2.5,
            AssetClass.DERIVATIVE: 3.0,
        },
        correlation_override=_CRISIS_CORRELATION,
        price_shocks={
            AssetClass.EQUITY: 0.60,
            AssetClass.FIXED_INCOME: 0.95,
            AssetClass.FX: 0.85,
            AssetClass.COMMODITY: 0.70,
            AssetClass.DERIVATIVE: 0.55,
        },
        category=ScenarioCategory.REGULATORY_MANDATED,
    ),
    "COVID_2020": StressScenario(
        # @guidance: Feb 24 - Mar 20 2020. S&P 500 fell 34% in 33 days, fastest
        # bear market on record. WTI crude collapsed 66%. VIX hit 82.69 intraday.
        # 10y UST yields fell from 1.5% to 0.5% (flight-to-safety offset by
        # liquidity stress in March). Source: Fed SCB 2020, ECB DFAST 2020.
        name="COVID_2020",
        description="COVID-19 pandemic 2020: rapid equity decline, commodity crash, vol spike",
        vol_shocks={
            AssetClass.EQUITY: 2.5,
            AssetClass.FIXED_INCOME: 1.3,
            AssetClass.FX: 1.8,
            AssetClass.COMMODITY: 2.0,
            AssetClass.DERIVATIVE: 2.5,
        },
        correlation_override=_COVID_CORRELATION,
        price_shocks={
            AssetClass.EQUITY: 0.65,
            AssetClass.FIXED_INCOME: 0.97,
            AssetClass.FX: 0.90,
            AssetClass.COMMODITY: 0.75,
            AssetClass.DERIVATIVE: 0.60,
        },
        category=ScenarioCategory.REGULATORY_MANDATED,
    ),
    "TAPER_TANTRUM_2013": StressScenario(
        # @guidance: May-Jun 2013. Fed Chair Bernanke's taper hint caused 10y Treasury
        # yields to jump ~100bp in 7 weeks. EM equities fell ~10%; EM FX (BRL, INR, IDR)
        # fell 10-15%. DM equities broadly flat to slightly down. Source: BIS Quarterly
        # Review Sep 2013; IMF GFSR Oct 2013.
        name="TAPER_TANTRUM_2013",
        description="Taper Tantrum 2013: fixed income selloff, moderate equity decline",
        vol_shocks={
            AssetClass.EQUITY: 1.5,
            AssetClass.FIXED_INCOME: 2.0,
            AssetClass.FX: 1.3,
            AssetClass.COMMODITY: 1.2,
            AssetClass.DERIVATIVE: 1.5,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.95,
            AssetClass.FIXED_INCOME: 0.90,
            AssetClass.FX: 0.95,
            AssetClass.COMMODITY: 0.97,
            AssetClass.DERIVATIVE: 0.92,
        },
        category=ScenarioCategory.REGULATORY_MANDATED,
    ),
    "EURO_CRISIS_2011": StressScenario(
        # @guidance: Aug-Nov 2011. Italian 10y yields breached 7% (perceived point of no
        # return). Eurostoxx 50 fell ~30% peak-to-trough. EURUSD fell from 1.49 to 1.30.
        # ECB LTRO ultimately stabilised the system. Source: ECB Financial Stability Review
        # Dec 2011; Basel III impact study.
        name="EURO_CRISIS_2011",
        description="European Sovereign Debt Crisis 2011: equity and FX decline, elevated correlations",
        vol_shocks={
            AssetClass.EQUITY: 2.0,
            AssetClass.FIXED_INCOME: 1.5,
            AssetClass.FX: 1.8,
            AssetClass.COMMODITY: 1.3,
            AssetClass.DERIVATIVE: 2.0,
        },
        correlation_override=_EURO_CRISIS_CORRELATION,
        price_shocks={
            AssetClass.EQUITY: 0.80,
            AssetClass.FIXED_INCOME: 0.92,
            AssetClass.FX: 0.85,
            AssetClass.COMMODITY: 0.90,
            AssetClass.DERIVATIVE: 0.75,
        },
        category=ScenarioCategory.REGULATORY_MANDATED,
    ),
    "BLACK_MONDAY_1987": StressScenario(
        # @guidance: Oct 19 1987. Dow Jones fell 22.6% in a single session — the largest
        # one-day percentage drop in history. Programme trading and portfolio insurance
        # amplified the selloff. VIX-equivalent (retrospective) estimated at ~150 (4x
        # normal). US 30y Treasuries rallied ~2% (flight-to-quality). DXY fell ~5%.
        # Source: SEC/CFTC report 1988; Brady Commission Report.
        name="BLACK_MONDAY_1987",
        description="Black Monday Oct 1987: largest single-day equity drop, vol 4x, flight to quality",
        vol_shocks={
            AssetClass.EQUITY: 4.0,
            AssetClass.FIXED_INCOME: 1.2,
            AssetClass.FX: 1.5,
            AssetClass.COMMODITY: 1.3,
            AssetClass.DERIVATIVE: 4.0,
        },
        correlation_override=_CRISIS_CORRELATION,
        price_shocks={
            AssetClass.EQUITY: 0.78,
            AssetClass.FIXED_INCOME: 1.02,  # flight-to-quality gain
            AssetClass.FX: 0.95,
            AssetClass.COMMODITY: 0.97,
            AssetClass.DERIVATIVE: 0.72,
        },
        category=ScenarioCategory.REGULATORY_MANDATED,
    ),
    "LTCM_RUSSIAN_1998": StressScenario(
        # @guidance: Aug-Sep 1998. Russia defaulted on GKO bonds (Aug 17); LTCM lost $4.6bn.
        # S&P 500 fell ~15% peak-to-trough. EM sovereign spreads widened 600-800bp.
        # EM currencies collapsed (RUB -75%). DM fixed income rallied (credit spread
        # proxy shock of -5% EM bonds modelled via FIXED_INCOME price shock).
        # Source: FRBNY report on LTCM 1999; BIS Working Paper 70.
        name="LTCM_RUSSIAN_1998",
        description="LTCM/Russian default 1998: EM contagion, credit spread widening, equity selloff",
        vol_shocks={
            AssetClass.EQUITY: 2.5,
            AssetClass.FIXED_INCOME: 2.0,
            AssetClass.FX: 2.5,
            AssetClass.COMMODITY: 1.5,
            AssetClass.DERIVATIVE: 2.5,
        },
        correlation_override=_CRISIS_CORRELATION,
        price_shocks={
            AssetClass.EQUITY: 0.85,
            AssetClass.FIXED_INCOME: 0.95,  # EM bond losses
            AssetClass.FX: 0.90,
            AssetClass.COMMODITY: 0.90,
            AssetClass.DERIVATIVE: 0.80,
        },
        category=ScenarioCategory.REGULATORY_MANDATED,
    ),
    "DOTCOM_2000": StressScenario(
        # @guidance: Mar 2000 - Dec 2000 (acute phase). NASDAQ fell 45%; S&P 500 fell ~25%.
        # Equity volatility roughly doubled. Long-dated Treasuries rallied ~3% as Fed
        # eased. FX broadly unchanged (no currency crisis component). Source: Shiller
        # CAPE data; NBER working paper 8090.
        name="DOTCOM_2000",
        description="Dot-com bust 2000: equity-specific selloff, flight to quality in fixed income",
        vol_shocks={
            AssetClass.EQUITY: 2.0,
            AssetClass.FIXED_INCOME: 1.1,
            AssetClass.FX: 1.1,
            AssetClass.COMMODITY: 1.0,
            AssetClass.DERIVATIVE: 2.0,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.75,
            AssetClass.FIXED_INCOME: 1.03,  # flight-to-quality gain
            AssetClass.FX: 1.00,
            AssetClass.COMMODITY: 0.98,
            AssetClass.DERIVATIVE: 0.70,
        },
        category=ScenarioCategory.REGULATORY_MANDATED,
    ),
    "SEPT_11_2001": StressScenario(
        # @guidance: Sep 11-21 2001. NYSE closed 4 trading days. S&P 500 fell 11.6% on
        # reopening week. VIX jumped from ~26 to ~43 (roughly 3x pre-event norm of ~14).
        # 10y UST yields fell ~50bp (flight-to-quality). USD strengthened initially then
        # fell as Fed cut rates aggressively. Source: NYSE report 2001; Fed Beige Book.
        name="SEPT_11_2001",
        description="9/11 attacks 2001: equity shock, extreme vol spike, flight to Treasuries",
        vol_shocks={
            AssetClass.EQUITY: 3.0,
            AssetClass.FIXED_INCOME: 1.3,
            AssetClass.FX: 1.5,
            AssetClass.COMMODITY: 1.8,
            AssetClass.DERIVATIVE: 3.0,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.88,
            AssetClass.FIXED_INCOME: 1.05,  # flight-to-quality gain
            AssetClass.FX: 0.95,
            AssetClass.COMMODITY: 1.02,  # oil initially spiked on uncertainty
            AssetClass.DERIVATIVE: 0.85,
        },
        category=ScenarioCategory.REGULATORY_MANDATED,
    ),
    "CHF_DEPEG_2015": StressScenario(
        # @guidance: Jan 15 2015. SNB removed the EURCHF 1.20 floor. CHF appreciated
        # ~30% intraday against EUR, ~20% against USD. SMI fell ~10%. IV on CHF options
        # doubled. Fixed income broadly unaffected outside Switzerland. Source: SNB
        # press release; BIS Quarterly Review Mar 2015.
        name="CHF_DEPEG_2015",
        description="CHF depeg Jan 2015: extreme FX shock as SNB removed EURCHF floor",
        vol_shocks={
            AssetClass.EQUITY: 2.0,
            AssetClass.FIXED_INCOME: 1.1,
            AssetClass.FX: 4.0,  # FX vol spiked dramatically
            AssetClass.COMMODITY: 1.1,
            AssetClass.DERIVATIVE: 2.5,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.95,
            AssetClass.FIXED_INCOME: 1.00,
            AssetClass.FX: 1.30,  # CHF appreciated 30% (> 1.0 means base FX gains)
            AssetClass.COMMODITY: 1.00,
            AssetClass.DERIVATIVE: 0.90,
        },
        category=ScenarioCategory.INTERNAL_APPROVED,
    ),
    "BREXIT_2016": StressScenario(
        # @guidance: Jun 24 2016 (result day). GBPUSD fell from 1.50 to 1.32 (~12%),
        # stabilising around 1.37 by day-end (~8% net). FTSE 100 fell ~8% on open but
        # recovered to -3% on weaker GBP boosting exporters. FTSE 250 (domestics) fell
        # ~7%. Gilts rallied. Source: Bank of England Financial Stability Report Jul 2016.
        name="BREXIT_2016",
        description="Brexit referendum Jun 2016: GBP selloff, UK equity decline, gilt rally",
        vol_shocks={
            AssetClass.EQUITY: 2.0,
            AssetClass.FIXED_INCOME: 1.2,
            AssetClass.FX: 3.0,  # GBP vol spiked
            AssetClass.COMMODITY: 1.1,
            AssetClass.DERIVATIVE: 2.0,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.92,
            AssetClass.FIXED_INCOME: 1.02,  # gilt rally
            AssetClass.FX: 0.90,  # GBP -10%
            AssetClass.COMMODITY: 0.98,
            AssetClass.DERIVATIVE: 0.88,
        },
        category=ScenarioCategory.INTERNAL_APPROVED,
    ),
    "VOLMAGEDDON_2018": StressScenario(
        # @guidance: Feb 5 2018. VIX spiked from 17 to 37 (intraday high 50) in a single
        # session — a 5x move. XIV and SVXY (short-vol products) lost >90% of value.
        # S&P 500 fell ~4% on the day, ~10% peak-to-trough. Fixed income broadly
        # unchanged (no credit event). Source: CBOE white paper on Feb 2018 volatility;
        # SEC staff report on short-vol ETPs.
        name="VOLMAGEDDON_2018",
        description="Volmageddon Feb 2018: extreme vol spike from short-vol product unwind",
        vol_shocks={
            AssetClass.EQUITY: 5.0,  # defining feature: extreme vol shock
            AssetClass.FIXED_INCOME: 1.1,
            AssetClass.FX: 1.3,
            AssetClass.COMMODITY: 1.2,
            AssetClass.DERIVATIVE: 5.0,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.90,
            AssetClass.FIXED_INCOME: 1.01,
            AssetClass.FX: 1.00,
            AssetClass.COMMODITY: 0.98,
            AssetClass.DERIVATIVE: 0.85,
        },
        category=ScenarioCategory.INTERNAL_APPROVED,
    ),
    "OIL_NEGATIVE_2020": StressScenario(
        # @guidance: Apr 20 2020. WTI May 2020 futures settled at -$37.63/bbl — first
        # negative oil price in history, driven by storage constraints during COVID lockdowns.
        # Modelled as near-total loss (0.01 multiplier) on commodity exposure. Equity
        # impact was smaller as the event was largely contained to energy sector.
        # Source: CME Group analysis; EIA market report Apr 2020.
        name="OIL_NEGATIVE_2020",
        description="WTI negative Apr 2020: near-total commodity loss from storage crisis",
        vol_shocks={
            AssetClass.EQUITY: 2.0,
            AssetClass.FIXED_INCOME: 1.2,
            AssetClass.FX: 1.3,
            AssetClass.COMMODITY: 5.0,  # commodity vol exploded
            AssetClass.DERIVATIVE: 2.0,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.95,
            AssetClass.FIXED_INCOME: 1.00,
            AssetClass.FX: 1.00,
            AssetClass.COMMODITY: 0.01,  # near-total loss on commodity exposure
            AssetClass.DERIVATIVE: 0.90,
        },
        category=ScenarioCategory.INTERNAL_APPROVED,
    ),
    "SVB_BANKING_2023": StressScenario(
        # @guidance: Mar 8-15 2023. SVB disclosed $1.8bn loss on HTM portfolio; run began.
        # FDIC took over SVB (Mar 10) and Signature Bank (Mar 12). KBW Bank Index fell
        # ~20%; broader S&P 500 fell ~5%. 2y UST yields fell 100bp in 3 days (fastest
        # since 1987) as markets priced emergency Fed cuts. Credit spreads on financials
        # widened ~100bp. Source: FDIC SVB post-mortem report May 2023.
        name="SVB_BANKING_2023",
        description="SVB banking crisis Mar 2023: financials selloff, rates rally, credit spread widening",
        vol_shocks={
            AssetClass.EQUITY: 2.0,
            AssetClass.FIXED_INCOME: 2.5,  # rates vol spiked on rapid repricing
            AssetClass.FX: 1.3,
            AssetClass.COMMODITY: 1.2,
            AssetClass.DERIVATIVE: 2.0,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.90,
            AssetClass.FIXED_INCOME: 0.97,  # HTM losses plus spread widening
            AssetClass.FX: 0.98,
            AssetClass.COMMODITY: 0.96,
            AssetClass.DERIVATIVE: 0.88,
        },
        category=ScenarioCategory.INTERNAL_APPROVED,
    ),
    "RATES_SHOCK_2022": StressScenario(
        # @guidance: 2022 calendar year. Fed hiked 425bp; 10y UST went from 1.5% to 3.9%,
        # implying ~15% price decline on 10y bond. S&P 500 fell ~20%. Unusual joint equity
        # and fixed income decline (60/40 portfolio worst year in decades). Vol elevated
        # but not extreme (VIX averaged ~26). Source: Fed H.15 series; S&P historical data.
        name="RATES_SHOCK_2022",
        description="Rates shock 2022: simultaneous equity and fixed income decline, unusual correlation",
        vol_shocks={
            AssetClass.EQUITY: 1.5,
            AssetClass.FIXED_INCOME: 2.0,
            AssetClass.FX: 1.4,
            AssetClass.COMMODITY: 1.5,
            AssetClass.DERIVATIVE: 1.5,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.90,
            AssetClass.FIXED_INCOME: 0.85,  # ~15% loss on 10y bond
            AssetClass.FX: 0.95,
            AssetClass.COMMODITY: 1.05,  # commodities initially outperformed
            AssetClass.DERIVATIVE: 0.88,
        },
        category=ScenarioCategory.REGULATORY_MANDATED,
    ),
    "+100BPS_PARALLEL": StressScenario(
        # @guidance: Canned demo scenario surfaced on the Risk overview tile
        # (issue kx-wxy). Parallel +100bp shift across the rates curve. Using
        # an assumed modified duration of 8y for the FI book (broad IG bond
        # index proxy), a +100bp move maps to a -8% price impact, i.e. price
        # shock factor of 0.92. Equity is modestly discounted via a +100bp
        # discount-rate increase (price_shock ≈ 0.98), and derivative price
        # shock follows equity's tail per the parametric grid convention.
        # FX and commodity are held flat — a parallel rates shock does not
        # mechanically move those asset classes in isolation.
        # No vol or correlation override — this is a deterministic delta-PV
        # tile, not a tail-risk scenario.
        name="+100BPS_PARALLEL",
        description="+100bps parallel rates shock (8y duration FI proxy)",
        vol_shocks={
            AssetClass.EQUITY: 1.0,
            AssetClass.FIXED_INCOME: 1.0,
            AssetClass.FX: 1.0,
            AssetClass.COMMODITY: 1.0,
            AssetClass.DERIVATIVE: 1.0,
        },
        correlation_override=None,
        price_shocks={
            AssetClass.EQUITY: 0.98,
            AssetClass.FIXED_INCOME: 0.92,  # -8% via 8y modified duration on +100bp
            AssetClass.FX: 1.0,
            AssetClass.COMMODITY: 1.0,
            AssetClass.DERIVATIVE: 0.975,
        },
        category=ScenarioCategory.INTERNAL_APPROVED,
    ),
    "EM_CONTAGION": StressScenario(
        # @guidance: Stylised EM contagion scenario (composite of 1994 Tequila Crisis,
        # 1997 Asian Financial Crisis, 1998 Russian default). EM equities fall 20%,
        # EM FX falls 15% vs USD. EM sovereign bonds fall 5% (spread widening).
        # DM assets modestly impacted. Source: IMF World Economic Outlook Oct 2014
        # (contagion chapter); BIS Quarterly Review Sep 2015.
        name="EM_CONTAGION",
        description="EM contagion scenario: EM equity and FX rout, DM safe-haven flows",
        vol_shocks={
            AssetClass.EQUITY: 2.5,
            AssetClass.FIXED_INCOME: 1.8,
            AssetClass.FX: 2.5,
            AssetClass.COMMODITY: 1.5,
            AssetClass.DERIVATIVE: 2.5,
        },
        correlation_override=_EM_CONTAGION_CORRELATION,
        price_shocks={
            AssetClass.EQUITY: 0.80,
            AssetClass.FIXED_INCOME: 0.95,
            AssetClass.FX: 0.85,
            AssetClass.COMMODITY: 0.90,
            AssetClass.DERIVATIVE: 0.78,
        },
        category=ScenarioCategory.INTERNAL_APPROVED,
    ),
}


def get_scenario(name: str) -> StressScenario:
    scenario = HISTORICAL_SCENARIOS.get(name)
    if scenario is None:
        raise KeyError(f"Unknown stress scenario: {name}")
    return scenario


def list_scenarios() -> list[str]:
    return list(HISTORICAL_SCENARIOS.keys())
