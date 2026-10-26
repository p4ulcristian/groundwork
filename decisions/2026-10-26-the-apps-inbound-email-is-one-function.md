# The app map's :on-inbound-email is one function

> The user's decision, 2026-10-26 (groundwork v6.0.0), taken from groundwork-email v0.2.0's [Inbound mail goes to one function of the app](https://github.com/p4ulcristian/groundwork-email/blob/v0.2.0/decisions/2026-10-26-inbound-mail-goes-to-one-function-of-the-app.md). Changes in part [main makes one attic and one postbox](2026-10-23-main-makes-one-attic-and-one-postbox.md): the app map's `:on-inbound-email` was `{mailbox f}`.

**Decision:** groundwork pins groundwork-email v0.2.0. The app map's `:on-inbound-email` is one function, `(fn [email {:keys [transaction log]}])`, handed to groundwork-email's webhook routes as it is: every inbound email reaches it, in one transaction run as groundwork-access's system actor `:email`, as before, and it routes the email by its `:mailbox`. An app that takes no inbound email hands one that ignores it, `(fn [_email _context] nil)`; a map is refused at the start (`:groundwork.server.main/bad-app`). An email the app queues names `:unsubscribe-url` too (groundwork-email's `email-shape`, an `https://` address or nil). So groundwork is v6.0.0.

**Why:** the mailboxes are the app's rows (a shop's quote alias), which a map fixed at the start can't name; groundwork passes the app's routing through, it doesn't route.

**Instead of:**
- the map kept here and turned into one function by groundwork: groundwork would route by a map groundwork-email gave up, and an alias made while the app runs would still reach nobody
