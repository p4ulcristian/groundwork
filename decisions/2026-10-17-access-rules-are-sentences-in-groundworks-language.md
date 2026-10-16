# Access rules are sentences in Groundwork's language

> Answers how the app says it, left open by [Who may see what is the app's](2026-10-15-who-may-see-what-is-the-apps.md). Changes [Groundwork ships permissions](2026-10-12-groundwork-ships-permissions.md): the six shapes and the shapes added on 2026-10-13 become words of this language. Built under [The freeze lifts for the access language](2026-10-17-the-freeze-lifts-for-the-access-language.md).

**Decision:** an app writes who may do what as one list of sentences. Each sentence says: allow this action, on this kind of record, to whom, and when. Groundwork gives the words and reads every sentence at start; it refuses to start on an unknown word, table or column.

The app has the nouns: its kinds of scope, which column names whose row, its actions, its sentences. Groundwork has the words, the grants tables, and what it makes from the sentences: the wall, list filters, who is rung, the browser's "can I?", and the leak test.

The words:

- **To whom:** `:anyone`, `:signed-in`, `[:member <scope>]` (a member of a fixed scope, or of the scope a column of the row names, whose role holds the action), `[:me <column>]` (the person or visitor in that column).
- **When:** a condition on the row's own columns (`[:= :invoice__status "draft"]`), and `[:none <column>]` for a universal row that belongs to nobody.
- **`:via`:** a row is allowed when another row it points to is allowed for the person (a payout row may be written by whoever may pay its order; an order is seen by a partner through an active agreement). The lookup runs past the wall, inside Groundwork, so Postgres doesn't loop on the other table's own rule.
- **Deny:** only two things can say no, before any yes: a session flag (an impersonating admin can't move money) and a scope's state (a suspended shop is read-only). Never a person or a row.

A kind of scope may have exactly one scope (the platform's back office): its members hold roles there, and its sentences reach other scopes' rows and universal rows.

**Why:** shapes were fixed patterns: each new business case needed a new shape and a new Groundwork decision. Words combine, so a new case is a new sentence in the app; only a case no word covers changes Groundwork, and one new word serves many cases. Groundwork can read every sentence, which it must to build the wall, the bells, "can I?" and the leak test from the same rules. Most words already exist, scattered by shape (conditions, people, visitors, open actions, links, session blocks); one list puts every rule in one place and one form.

**The price:**
- Both running apps rewrite their permission description in the same change.
- The wall knows read, insert, update and delete, not actions: two update actions on one table become one update rule on the wall, and only the endpoint check tells them apart.
- `:via` adds a lookup to every row check that uses it; it is measured on the longest lists.
- The leak test proves the wall matches the sentences, not that the sentences are right: a sentence that is too open passes it.
- A deny can confuse ("why can't I?"), so the explain tool names the deny that said no.

**Instead of:**
- adding the missing words to the shapes as they are: smallest change, but the rules stay scattered, and the next case risks a new shape again
- conditions in a policy language (Cedar, Oso Polar): says anything, but hard to turn into list filters and bells
- the app writing Postgres row rules itself: Groundwork can't read them, so no bells, no "can I?", a blind leak test
