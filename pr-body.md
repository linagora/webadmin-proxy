Closes #15

## What

ldap-rest's [Twake James plugin](https://github.com/linagora/ldap-rest/blob/master/src/plugins/twake/james.ts) pushes user quotas, user aliases, user renames and mailing lists to James. This PR adds new versions of the mail profiles that deny those writes, so the admin console cannot diverge from the IAM.

- `ldap-rest-managed-denials.json`: a shared fragment of deny rules:
  - `PUT`/`DELETE` on `/quota/users/{username}` and `/quota/users/{username}/*` (size, count, both)
  - `PUT`/`DELETE` on `/address/aliases/{username}/sources/{alias}`
  - `POST /users/{username}/rename/{newUsername}` (any query, including `&force` as used by ldap-rest)
  - `PUT`/`DELETE` on `/mailingLists/{address}`, `.../members/{member}` and `.../owners/{owner}`
- `linagora-mail-functional-baseline-ldap-rest.json` and `linagora-mail-functional-admin-ldap-rest.json`: include the denials first, then the existing profile. This keeps them in sync with their non-LDAP counterparts.
- Matching `.questions` files, with the LDAP-managed answers set to `false` (`users.quota.save/clear`, `users.aliases.add/remove`, `users.rename`, `mailing-lists.create/delete/detail.*`).
- A docs section in `docs/02-configuration.md`.

Reads stay granted, so the frontend tabs still display: user quota, aliases, mailing lists. As the issue requests, forwards and identities stay manageable. Deny rules use free variables, following the guidance in the docs.

## Notes for review

- `linagora-mail-functional-support.json` already denies these writes, so it gets no LDAP variant. One gap: it denies only `/quota/users/%@{domain}/size`, so `PUT /quota/users/{user}` and `/count` are still allowed. This PR does not change that profile.
- The plugin also manages team-mailbox membership, delegation (`authorizedUsers`), the default identity and `deleteData`. The issue does not list them, so this PR leaves them untouched.
- The plugin writes mailing lists through `/address/groups`. No shipped mail profile grants that endpoint, so it needs no deny rule.

## Validation

- Single-module project (root). `mvn test-compile checkstyle:check` passed.
- New `@Nested` integration tests `LinagoraMailFunctionalBaselineLdapRestProfile` and `LinagoraMailFunctionalAdminLdapRestProfile` cover the denied writes, the allowed reads, forwards and identities, cross-domain rejection, and that the underlying profile's own grants and denials still apply. All `WebAdminProxyIntegrationTest$LinagoraMail*` tests pass: 60 tests.
- `WebAdminProxyConfigurationTest$PredefinedProfiles` parses every shipped JSON, including the new ones. 13 tests pass.

---
*Generated automatically*
