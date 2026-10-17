# A stylesheet paints only its own room

> **Replaced** by [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md).

**Decision:** the first half of a class's name is its namespace, and one namespace is one stylesheet named after it (`job-card.css` holds only `job-card__…` classes; a large one may be a folder of files). A rule in a stylesheet may only style an element that carries one of its own classes; around that element it may look at anything (an ancestor, a child, a sibling of any namespace) to decide.

- **Changing another namespace's look goes through what it offers:** a state of its own (`:css.card/compact`), or a variable it reads, named after it (`--card__padding`), which anyone may set.
- **A specialised look carries both classes** (the general one and its own), adds what is its own, and adjusts the general one through those.
- **A value from data** (a colour, a width) is set on the element as its namespace's variable and read in its stylesheet.
- **An outside library's stylesheet** is declared with the start of its class names and the one namespace that wraps it; only that wrapper may style the library's classes, inside its own outer element. Everyone else uses the wrapper.

**Why:** every element's look then comes from one place, and a change inside one look can't quietly break another far away. Rules that reach into another component's insides are what break when that component changes. A stylesheet still reacts to its context, which is CSS's strength; it just can't repaint what isn't its own.

**The price:** a look has to offer what others may adjust, on purpose.

**Instead of:** any stylesheet styling anything (nothing is owned); a stylesheet that may not even mention another namespace (it loses "a card that contains a warning badge").
