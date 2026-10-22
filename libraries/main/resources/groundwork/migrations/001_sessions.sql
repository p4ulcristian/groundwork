-- Who is signed in. The cookie holds random bytes; this table holds only their SHA-256.
CREATE TABLE groundwork.sessions (
  session__id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  session__key_hash   bytea       NOT NULL UNIQUE,
  session__person_id  uuid        NOT NULL,
  session__data       jsonb       NOT NULL DEFAULT '{}',
  session__created_at timestamptz NOT NULL DEFAULT now(),
  session__expires_at timestamptz NOT NULL
);

CREATE INDEX sessions_by_person ON groundwork.sessions (session__person_id);
