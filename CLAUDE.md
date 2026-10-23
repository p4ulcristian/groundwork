# house

house puts the manor libraries together (they sit beside it in ~/Work/apps/manor, each its own public repo, GitHub topic `manor`). Work on branch `Starting-from-the-bottom`.

- Releasing a library: `tools/bump <library>…` tags it and carries the new tag through every library that uses it, house last. Don't cascade by hand. No tests are run for bumps.
- No CI on any manor repo.
- Every value that sets behaviour is a required setting, checked by malli; no defaults.
- decisions/ is the record: one file per decision (Decision / Why / Instead of), written when decided.
