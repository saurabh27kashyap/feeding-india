-- Ops can reject a bill with a reason; the kitchen fixes it and sends it again.
ALTER TABLE invoice DROP CONSTRAINT invoice_status_check;
ALTER TABLE invoice ADD CONSTRAINT invoice_status_check
    CHECK (status IN ('SUBMITTED', 'REJECTED', 'APPROVED', 'PAID'));
ALTER TABLE invoice ADD COLUMN reject_reason TEXT;
ALTER TABLE invoice ADD CONSTRAINT invoice_rejected_check
    CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL);

-- Every step of a bill's life, shown to both the kitchen and Ops.
CREATE TABLE invoice_event (
    id           BIGSERIAL PRIMARY KEY,
    invoice_id   BIGINT      NOT NULL REFERENCES invoice (id),
    kind         TEXT        NOT NULL CHECK (kind IN ('SUBMITTED', 'REJECTED', 'RESUBMITTED', 'APPROVED', 'PAID')),
    actor_label  TEXT        NOT NULL,
    note         TEXT,
    amount_paise BIGINT,
    at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX invoice_event_invoice_idx ON invoice_event (invoice_id, id);
