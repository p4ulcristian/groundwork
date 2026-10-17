# A class is a namespaced keyword

> **Replaced** by [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md).

**Decision:** in a component a CSS class is a namespaced keyword with the marker `css.`, never typed text: `:css.job-card/title`. It converts to the class name by the same rule as a database column: `:css.job-card/title` ↔ `.job-card__title`. The stylesheets stay plain CSS, with everything CSS can do.

Groundwork checks both ways and stops the build with every problem at once: a class keyword in the code that no stylesheet defines, and a class in a stylesheet that no code names.

- **A state that comes from data** (a job's stage) goes through a lookup table written out in the code, from the value to its class keyword; a value with no entry is an error in development, not an unstyled element.
- **A component accepts one extra class** from whoever uses it and puts it on its outer element, so its user can place it.
- **A handle for tests is `data-testid`,** never a class; a stylesheet may not select by it.

**Why:** one name for each thing, from the column to the browser, now covers how things look. Because every class is a marked keyword written out in the code, what exists and what is used is known exactly: dead styles, typos and leftovers can't stay. The marker keeps classes apart from data, endpoints and events, which are namespaced keywords too, wherever the keyword travels (a lookup table, a prop). Data names have no dots, so the two can't collide.

**The price:** an existing app's class names are renamed into this form. A class name can't be put together at run time.

**Instead of:** class names as text (nothing knows which exist); a declared list per component with parts, options and values (more to keep in step than it gave); class names built from data at run time (the check can't see them); a reserved namespace without a marker (an event named like a stylesheet becomes "a class with no style").
