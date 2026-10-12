# A build can have several modules

**Decision:** an app's browser build may be split into modules, named in the `:browser` settings (module → what it starts or holds, and the modules it depends on). A page names the module it starts (`:page {:module :admin}` on its route), and the page shell loads it after the modules it depends on, by their fingerprinted names after a release. A page that names none loads the module(s) that depend on no other. Modules no page names can load on demand (`:loader? true`). An app may add its own shadow-cljs build options (`:options`), merged over Groundwork's.

**Why:** an app with several areas (a public site, a back office, a 3D editor) shouldn't make every visitor download all of it. The split is still the build's, so the page and the build still can't disagree.

**The price:** `:options` is an opening into shadow-cljs itself: what an app puts there Groundwork doesn't check.

**Instead of:** one module for every app, or each app with several modules keeping its own build config beside Groundwork.
