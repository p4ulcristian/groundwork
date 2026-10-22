# groundwork.server.main

The server's one start: puts the server libraries together. Today it only
lists them; it grows into reading the settings, migrating and starting the
server. Its tests read the whole repo: the layers (no circles; each side's
main uses every library of its side) and the contracts between the sides.

```sh
clojure -X:test     # from the repo's root
```
