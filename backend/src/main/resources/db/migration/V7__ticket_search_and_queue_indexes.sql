-- V7: full-text search and queue indexes.

-- Full-text search document, maintained by PostgreSQL itself on every insert/update (a generated
-- column can't drift out of sync). Title matches outrank description matches (weights A > B).
-- Not mapped in JPA: the application only reads it in search queries.
ALTER TABLE work_items
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'B')
    ) STORED;

-- GIN ("generalized inverted index"): word -> list of rows, which is what @@ queries need.
CREATE INDEX work_items_search_idx ON work_items USING gin (search_vector);

-- The unassigned queue ("tickets waiting for someone to pick them up"). A PARTIAL index holds only
-- the rows that can appear in that queue, so it stays small however many tickets get closed. The
-- predicate must match the query's WHERE clause exactly for the planner to use it.
CREATE INDEX work_items_unassigned_queue_idx ON work_items (assigned_team_id, created_at)
    WHERE assignee_id IS NULL AND status NOT IN ('CLOSED', 'CANCELLED', 'REJECTED');

-- Filtering by category/subcategory, and matching category names in search (BitmapOr).
CREATE INDEX work_items_category_idx ON work_items (category_id);
CREATE INDEX work_items_subcategory_idx ON work_items (subcategory_id);
