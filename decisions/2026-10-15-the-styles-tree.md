# The styles tree

> **Replaced** by [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md).

**Decision:** stylesheets live in their own tree, apart from the code and organised by design, and its order is the load order:

```
styles/
  base/     tokens, fonts, resets, plain element styles, themes
  vendor/   outside libraries' stylesheets and their wrappers
  looks/    shared looks, used by anything
  areas/    one area's own looks, which may build on looks/
```

- **Base** is listed in the settings, in order, and loads first. It has no classes, and it is the only place that may style globally (the page, every link, variables for everything).
- **Every other stylesheet** holds only its own namespace's classes. Where it sits in the tree doesn't change the code: the code knows only the class's name, so making an area's look a shared one is moving a file.
- **Every variable that is used must be defined:** a token in base, or a namespace's own variable.
- **A specific look loads after the general ones** it builds on, so it wins where both say the same.

**Why:** styles follow the design's shape, not the code's: a look is often shared by screens that share no code, and unifying two looks shouldn't mean moving code. With ownership in the name and checked, the folder no longer has to say who uses what. A variable that doesn't exist is dropped silently by the browser; checked, it stops the build.

**Instead of:** stylesheets next to the components that use them (shared looks have no honest home, and styles are forced into the code's structure); global rules and variables anywhere.
