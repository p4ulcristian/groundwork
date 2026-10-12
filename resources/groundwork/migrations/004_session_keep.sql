-- A visitor session that holds something worth keeping lives longer: its own hours.
ALTER TABLE groundwork.sessions ADD COLUMN session__keep_hours integer;
