# Java 25 or newer

**Decision:** Groundwork supports Java 25 and newer, and its tests run on 25.

**Why:** Groundwork is a library for my own apps, which all run on 25 and get updated with it. 25 is the current long-term-support release, and the virtual threads the README recommends wait without holding their carrier thread only from 24 on. One version to test.

**Instead of:** Java 21. It reaches more apps, but means testing two versions and warning about pinning.
