-- Permissions: places (scopes), their roles, who belongs where, and the
-- functions every check and the database wall run on.

CREATE TABLE groundwork.scopes (
  scope__id        uuid PRIMARY KEY,
  scope__kind      text NOT NULL,
  scope__parent_id uuid REFERENCES groundwork.scopes ON DELETE RESTRICT
);
CREATE INDEX scopes_by_parent ON groundwork.scopes (scope__parent_id);

CREATE TABLE groundwork.roles (
  role__id       uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
  role__scope_id uuid    NOT NULL REFERENCES groundwork.scopes ON DELETE CASCADE,
  role__name     text    NOT NULL,
  role__owner    boolean NOT NULL DEFAULT false,
  UNIQUE (role__scope_id, role__name),
  UNIQUE (role__id, role__scope_id)
);
CREATE UNIQUE INDEX one_owner_role_per_scope ON groundwork.roles (role__scope_id) WHERE role__owner;

-- The ticks in a scope's roles grid. The owner role needs none: it holds everything.
CREATE TABLE groundwork.role_actions (
  role_action__role_id uuid NOT NULL REFERENCES groundwork.roles ON DELETE CASCADE,
  role_action__action  text NOT NULL,
  PRIMARY KEY (role_action__role_id, role_action__action)
);

-- One role per person per scope, and the role is that scope's own.
CREATE TABLE groundwork.memberships (
  membership__person_id uuid NOT NULL,
  membership__scope_id  uuid NOT NULL REFERENCES groundwork.scopes ON DELETE CASCADE,
  membership__role_id   uuid NOT NULL,
  PRIMARY KEY (membership__person_id, membership__scope_id),
  FOREIGN KEY (membership__role_id, membership__scope_id)
    REFERENCES groundwork.roles (role__id, role__scope_id)
);
CREATE INDEX memberships_by_scope ON groundwork.memberships (membership__scope_id);

-- One action on top of a person's role.
CREATE TABLE groundwork.extra_actions (
  extra_action__person_id uuid NOT NULL,
  extra_action__scope_id  uuid NOT NULL REFERENCES groundwork.scopes ON DELETE CASCADE,
  extra_action__action    text NOT NULL,
  PRIMARY KEY (extra_action__person_id, extra_action__scope_id, extra_action__action)
);

-- Invited, not joined yet. The link carries a token; this table keeps its SHA-256.
CREATE TABLE groundwork.invitations (
  invitation__id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  invitation__email      text        NOT NULL,
  invitation__scope_id   uuid        NOT NULL,
  invitation__role_id    uuid        NOT NULL,
  invitation__token_hash bytea       NOT NULL UNIQUE,
  invitation__invited_by uuid        NOT NULL,
  invitation__expires_at timestamptz NOT NULL,
  FOREIGN KEY (invitation__role_id, invitation__scope_id)
    REFERENCES groundwork.roles (role__id, role__scope_id) ON DELETE CASCADE
);

-- Every scope where a person holds an action: through their role (an owner
-- holds everything), through an extra action, and every scope below those.
CREATE FUNCTION groundwork.scopes_holding(person uuid, action text)
RETURNS SETOF uuid LANGUAGE sql STABLE AS $$
  WITH RECURSIVE held(scope) AS (
    SELECT m.membership__scope_id
    FROM groundwork.memberships m
    JOIN groundwork.roles r ON r.role__id = m.membership__role_id
    WHERE m.membership__person_id = person
      AND (r.role__owner OR EXISTS (SELECT 1 FROM groundwork.role_actions a
                                    WHERE a.role_action__role_id = r.role__id
                                      AND a.role_action__action = action))
    UNION
    SELECT e.extra_action__scope_id
    FROM groundwork.extra_actions e
    WHERE e.extra_action__person_id = person AND e.extra_action__action = action
    UNION
    SELECT s.scope__id
    FROM groundwork.scopes s
    JOIN held h ON s.scope__parent_id = h.scope
  )
  SELECT scope FROM held
$$;

-- The database wall: a row is visible when its scope was verified for this
-- transaction, or the transaction runs as the system. Both settings end with
-- the transaction.
CREATE FUNCTION groundwork.wall_allows(scope uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('groundwork.bypass', true), '') = 'on'
      OR scope = ANY (coalesce(nullif(current_setting('groundwork.scopes', true), ''), '{}')::uuid[])
$$;

-- The person a transaction runs for, for records that belong to a person.
CREATE FUNCTION groundwork.wall_person()
RETURNS uuid LANGUAGE sql STABLE AS $$
  SELECT nullif(current_setting('groundwork.person', true), '')::uuid
$$;
