# parquery is one address

**Decision:** parquery's two kinds of traffic share one address, `/parquery`. An HTTP POST carries asks and gets their answers back; a websocket to the same address is the line that stays open for bells. The kind of connection tells the server which one it is.

**Why:** asking and the bells are one conversation, about the same keywords, under the same session and permissions. One address means one route to mount, one place for its access rules, and one name in the logs.

**The price:** in logs and devtools the two are told apart by the method, not the path.

**Instead of:** `/parquery` for asks and a separate `/live` for the bells.
