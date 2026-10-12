# Groundwork ships no logins

**Decision:** Groundwork has the session and "sign in as this person"; how a person proves who they are is the app's own login. An identity provider (a third-party subscription), a gate's header, an email link or a password: the app's code does the proving and ends by calling sign-in-as. Groundwork depends on no identity provider.

**Why:** an identity provider is a third-party service and a subscription; a framework that ships one ties every app to it. With the session in Groundwork, plugging any login in is one call at its end.

**Instead of:** shipping common logins in Groundwork and letting each app switch them on.
