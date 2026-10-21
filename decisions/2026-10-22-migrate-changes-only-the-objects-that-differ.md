# Migrate changes only the objects that differ

> Adds to [Production migrates before it starts](2026-10-18-production-migrates-before-it-starts.md).

**Decision:** what Groundwork installs from an app's settings (row-level security on each record table, the wall's policies, the vias' functions and guards, the functions that narrow a list, the fill rules, the bells' triggers, the app role's grants) is brought to what the settings make object by object, not put up again whole.

- Each install reads what the database holds, one query per part: every object of Groundwork's with a fingerprint of how Postgres prints it back (`pg_get_expr` for a policy, `pg_get_triggerdef` for a trigger, `pg_get_functiondef` for a function, the two row-level security flags for a table).
- `groundwork.installed_objects` records, for each object an install made, the fingerprint of the statements that made it and of the object as Postgres printed it right after.
- An object is left alone when the settings make it with the recorded statements and the database prints it as recorded. Otherwise it is created when missing and replaced when there: changed by the settings, by a new Groundwork, or by hand. An object of Groundwork's that the settings no longer make is dropped: a policy or trigger named `groundwork…`, a generated function in the schema `groundwork`, and row-level security an install switched on.
- Grants work the same way: one grant per privilege the app's role lacks, one revoke per write it holds on Groundwork's own records.
- What differs is sent together, as one JDBC batch, in one transaction with the records. Nothing differs: no statement is sent, nothing is locked, nothing is written.
- `groundwork.installs` still keeps one fingerprint of all of a part's statements, and production's start still compares it.

The catalog reads the checks make (columns, indexes, foreign keys) are one query each for every table, not one per table.

**Why:** an app with 85 record tables has 340 policies, nearly 400 triggers and over 500 functions. Put up again one statement at a time, a migrate with nothing new took 13 minutes from a runner 221 ms from the database, and each table stayed locked until the end. Most deploys change nothing there, or one table.

The record is needed because Postgres prints a policy back differently from the statement that made it, and judging the two equal by rewriting text would be a guess. Comparing what is printed now with what was printed right after the install is exact, and it also sees a hand edit, a dropped object, a switched off trigger or a table made again by a migration.

**The price:** one more table of Groundwork's, which the app's role may only read. The first migrate after this replaces every object once, as nothing is recorded yet. A Postgres upgrade that prints objects differently replaces them once. A table that stops being a record kind loses its row-level security too, as a table Groundwork never walled.

**Instead of:**
- putting everything up again at every migrate, one statement at a time (the 13 minutes);
- skipping a part whole when its fingerprint and its objects are unchanged: one changed rule would put every table's wall up again, locking all of them;
- comparing the statements with Postgres's catalog text directly: Postgres rewrites expressions, so equal objects would look different, or different ones equal.
