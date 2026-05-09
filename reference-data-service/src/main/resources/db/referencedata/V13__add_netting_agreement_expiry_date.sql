-- Adds an optional expiryDate column to netting_agreements so that an
-- expired ISDA / netting agreement can be modelled and surfaced as a
-- distinct agreementStatus on the counterparty-risk dashboard.
--
-- Nullable: most agreements have no expiry; a NULL expiryDate is treated
-- as ACTIVE downstream.

ALTER TABLE netting_agreements
    ADD COLUMN expiry_date TIMESTAMPTZ;
