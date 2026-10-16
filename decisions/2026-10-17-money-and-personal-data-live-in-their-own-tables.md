# Money and personal data live in their own tables

> Follows [Access rules are sentences in Groundwork's language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md) and [Every endpoint declares its answer](2026-10-12-every-endpoint-declares-its-answer.md).

**Decision:** the wall works on whole rows. When someone may see a row but not all of it (a partner sees an order, not the shop's price or the customer's phone), two things keep the rest away:

- **By default, the endpoint's answer shape:** an endpoint that doesn't declare a column can't send it.
- **Money and personal data go into their own table,** under the row, with their own sentences. Then the wall stops them, whatever an endpoint asks for.

**Why:** the answer shape already exists and costs nothing, but it depends on whoever writes the endpoint: one careless endpoint leaks a price. For money and personal data that isn't enough; a separate table puts them behind the wall.

**The price:** some tables are split, and a screen that shows both reads both.

**Instead of:**
- the answer shape only: no table changes, but nothing in Postgres stops a leak
- a column word in the language: Postgres grants columns per database role, not per person, so Groundwork would keep a view per audience
