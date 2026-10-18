# Shared rows may belong to nobody

> Replaces [Shared rows have an owner](2026-10-13-shared-rows-have-an-owner.md), following [Who may see what is the app's](2026-10-15-who-may-see-what-is-the-apps.md). Decided while Groundwork was frozen; built as the `[:none <column>]` word of [the access language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md).

**Decision:** a row every scope reads (a catalogue the platform keeps for everyone) doesn't have to be given to a scope. It can stay universal: no owner. Who may read such rows and who may change them is the app's to say, like every other access rule.

**Why:** giving universal rows to the platform's own scope made them that scope's private rows, so every other read had to borrow them back: a shop's job list lost the names of the platform's parts it shows. The rows were never anyone's; they are data for everyone, kept by a few.

**Instead of:** giving them to the platform's scope and reading them through an open action in it.
