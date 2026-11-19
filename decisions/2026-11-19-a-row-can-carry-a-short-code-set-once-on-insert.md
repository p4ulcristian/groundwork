# A row can carry a short code, set once on insert

> The user's decision, 2026-11-19, from Iron Rainbow: a batch label's QR holds `/flex/ws/<uuid>/batches/<uuid>?batch=<uuid>` (about 158 characters, QR version 9), and order, inventory and workstation labels and the customizer's share link want short addresses too. Built as groundwork-short-codes v0.1.0.

**Decision:** a table that wants short addresses declares a short-code column in its table map and the trigger that fills it. Postgres fills it in the same insert, it is unique, and it is never changed or reused. Its first letter names the kind (the app's own fixed list); the rest is random, from an alphabet without look-alikes (no 0/O, 1/I/L). The code is the row's second name, written on the row itself.

```clojure
;; an app's table map
:columns  [… [:batch/short-code :string {:unique? true}] …]
:triggers [(short-codes/make-trigger {:letter "B" :column-name "batch__short_code" :random-length 7})]
;; a label prints  https://…/c/BK7Q2MX4  as a QR, and BK7Q2MX4 under it
```

**A new library, groundwork-short-codes,** holds everything about short codes: the two Postgres functions (`groundwork_short_codes_make_code`, and `groundwork_short_codes_fill_code`, the BEFORE INSERT trigger), the trigger a table declares, reading a typed or scanned code, and the retry after a clash. It uses groundwork-postgres through its api. groundwork-postgres itself is unchanged: it already makes the triggers a library declares.

**The app owns the route and what each kind means.** `/c/<code>` needs the app's own system actor and its SQL files, so the app writes it: it reads the code with the library, finds the row by the kind's letter, and redirects to the row's own page. That page checks sign-in and access exactly as it does for the long address today, so a signed-out scan goes to the login and back. Content to save (the customizer's picks) stays in the app's own table; that table declares a short-code column like any other.

Rules that keep it robust:

- **The route is `/c/<code>`**, not the site root: `c` for code, a fixed segment so a code never collides with a page (`/login`, a future `/PRICING`). It never changes once labels are printed. It takes `/C/` too.
- **An unknown code answers 404**, and the route is rate limited by address.
- **A typed code is uppercased and its spaces and dashes dropped** before the lookup (`read-code`).
- **A code is 8 characters: the kind letter and 7 random** from 31, printed as two groups of four (`BK7Q-2MX4`). 27.5 billion codes per kind.
- **Two random codes can meet:** 200,000 rows in one table meet about once (the library's Postgres test met one). The unique index refuses the second, and every insert of rows with codes runs in `run-again-on-code-clash!`: a savepoint, and once more with new codes.
- **A copy of a row leaves the code out**, so the trigger makes a new one; a copy that carries it is refused by the unique index.
- **The QR holds the URL in capitals** (36 characters with the app's domain), so it is read in QR's alphanumeric mode: version 2, 25×25, against today's batch label at version 9, 53×53.
- **Kind letters only grow:** a letter is never reused for another table.
- **Old long addresses keep working:** labels printed before keep their URLs, and the scanner keeps reading them.
- **A table with rows takes the column in two deploys:** first `{:optional? true}` with the trigger and a change file that fills the old rows, then required.

**Why:** the shortest address a person can type, one solution for every kind and every app. On the row, the code goes wherever its row goes (deleted, copied, restored together), so nothing has to be kept in step, and it costs no write besides the insert that already happens.

**The price:** a migration per table that wants codes (existing rows get codes once), one more library in tools/bump's cascade, and the clash retry around each insert path.

**Instead of:**
- **a separate code table (code → kind, row):** the code lives apart from what it names; Postgres can't check a reference into one of several tables, so a deleted row leaves a code pointing at nothing, and every code is an extra write
- **the row's uuid written short (base36), no Groundwork change:** no new state at all, but 25 characters, too long to type
- **a column default that calls a SQL function, in groundwork-postgres:** groundwork-postgres compares defaults by making them on a probe table, where the declared functions have temporary names; a trigger needs no change there
- **the route in the library or the composer:** it needs the app's system actor and SQL files; the composer only puts libraries together
- **a check for a taken code inside the trigger:** row-level security hides other shops' rows from it, so it can't see every code; the unique index can
