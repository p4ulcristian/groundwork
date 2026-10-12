# The library does not need sops

**Decision:** Groundwork's code takes plain values (host, password…) and never reads secrets itself. sops comes later as setup tooling around it.

**Why:** where values come from is the app's choice. The library only needs the values.
