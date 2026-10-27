# The errors route's ?since= is read by ring.util.codec

> The user's decision, 2026-10-27 (keep groundwork minimal; replace query strings read by regex with ring.util.codec where ring is on the library's path). Since v7.0.2. Changes in part [Browser errors go to the composer's one list](2026-10-23-browser-errors-go-to-the-composers-one-list.md): how `GET /groundwork/errors?since=N` reads N.

**Decision:** the errors route reads its query as form text by ring-codec's `ring.util.codec/form-decode`, UTF-8, which ring-core brings through groundwork-bridge's reitit-ring and rj9a: since is the one `since` param, a whole number from 0 of at most 18 digits. Read so:

- `since=%34%31` is 41;
- `since` given twice is none, a 400;
- a pair with a bad %-escape is left out (`x=%zz&since=0` is 0; `since=0%zz` is none);
- `since=+0` is a space and a 0: none.

**Why:** the regex read the first `since=` of digits up to the next `&`, a %-escaped number as none and a second `since` as nothing at all. ring.util.codec is the form decoding the server's whole Ring stack already uses, on the composer's path already: nothing to add.

**The price:** a monitor that sends `since` twice gets 400 where it got the first; one that %-escapes the number now gets its answer.

**Instead of:**
- **the regex**: the first match, escapes unread
- **the JDK's URLDecoder by hand**: the same reading as ring.util.codec's, written again
