-- Names made consistent (decisions/2026-10-18-names-are-made-consistent-once.md):
-- a table's columns are named after its row. The one-time keys' table is
-- run_once_keys, its columns run_once_key__…; a schedule's columns are
-- job_schedule__…, after its table job_schedules.
ALTER TABLE groundwork.run_once RENAME TO run_once_keys;
ALTER TABLE groundwork.run_once_keys RENAME COLUMN run_once__key TO run_once_key__key;
ALTER TABLE groundwork.run_once_keys RENAME COLUMN run_once__person_id TO run_once_key__person_id;
ALTER TABLE groundwork.run_once_keys RENAME COLUMN run_once__endpoint TO run_once_key__endpoint;
ALTER TABLE groundwork.run_once_keys RENAME COLUMN run_once__answer TO run_once_key__answer;
ALTER TABLE groundwork.run_once_keys RENAME COLUMN run_once__created_at TO run_once_key__created_at;
ALTER INDEX groundwork.run_once_pkey RENAME TO run_once_keys_pkey;
ALTER INDEX groundwork.run_once_by_age RENAME TO run_once_keys_by_age;

ALTER TABLE groundwork.job_schedules RENAME COLUMN schedule__key TO job_schedule__key;
ALTER TABLE groundwork.job_schedules RENAME COLUMN schedule__next_at TO job_schedule__next_at;
