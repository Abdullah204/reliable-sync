-- Destination tables for Acme CRM data.
-- external_id (the source system's ID) is the idempotency key: every write is an upsert on it.
-- Our own surrogate `id` is kept separate, so downstream joins never depend on a vendor's ID format.

CREATE TABLE acme.customer (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_id       text        NOT NULL UNIQUE,
    name              text        NOT NULL,
    email             text,
    country           char(2),
    source_created_at timestamptz,
    source_updated_at timestamptz NOT NULL,
    content_hash      text        NOT NULL,  -- sha256 of the source payload; separates UPDATED from UNCHANGED
    raw               jsonb       NOT NULL,  -- full payload as received, for audits and backfills of new columns
    first_synced_at   timestamptz NOT NULL DEFAULT now(),
    last_synced_at    timestamptz NOT NULL DEFAULT now()
);

-- No foreign key to acme.customer: object types sync independently, so an invoice may arrive
-- before its customer. Referential checks belong in downstream models, not in ingestion.
CREATE TABLE acme.invoice (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_id          text           NOT NULL UNIQUE,
    customer_external_id text           NOT NULL,
    number               text           NOT NULL,
    amount               numeric(14, 2) NOT NULL CHECK (amount >= 0),
    currency             char(3)        NOT NULL,
    status               text           NOT NULL,
    due_date             date,
    source_updated_at    timestamptz    NOT NULL,
    content_hash         text           NOT NULL,
    raw                  jsonb          NOT NULL,
    first_synced_at      timestamptz    NOT NULL DEFAULT now(),
    last_synced_at       timestamptz    NOT NULL DEFAULT now()
);

CREATE INDEX invoice_by_customer ON acme.invoice (customer_external_id);
