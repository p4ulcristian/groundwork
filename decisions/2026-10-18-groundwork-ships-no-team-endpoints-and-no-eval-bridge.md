# Groundwork ships no team endpoints and no eval bridge

**Decision:** the team endpoints (a scope's members and roles as ready-made endpoints) and the eval bridge (evaluating code in a running preview) leave Groundwork. Memberships, roles and invitations stay: they are what the access language checks.

**Why:** no app uses either. The large app keeps its own team screens and endpoints, and both apps switch the bridge off. Code nothing uses goes untested and drifts.

**The price:** an app that wants them writes its own, or brings them back from history.

**Instead of:** keeping them ready for an app that might want them.
