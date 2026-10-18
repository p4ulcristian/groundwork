-- The wall functions nothing calls any more: the per-row checks of 002, 007
-- and 017 (the wall reads the per-statement sets of 017 instead), and
-- wall_member, whose setting nothing sets. 017 kept them for the policies an
-- earlier start installed; every start since has replaced those. No CASCADE:
-- if anything in this database still uses one, the migration stops here.
DROP FUNCTION groundwork.wall_allows(uuid);
DROP FUNCTION groundwork.wall_member(uuid);
DROP FUNCTION groundwork.wall_writes(uuid);
DROP FUNCTION groundwork.wall_whole(uuid);
