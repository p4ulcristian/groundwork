# Groundwork ships no logins

> **Changed in part** by doorman's [doorman ships no logins](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-doorman-ships-no-logins.md) (2026-10-22): the session and signing in are doorman's, and that decision moved there whole. What stays here is what Groundwork ships.

**Decision:** Groundwork ships no login. It ships doorman, which has the session and `sign-in!`; how a person proves who they are is the app's own login, which ends by calling `sign-in!`. Groundwork depends on no identity provider.

**Why:** an identity provider is a third-party service and a subscription; a framework that ships one ties every app to it.

**Instead of:** shipping common logins in Groundwork and letting each app switch them on.
