-- MealLedger schema.
-- Rules that must never break are enforced here, not only in Java:
-- the database is the last guard even if application code has a bug.

CREATE TABLE vendor (
    id                  BIGSERIAL PRIMARY KEY,
    name                TEXT NOT NULL UNIQUE,
    -- Contract rate per meal, in paise (integer money, never float).
    rate_per_meal_paise INT  NOT NULL CHECK (rate_per_meal_paise > 0)
);

CREATE TABLE route (
    id        BIGSERIAL PRIMARY KEY,
    vendor_id BIGINT NOT NULL REFERENCES vendor (id),
    name      TEXT   NOT NULL UNIQUE
);
CREATE INDEX route_vendor_idx ON route (vendor_id);

CREATE TABLE centre (
    id       BIGSERIAL PRIMARY KEY,
    route_id BIGINT  NOT NULL REFERENCES route (id),
    name     TEXT    NOT NULL,
    type     TEXT    NOT NULL CHECK (type IN ('SCHOOL', 'ANGANWADI')),
    district TEXT    NOT NULL,
    -- Children registered at the centre. Used only when there is no history.
    enrolled INT     NOT NULL CHECK (enrolled > 0),
    active   BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX centre_route_idx ON centre (route_id);

-- Which meals a centre is served. Anganwadis get breakfast + lunch.
CREATE TABLE centre_meal_slot (
    centre_id BIGINT NOT NULL REFERENCES centre (id),
    meal_slot TEXT   NOT NULL CHECK (meal_slot IN ('BREAKFAST', 'LUNCH')),
    PRIMARY KEY (centre_id, meal_slot)
);

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    phone         TEXT NOT NULL UNIQUE,
    name          TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('CENTRE', 'KITCHEN', 'OPS')),
    centre_id     BIGINT REFERENCES centre (id),
    vendor_id     BIGINT REFERENCES vendor (id),
    -- A user belongs to exactly the thing their role needs, nothing else.
    CHECK (
        (role = 'CENTRE'  AND centre_id IS NOT NULL AND vendor_id IS NULL) OR
        (role = 'KITCHEN' AND vendor_id IS NOT NULL AND centre_id IS NULL) OR
        (role = 'OPS'     AND centre_id IS NULL     AND vendor_id IS NULL)
    )
);

-- One row = one real-world meal event: this centre, this day, this meal.
CREATE TABLE meal_order (
    id               BIGSERIAL PRIMARY KEY,
    centre_id        BIGINT      NOT NULL REFERENCES centre (id),
    -- Snapshot of route and vendor when the order was created, so moving a
    -- centre to another route later does not rewrite history.
    route_id         BIGINT      NOT NULL REFERENCES route (id),
    vendor_id        BIGINT      NOT NULL REFERENCES vendor (id),
    service_date     DATE        NOT NULL,
    meal_slot        TEXT        NOT NULL CHECK (meal_slot IN ('BREAKFAST', 'LUNCH')),
    suggested_qty    INT         NOT NULL CHECK (suggested_qty >= 0),
    suggestion_basis TEXT        NOT NULL,
    requested_qty    INT         NOT NULL CHECK (requested_qty >= 0),
    locked_qty       INT CHECK (locked_qty >= 0),
    received_qty     INT CHECK (received_qty >= 0),
    status           TEXT        NOT NULL CHECK (status IN ('REQUESTED', 'LOCKED', 'DISPATCHED', 'RECEIVED', 'CLOSED')),
    cutoff_at        TIMESTAMPTZ NOT NULL,
    confirm_by       TIMESTAMPTZ NOT NULL,
    closed_reason    TEXT,
    dispatched_at    TIMESTAMPTZ,
    received_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (centre_id, service_date, meal_slot),
    CHECK (status <> 'CLOSED' OR closed_reason IS NOT NULL),
    CHECK (status NOT IN ('LOCKED', 'DISPATCHED', 'RECEIVED') OR locked_qty IS NOT NULL),
    CHECK (status <> 'RECEIVED' OR received_qty IS NOT NULL)
);
CREATE INDEX meal_order_route_day_idx  ON meal_order (service_date, route_id, meal_slot);
CREATE INDEX meal_order_vendor_day_idx ON meal_order (vendor_id, service_date);
-- Small partial index: the cutoff sweeper only ever looks at open orders.
CREATE INDEX meal_order_open_cutoff_idx ON meal_order (cutoff_at) WHERE status = 'REQUESTED';

-- Audit trail: every change to an order, who made it, and why.
CREATE TABLE order_event (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT      NOT NULL REFERENCES meal_order (id),
    from_status   TEXT,
    to_status     TEXT        NOT NULL,
    qty           INT,
    actor_user_id BIGINT REFERENCES app_user (id),
    actor_label   TEXT        NOT NULL,
    reason        TEXT,
    at            TIMESTAMPTZ NOT NULL
);
CREATE INDEX order_event_order_idx ON order_event (order_id, id);

-- Children present, as counted by FRAT (or entered by hand where FRAT is not used).
CREATE TABLE attendance (
    centre_id    BIGINT      NOT NULL REFERENCES centre (id),
    service_date DATE        NOT NULL,
    meal_slot    TEXT        NOT NULL CHECK (meal_slot IN ('BREAKFAST', 'LUNCH')),
    present      INT         NOT NULL CHECK (present >= 0),
    source       TEXT        NOT NULL CHECK (source IN ('FRAT', 'MANUAL')),
    revisions    INT         NOT NULL DEFAULT 0,
    recorded_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (centre_id, service_date, meal_slot)
);

CREATE TABLE invoice (
    id                  BIGSERIAL PRIMARY KEY,
    vendor_id           BIGINT      NOT NULL REFERENCES vendor (id),
    service_date        DATE        NOT NULL,
    status              TEXT        NOT NULL CHECK (status IN ('SUBMITTED', 'APPROVED')),
    submitted_at        TIMESTAMPTZ NOT NULL,
    approved_at         TIMESTAMPTZ,
    approved_by         BIGINT REFERENCES app_user (id),
    total_payable_paise BIGINT CHECK (total_payable_paise >= 0),
    -- One invoice per kitchen per day.
    UNIQUE (vendor_id, service_date),
    CHECK (status <> 'APPROVED' OR (approved_at IS NOT NULL AND total_payable_paise IS NOT NULL))
);

CREATE TABLE invoice_line (
    id           BIGSERIAL PRIMARY KEY,
    invoice_id   BIGINT NOT NULL REFERENCES invoice (id),
    -- An order can be billed only once, ever.
    order_id     BIGINT NOT NULL UNIQUE REFERENCES meal_order (id),
    billed_qty   INT    NOT NULL CHECK (billed_qty >= 0),
    -- Filled when Ops approves: a frozen snapshot of the reconciliation.
    payable_qty  INT CHECK (payable_qty >= 0),
    rate_paise   INT CHECK (rate_paise > 0),
    amount_paise BIGINT CHECK (amount_paise >= 0),
    flag         TEXT
);
CREATE INDEX invoice_line_invoice_idx ON invoice_line (invoice_id);

CREATE TABLE feedback (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT      NOT NULL UNIQUE REFERENCES meal_order (id),
    quantity_ok  BOOLEAN     NOT NULL,
    quality_ok   BOOLEAN     NOT NULL,
    on_time      BOOLEAN     NOT NULL,
    note         TEXT,
    tags         TEXT[]      NOT NULL DEFAULT '{}',
    -- NONE: no note to tag. PENDING: waiting for the LLM. FAILED: gave up after retries.
    tag_status   TEXT        NOT NULL CHECK (tag_status IN ('NONE', 'PENDING', 'TAGGED', 'FAILED')),
    tag_attempts INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX feedback_pending_idx ON feedback (id) WHERE tag_status = 'PENDING';
