-- V5: replace the composite "assignee in team" foreign key with assignment-time checks.
--
-- Why: V4 enforced (assigned_team_id, assignee_id) -> team_members with a foreign key. A foreign
-- key holds for the row's whole life, but CLOSED tickets keep their historical assignee, so anyone
-- who had ever owned a ticket could never be removed from that team. History must not block
-- administration. The rule we actually want is:
--   1. when a ticket is assigned, the assignee must be a member of the assigned team;
--   2. a member can't leave a team while they still own OPEN tickets of that team.

ALTER TABLE work_items DROP CONSTRAINT work_items_assignee_in_team_fk;

-- Rule 1: checked whenever the assignment is written (insert, or update of team/assignee).
CREATE FUNCTION work_items_check_assignee_membership() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.assignee_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM team_members m WHERE m.team_id = NEW.assigned_team_id AND m.user_id = NEW.assignee_id
    ) THEN
        RAISE EXCEPTION 'assignee % is not a member of team %', NEW.assignee_id, NEW.assigned_team_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER work_items_assignee_membership
    BEFORE INSERT OR UPDATE OF assignee_id, assigned_team_id ON work_items
    FOR EACH ROW EXECUTE FUNCTION work_items_check_assignee_membership();

-- Rule 2: removing a membership is refused while the member owns open tickets in that team.
-- "Open" = not in a terminal state; RESOLVED/FULFILLED can still be reopened, so they count.
CREATE FUNCTION team_members_check_no_open_assignments() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (
        SELECT 1 FROM work_items w
        WHERE w.assigned_team_id = OLD.team_id
          AND w.assignee_id = OLD.user_id
          AND w.status NOT IN ('CLOSED', 'CANCELLED', 'REJECTED')
    ) THEN
        RAISE EXCEPTION 'user % still owns open tickets in team %', OLD.user_id, OLD.team_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER team_members_no_open_assignments
    BEFORE DELETE ON team_members
    FOR EACH ROW EXECUTE FUNCTION team_members_check_no_open_assignments();

-- Serves the check above and "my assigned tickets in this team".
CREATE INDEX work_items_team_assignee_idx ON work_items (assigned_team_id, assignee_id);
