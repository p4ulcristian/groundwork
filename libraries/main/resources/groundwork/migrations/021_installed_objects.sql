-- Each object Groundwork installed from an app's settings, by part (the
-- wall, the bells): a policy, a trigger, a function, a table's row-level
-- security. With the fingerprint of the statements that made it, and of
-- the object as Postgres printed it back right after. The next install
-- compares both with what it would make and what the database holds, and
-- changes only the objects that differ
-- (decisions/2026-10-22-migrate-changes-only-the-objects-that-differ.md).
CREATE TABLE groundwork.installed_objects (
  installed_object__part      text NOT NULL,
  installed_object__kind      text NOT NULL,
  installed_object__table     text NOT NULL,
  installed_object__name      text NOT NULL,
  installed_object__statement text NOT NULL,
  installed_object__printed   text NOT NULL,
  PRIMARY KEY (installed_object__part, installed_object__kind, installed_object__table, installed_object__name)
);
