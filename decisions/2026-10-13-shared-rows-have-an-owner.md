# Shared rows have an owner

> **Replaced** by [Shared rows may belong to nobody](2026-10-16-shared-rows-may-belong-to-nobody.md) (2026-10-16): a row every scope reads may have no owner.

> Adds to [A record may wait for its scope](2026-10-13-a-record-may-wait-for-its-scope.md) and [An action can be open](2026-10-13-an-action-can-be-open.md).

**Decision:** a row that every scope reads belongs to a real scope, the one whose people keep it (the platform's own scope), never to no scope. Others read it through an open action in that scope. An endpoint can name its scope as "the one in the request, and always also this fixed scope", so one read returns a shop's own rows and the shared ones; both scopes are verified, and the watch holds both.

An empty scope column keeps its one meaning everywhere: the row is nobody's yet.

**Why:** a table often mixes rows of two sorts: each shop's own categories and the platform's categories that every shop shows. If "no owner" meant "everyone's" there, an empty column would mean nobody's in one table and public in another, and a bug that empties it would publish the row. And nobody is a member of "no owner", so the people who keep the shared rows could only edit them as the system, past the wall. With an owner, they edit as members with roles, and readers come in through a declared open action.

**The price:** the scope that owns shared rows has to exist as a scope, and its id is named in the app's code. Queries ask for "this scope's rows and that scope's rows" instead of "this scope's rows and the ownerless ones".

**Instead of:** a record kind declaring that an empty scope means everyone's.
