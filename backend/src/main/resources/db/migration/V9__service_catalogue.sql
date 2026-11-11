-- V9: service catalogue (Phase 2, M18): things requesters can order, each with its own short form.
--
-- An item routes through the existing categories (and so to their teams) instead of naming a team
-- itself: one routing table for every ticket, whether it was raised freely or from the catalogue.

CREATE TABLE catalogue_items (
    id             uuid         PRIMARY KEY,
    name           varchar(100) NOT NULL,
    summary        varchar(200) NOT NULL,
    description    text,
    category_id    uuid         NOT NULL REFERENCES categories (id),
    subcategory_id uuid         REFERENCES categories (id),
    -- The form as an ordered list of field definitions, validated by the application. JSON rather
    -- than a fields table: a form is always read and written whole, and never queried by field.
    form_fields    jsonb        NOT NULL DEFAULT '[]',
    active         boolean      NOT NULL DEFAULT true,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL DEFAULT 0,

    CONSTRAINT catalogue_items_form_fields_check CHECK (jsonb_typeof(form_fields) = 'array')
);

-- "Laptop" and "laptop" are the same item to a requester.
CREATE UNIQUE INDEX catalogue_items_name_uk ON catalogue_items (lower(name));

-- The column has been waiting since V4. Items are deactivated, never deleted, so the foreign key
-- never blocks anything and needs no index for deletes.
ALTER TABLE service_requests
    ADD CONSTRAINT service_requests_catalogue_item_fk FOREIGN KEY (catalogue_item_id) REFERENCES catalogue_items (id);

-- What the requester answered, with each question's label as it read at the time. Editing the item
-- later must not change what an old request says was asked.
ALTER TABLE service_requests
    ADD COLUMN catalogue_answers jsonb,
    ADD CONSTRAINT service_requests_catalogue_answers_check CHECK (
        (catalogue_item_id IS NULL AND catalogue_answers IS NULL)
        OR (catalogue_item_id IS NOT NULL AND jsonb_typeof(catalogue_answers) = 'array'));
