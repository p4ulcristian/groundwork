# groundwork

groundwork puts the groundwork libraries together (they sit beside it in ~/Work/apps/groundwork, each its own public repo, GitHub topic `groundwork`; this composer is ~/Work/apps/groundwork/groundwork). Work on `main`.

- Releasing a library: `tools/bump <library>…` tags it and carries the new tag through every library that uses it, groundwork last. Don't cascade by hand. No tests are run for bumps.
- No CI on any groundwork repo.
- Every value that sets behaviour is a required setting, checked by malli; no defaults.
- decisions/ is the record: one file per decision (Decision / Why / Instead of), written when decided.
