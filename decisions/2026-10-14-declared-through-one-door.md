# Declared, through one door

> **Changed in part** by [Groundwork is the default composition](2026-10-22-groundwork-is-the-default-composition.md) (2026-10-22): an app may add its own steps and use the ground-* libraries in them; the start checks the required steps.

**Decision:** this is how Groundwork takes in what apps need and stays able to change its mind:

- **An ability is declared, never automatic.** A route says its code loads on demand; an app lists its languages; a table says how it rings. An app that declares nothing notices nothing.
- **Apps say what they want; Groundwork owns how.** A declaration names an intent ("these are my languages"), not a mechanism (a prefix in the address).
- **One door.** What Groundwork does, an app does only through Groundwork: no hand-built addresses, no reading around it. A change is then one change.
- **What can't be a declaration is the app's,** attached through a hook, and it can only add, never replace.
- **A new ability needs a real app that needs it now,** a test, and a decision file.
- **Extra care for what is nearly permanent:** addresses people saved or search engines indexed, data already stored, and whatever outside parties call. Everything else is inside, and inside can change: Groundwork changes, a new version, the apps follow, and their tests are the judge.

**Why:** nobody sees the future, so changing an opinion has to be cheap. It is cheap when the opinion lives in one place behind declarations of intent, and when tests say whether anything broke; with code written by AI, the cost of a change is knowing it is right, not making it.

**Instead of:** flexibility through apps replacing parts (each app drifts into its own half-tested version), or leaving abilities out until they are certainly universal (the first real app needs them now).
