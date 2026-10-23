# A Groundwork app uses the libraries through Groundwork

> **Replaced** by [Groundwork is the default composition](2026-10-22-groundwork-is-the-default-composition.md) (2026-10-22): apps start from groundwork's composition as data and may extend it and use the libraries.

> Adds to [Declared, through one door](2026-10-14-declared-through-one-door.md) and [The ground-* libraries live in one repo](2026-10-22-the-ground-libraries-live-in-one-repo.md).

**Decision:** a Groundwork app talks only to `groundwork`; it doesn't require a `ground-*` library or replace one. A project that isn't a Groundwork app may use any `ground-*` library on its own. So each library is built to stand alone: it knows nothing of Groundwork or of libraries it doesn't need, and what it needs from another part comes in through a hook that Groundwork fills.

**Why:** one door stays one door: every Groundwork app runs the same tested combination, and a change is made in one place. Standing alone keeps each library's edges clean and lets a small project take one piece (a script that only needs `ground-sql`).

**The price:** a Groundwork app can't swap or skip a library. A library used alone has to be documented and tested alone too.

**Instead of:**
- the libraries as Groundwork's insides only: the simplest promise, but nothing small can take one piece
- apps picking libraries: tools carry only what they need, but one door breaks and apps drift into combinations nobody tested
