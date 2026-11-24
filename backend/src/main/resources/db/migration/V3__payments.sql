-- Approval is not payment. Ops approves the amount; finance pays it and records the
-- bank reference (UTR). A UTR can settle only one invoice, so double payment is caught.
ALTER TABLE invoice DROP CONSTRAINT invoice_status_check;
ALTER TABLE invoice ADD CONSTRAINT invoice_status_check CHECK (status IN ('SUBMITTED', 'APPROVED', 'PAID'));

ALTER TABLE invoice ADD COLUMN paid_at     TIMESTAMPTZ;
ALTER TABLE invoice ADD COLUMN paid_by     BIGINT REFERENCES app_user (id);
ALTER TABLE invoice ADD COLUMN payment_ref TEXT;

ALTER TABLE invoice ADD CONSTRAINT invoice_paid_check
    CHECK (status <> 'PAID' OR (paid_at IS NOT NULL AND payment_ref IS NOT NULL AND approved_at IS NOT NULL));

CREATE UNIQUE INDEX invoice_payment_ref_uq ON invoice (payment_ref) WHERE payment_ref IS NOT NULL;
CREATE INDEX invoice_day_idx ON invoice (service_date);
