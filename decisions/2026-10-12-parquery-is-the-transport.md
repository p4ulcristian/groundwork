# parquery is the transport

**Decision:** parquery is the transport between browser and server: it starts where something is handed to `/parquery` on one side and ends where it is handed over on the other. Its parts are named after the transport they use, each with a server half (`.clj`) and a browser half (`.cljs`) of the same name:

- `parquery/http`: asks and answers (a POST to `/parquery`)
- `parquery/websocket`: the bells (a websocket to `/parquery`)
- `parquery/route`: the one address that sends each kind to its part
- `parquery/wire`: the message format, shared by both sides

The endpoints (the registry and its checks) are not transport and live outside parquery; so does the browser's store of answers, which belongs to the re-frame part (its name is still open).

**Why:** a part named after its transport says exactly what it is. Asking and the bells are one conversation, so they share one name, parquery, and one address.

**Instead of:** naming the parts by what they do (ask and live, request and subscribe, pull and push), which reads less obviously; or separate names for the two directions.
