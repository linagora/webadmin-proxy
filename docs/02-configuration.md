# Configuration reference

Configuration is loaded from a single JSON file (`configuration.json`). Any value may reference an environment variable using the `{ENV:VAR_NAME}` syntax — the proxy will fail to start if a referenced variable is absent.

## Full example

```json
{
  "port": "8001",
  "oidc.userInfo.url": "http://lemonldap:19090/oauth2/userinfo",
  "oidc.introspect.url": "http://lemonldap:19090/oauth2/introspect",
  "oidc.introspect.credentials": "Bearer {ENV:OIDC_INTROSPECT_CREDENTIALS}",
  "oidc.audience": ["webadmin-proxy", "webadmin-proxy-alt"],
  "oidc.claim.authenticated.user": "email",
  "oidc.token.cache.expiration": "60s",
  "cors.allow.origin": ["https://twake-mail-admin.linagora.com", "https://twake-calendar-admin.linagora.com"],
  "self.webadmin.enabled": "true",
  "self.webadmin.port": "8002",
  "clients": [
    {
      "twakemail-client": {
        "webadmin.backend": "http://james:8000",
        "webadmin.token": "{ENV:TWAKEMAIL_WEBADMIN_TOKEN}",
        "expected.claims": {
          "admin": "1"
        },
        "expected.scopes": ["webadmin", "admin"],
        "authorized.users": ["alice@example.com", "bob@example.com"],
        "allowed.urls": [
          {"denied": true, "endpoint": "/domains/{domain}/quota"},
          {"verb": ["GET"], "endpoint": "/domains/{domain}/users"},
          {"endpoint": "/domains/{domain}/aliases/*"}
        ],
        "url.patterns.restrictions": {
          "domain": {
            "backing.claim": "domain",
            "operator": "EQUALS"
          }
        }
      }
    }
  ]
}
```

## Top-level fields

| Field | Required | Description |
|-------|----------|-------------|
| `port` | yes | Port the proxy listens on |
| `oidc.userInfo.url` | yes | OIDC userinfo endpoint URL |
| `oidc.introspect.url` | yes | OIDC token introspection endpoint URL |
| `oidc.introspect.credentials` | no | Credentials sent with introspect requests (e.g. `Bearer <token>`) |
| `oidc.audience` | yes | Expected audience(s). Accepts a single string or a JSON array (e.g. `["aud-a", "aud-b"]`). A token is accepted if its `aud` claim contains at least one of the configured values. Tokens matching none of the configured audiences are rejected with 401 |
| `oidc.claim.authenticated.user` | yes | Name of the userinfo claim used as the authenticated user identity (typically `email`) |
| `oidc.token.cache.expiration` | yes | How long resolved tokens are cached. Format: `<n>s`, `<n>m`, etc. |
| `cors.allow.origin` | no | Allowed CORS origin(s). Accepts a single string or a JSON array. Use `"*"` to allow all origins, or list specific origins (e.g. `["https://app.example.com", "https://admin.example.com"]`). Absent = no CORS headers added. Preflight responses advertise `Content-Type, Authorization, Accept, I-KNOW-WHAT-I-M-DOING` as allowed request headers |
| `self.webadmin.enabled` | no | `true` to start the self-admin HTTP server. Defaults to `false` |
| `self.webadmin.port` | no | Port for the self-admin server. Required when `self.webadmin.enabled` is `true`. Use `0` for a random port |
| `clients` | yes | Ordered array of client configurations. Each element is a single-key object whose key is the OIDC `client_id`. Duplicate keys are allowed — the proxy picks the **first matching entry** for the authenticated user |

## Client configuration

Each element in the `clients` array is a single-key object. The key is the OIDC `client_id` that clients present in their tokens. When the same `client_id` appears more than once, the proxy evaluates the entries in order and selects the first one where both `authorized.users` and `expected.claims` match the incoming token.

| Field | Required | Description |
|-------|----------|-------------|
| `webadmin.backend` | yes | Base URL of the James WebAdmin backend to proxy to |
| `webadmin.token` | yes | Bearer token used to authenticate requests to the backend |
| `expected.claims` | no | Map of claim name → required value. All listed claims must be present in userinfo with exactly the specified value |
| `expected.scopes` | no | List of OAuth 2.0 scopes that must all be present in the token's `scope` field (from the introspection response, RFC 7662). If empty or absent, no scope restriction is applied. Extra scopes in the token are ignored |
| `authorized.users` | no | Allowlist of user identities (as resolved by `oidc.claim.authenticated.user`). If non-empty, only listed users are admitted. Useful when OIDC claim configuration is impractical |
| `allowed.urls` | no | Ordered list of URL rules (allow and deny). If omitted or empty, all URLs are allowed. Rules are evaluated in order; the first matching rule wins |
| `url.patterns.restrictions` | no | Constraints on URL template variables, validated against OIDC claims |

### allowed.urls rules

Rules are evaluated **in order** — the first rule whose `endpoint` pattern and `verb` list match the incoming request determines the outcome. A matching `denied: true` rule returns 403 immediately; a matching rule without it (or with `denied: false`) allows the request through.

This makes it easy to carve out exceptions from a broad wildcard without enumerating every permitted path:

```json
"allowed.urls": [
  {"denied": true, "endpoint": "/domains/{domain}/quota"},
  {"denied": true, "verb": ["DELETE"], "endpoint": "/domains/{domain}/aliases"},
  {"endpoint": "/domains/{domain}/*"}
]
```

Each rule is either a regular endpoint rule or an include directive:

**Endpoint rule:**

| Field | Required | Description |
|-------|----------|-------------|
| `endpoint` | yes | Endpoint pattern (see pattern syntax below) |
| `verb` | no | List of HTTP verbs this rule applies to (e.g. `["GET", "PUT"]`). If omitted, the rule matches all verbs |
| `denied` | no | `true` to explicitly deny matching requests with 403. Defaults to `false` |

The field is `verb`, singular. `verbs` is accepted as a deprecated alias and behaves identically; a
warning is logged. Setting both in the same rule is a startup error.

`verb` must be a non-empty array. An absent `verb` means every verb; `"verb": []` or `"verb": "GET"`
is a **startup error**: an empty list reads as "no verb", and a rule whose last verb was removed
would otherwise silently grant every verb.

**Any other field name is a startup error.** This is deliberate: an unread field imposes no
constraint, so a typo does not narrow a rule, it *widens* it. A rule spelled `"method": ["DELETE"]`
would apply to every verb while reading as if it applied only to `DELETE` — the proxy refuses to
start rather than grant more than the profile appears to grant. The same check applies to `include`
directives, which accept no other field.

**Include directive:**

| Field | Required | Description |
|-------|----------|-------------|
| `include` | yes | URI of a JSON file containing an array of endpoint rules to inject in place of this entry |

Include URIs use the following schemes:

| Scheme | Resolution |
|--------|------------|
| `classpath://path` | Resource on the JVM classpath (packaged in the JAR) |
| `file://relative/path` | Path relative to the current working directory |
| `file:///absolute/path` | Absolute filesystem path |

Six ready-made profiles ship on the classpath — see [Shipped profiles](#shipped-profiles).

### Endpoint pattern syntax

A pattern is a path, optionally followed by `?` and a query pattern. Both halves are matched
independently; the path half is matched against the URL-decoded request path.

| Token | Meaning |
|-------|---------|
| `{varname}` | Captures exactly one path segment (no `/`). The captured value is available to `url.patterns.restrictions` |
| `%` | Matches the local part of an email address (no `@`, no `/`). Pure consumer — no named capture |
| `*` | Matches any remaining characters, including `/`, and including none |
| `?param={varname}` | Requires the parameter, captures its value into a named variable |
| `?param=value` | Requires the parameter to equal a literal value |
| `?param=*` | Requires the parameter, accepts any value including an empty one |
| `?param=` | Requires the parameter to be present and valueless — matches `?param` and `?param=` |
| `?param` | Requires the parameter, accepts any value including none — same as `?param=*` |
| `?{params}` | Stands for "any other parameters". Imposes no constraint and captures nothing: `/tasks?{query_params}` behaves as `/tasks` |

Examples:
- `/users` — exact path
- `/domains/{domain}/users` — one variable segment
- `/users/%@{domain}/mailboxes` — email address user, domain captured
- `/domains/{domain}/aliases/*` — any sub-path under aliases
- `/quota?scope={scope}` — captures a query parameter

`*` matches the empty string but not a missing separator: `/tasks/*` matches `/tasks/` and
`/tasks/abc` but **not** `/tasks`. Profiles that mean "the collection and everything under it" list
both, as `{"endpoint": "/tasks"}, {"endpoint": "/tasks/*"}`.

The same variable name may appear several times, in both halves (`/domains/{domain}/users?domain={domain}`)
or twice in the path (`/domains/{domain}/team-mailboxes/{mailbox}/members/%@{domain}`); the rule then
matches only if every occurrence captures the same value. Variable names are free-form
(`{query_params}`, `{task-id}`).

Mind the direction of that constraint on a **deny** rule: repeating `{domain}` narrows what the rule
matches, so it also narrows what it denies. A deny on `…/members/%@{domain}` does not stop adding
`eve@other.com` to a team mailbox of your domain, and a later `/domains/{domain}/*` allow lets it
through. Deny rules should use a free variable (`…/members/{username}`).

#### Query string matching

The query half is matched by **presence and value, per listed parameter**:

- Every parameter listed in the pattern must be present in the request. A pattern parameter the
  request does not carry means no match.
- Parameters the request carries but the pattern does not list are **ignored**. `?domain={domain}`
  matches `?domain=a.com&limit=10`.
- Order is irrelevant: `?a=1&b=2` matches `?b=2&a=1`.
- Request parameter values are URL-decoded before comparison.
- A pattern with **no** query half imposes no query constraint at all — `/mappings/sources/%@{domain}`
  matches `GET /mappings/sources/bob@d.com?type=alias`. This is the usual way to allow an endpoint
  regardless of its filters.

**Flag-style parameters.** James has endpoints selected by a valueless parameter — `?hasSpecificQuota`,
`?reload-certificate`. A request parameter with no `=` is read as having an empty value. In a
pattern, `?hasSpecificQuota` requires the parameter to be present whatever its value, like
`?hasSpecificQuota=*`; write `?hasSpecificQuota=` to also require it to be valueless. Prior versions
dropped such parameters silently, then rejected them at startup.

Against a request carrying `?flag`, `?flag=`, `?flag=true`, or no `flag` at all:

| Pattern | `?flag` | `?flag=` | `?flag=true` | no `flag` |
|---------|:-------:|:--------:|:------------:|:---------:|
| `?flag=` | match | match | — | — |
| `?flag=*` | match | match | match | — |
| `?flag=true` | — | — | match | — |
| *(no query half)* | match | match | match | match |
| `?flag` | match | match | match | — |

### url.patterns.restrictions

Constraints on captured URL variables. Each entry is keyed by the variable name as it appears in the endpoint pattern.

| Field | Required | Description |
|-------|----------|-------------|
| `backing.claim` | yes | Name of the OIDC claim whose value is used for comparison. If the claim is absent from the token, the request is rejected with 403 |
| `operator` | yes | How to compare the claim value against the URL variable. See operators below |

Operators:

| Operator | Description |
|----------|-------------|
| `EQUALS` | The claim value must exactly equal the URL variable value |
| `HAS_DOMAIN` | The claim value is parsed as an email address; its domain part must equal the URL variable value. Useful with the `email` claim |

A restriction is checked only when the rule that matched actually captured that variable. A rule that
does not name it — `/*`, `/users`, `/mailingLists/*` — passes the restriction unexamined, so
restrictions narrow rules, they do not constrain a profile as a whole. See
[Baselines are tenant-scoped only if you scope them](#baselines-are-tenant-scoped-only-if-you-scope-them).

## Shipped profiles

Six profiles ship on the classpath. They fall into two families, and the distinction matters:

| Profile | Family | Intended holder |
|---------|--------|-----------------|
| `classpath://functional-admin-mail-baseline.json` | baseline | Twake Mail tenant (domain) admin |
| `classpath://functional-admin-calendar-baseline.json` | baseline | Twake Calendar tenant (domain) admin |
| `classpath://linagora-mail-admin-profile.json` | complete | Linagora platform operator, Twake Mail |
| `classpath://linagora-calendar-admin-profile.json` | complete | Linagora platform operator, Twake Calendar |
| `classpath://linagora-mail-support-profile.json` | complete | Linagora support agent, Twake Mail |
| `classpath://linagora-calendar-support-profile.json` | complete | Linagora support agent, Twake Calendar |

**Baselines** are data-plane fragments. Every rule is anchored on a `{domain}` variable, they contain
no deny rules and no platform-wide endpoints, and they are meant to be included after your own deny
rules — not used alone. See [Baselines are tenant-scoped only if you scope them](#baselines-are-tenant-scoped-only-if-you-scope-them)
and [Baselines alone leave the frontend sidebar empty](#baselines-alone-leave-the-frontend-sidebar-empty)
before shipping one.

**Complete profiles** are Linagora's own production ACLs. They are self-contained (the support ones
include the matching baseline as their last entry) and usable as-is, but they encode Linagora's
policy — read the "withholds" column before adopting one unchanged, and prefer copying the file into
your own configuration if you need to diverge, since classpath profiles change with the proxy version.

### functional-admin-mail-baseline.json

**Grants**, all scoped to one `{domain}`: the domain itself and everything below it
(`/domains/{domain}`, `/domains/{domain}/*` — aliases, team mailboxes, rate limits, contacts,
signature templates…); users of that domain and all their sub-resources (`/users/%@{domain}`,
`/users/%@{domain}/*` — mailboxes, identities, labels, delegation, JMAP settings…); user and domain
quotas; address aliases and forwards; the user's mapping sources; vacation; deleted-message vault;
team-mailbox membership; message search by user (`/messages?user=%@{domain}`); per-domain quota
listing (`/quota/users?domain={domain}`); IMAP/network channels; and per-domain tasks
(`/tasks/{domain}`, `/tasks/{domain}/*`).

**Withholds**: every unscoped endpoint. No `/domains`, `/users`, `/healthcheck`, `/metrics`,
`/mailRepositories`, `/mappings`, `/mailingLists`, `/events`, `/cassandra`, `/servers`, no bare
`/tasks`, no global `/quota`. It also does not grant `/mappings/user/{username}` — only
`/mappings/sources/…` — so the frontend's user Mappings tab, which gates on the former, stays hidden.

**Use as**: a starting point, combined with deny rules, entry-point reads, and a `domain` restriction.

### functional-admin-calendar-baseline.json

**Grants**, all scoped to one `{domain}`: the domain and everything below it; users of the domain and
their sub-resources (calendars, address books, booking links); registered users
(`/domains/{domain}/registeredUsers`); resources, both as `/resources?domain={domain}` and
`/resources/{domain}(/*)`; domain-member address book sync (`/addressbook/domain-members/{domain}`);
calendars of the domain's users (`/calendars/%@{domain}(/*)`); per-domain tasks; and mailing lists —
read (`GET /mailingLists/%@lists.{domain}` and `%@{domain}`) plus member and owner management
(`PUT`/`DELETE` on `/members/*` and `/owners/*`) for lists of the admin's own tenant.

**Withholds**: creating or deleting a mailing list (no `PUT`/`DELETE` on `/mailingLists/{address}`
itself); unfiltered `GET /mailingLists`, which would return every tenant's lists — listing is allowed
only through the `?domain=` filter; and, as with the mail baseline, every unscoped endpoint.

**Use as**: a starting point, same caveats as the mail baseline.

Example — deny rules first, then the baseline:

```json
"allowed.urls": [
  {"denied": true, "verb": ["POST"], "endpoint": "/domains/{domain}?action=deleteData"},
  {"include": "classpath://functional-admin-calendar-baseline.json"}
]
```

### linagora-mail-admin-profile.json

Twake Mail platform operator. Enumerates the endpoints it allows rather than using a catch-all.

**Grants**: platform-wide reads and operations — `/healthcheck`, `/metrics`, `/mailRepositories`,
`/events`, `/tasks`, `/cassandra`, `/mappings`, `/servers/channels`, `/quota`, `/reports/*`,
`/jmap/settings`; `GET /users` and `GET /domains`; user administration (`GET`/`PUT`/`POST`/`PATCH` on
`/users/*`); domain administration including team mailboxes, extra ACLs and extra senders
(`GET`/`PUT`/`POST`/`PATCH` on `/domains/{domain}/*`); vault restore (`POST /deletedMessages/users`);
mailbox and message creation; Trash/Spam cleanup (`DELETE /messages?mailbox=Trash`, `…=Spam`).

**Withholds** — the leading deny rules, which win because rules are evaluated in order: destructive
data wipes (`POST /users/{user}?action=deleteData`, `POST /domains/{domain}?action=deleteData`), user
rename, and any write to domain contacts, domain aliases, or user delegation (`authorizedUsers`) —
those three are read-only, `GET` is granted separately. Note that `DELETE` is absent from every
`/users/*` and `/domains/{domain}/*` allow rule, so deletions fall through to no match and are
refused.

**Use as-is**: yes, for a Linagora-operated deployment.

### linagora-calendar-admin-profile.json

Twake Calendar platform operator. Deliberately the opposite shape: a `{"endpoint": "/*"}` catch-all
at the end, carved out by the rules above it.

**Grants**: everything on the backend, plus explicit `DELETE` on user address books and booking links.

**Withholds**: `POST /users/{user}?action=deleteData` and `POST /domains/{domain}?action=deleteData`,
and every other `DELETE` under `/users/%@{domain}/*` — the two explicit `DELETE` allows sit before
that deny, so address books and booking links remain deletable while calendars and the rest do not.

**Use as-is**: only if a full-access operator profile is what you want. The trailing `/*` grants
every current and future James endpoint, and because it captures no variables it is also invisible to
`url.patterns.restrictions` (see below). Anything you need to withhold must be denied above it.

### linagora-mail-support-profile.json

Twake Mail support agent: read the platform, act only within a tenant.

**Grants**: platform-wide *reads* — `GET` on `/domains`, `/users`, `/mailRepositories`,
`/events/deadLetter/groups`, `/quota`, `/mappings`, `/servers/channels`, `/cassandra/version`,
`/jmap/settings/reports`, `/mailboxes/{mailboxId}` — plus `/healthcheck`, `/metrics` and `/tasks`.
Everything a support agent may *change* comes from the trailing
`{"include": "classpath://functional-admin-mail-baseline.json"}`, i.e. is tenant-scoped.

**Withholds**: the mail admin profile's deny list, plus destructive operations a support agent must
never perform even inside a tenant — deleting a user's mailboxes, deleting team mailboxes or their
folders, changing domain rate limits, renaming users, and any write to domain quotas.

**Use as-is**: yes. Pair it with a `domain` restriction only if support agents are themselves
tenant-bound; a Linagora support agent normally is not, and the included baseline then grants the
tenant-scoped writes across all tenants.

### linagora-calendar-support-profile.json

Twake Calendar support agent. Same shape: platform reads (`/healthcheck`, `/metrics`, `/tasks`,
`/tasks/*`), cross-tenant mailing list read and member/owner management (`/mailingLists`,
`/mailingLists/*`, `PUT`/`DELETE` on `/mailingLists/*/members/*` and `/mailingLists/*/owners/*`), the
same address-book and booking-link `DELETE` carve-outs as the calendar admin profile, and then
`{"include": "classpath://functional-admin-calendar-baseline.json"}`.

**Withholds**: the two `action=deleteData` wipes, and every other `DELETE` under
`/users/%@{domain}/*`.

**Use as-is**: yes.

### LDAP-rest compatible mail profiles

When [ldap-rest](https://github.com/linagora/ldap-rest) and its
[Twake James plugin](https://github.com/linagora/ldap-rest/blob/master/src/plugins/twake/james.ts) are
the source of truth, the plugin pushes user quotas, user aliases, user renames and mailing lists to
James. Writes from the admin console would be overwritten by — or diverge from — the IAM.

`classpath://ldap-rest-managed-denials.json` denies exactly those writes: `PUT`/`DELETE` on
`/quota/users/{username}` and below, `PUT`/`DELETE` on `/address/aliases/{username}/sources/{alias}`,
`POST /users/{username}/rename/{newUsername}`, and `PUT`/`DELETE` on `/mailingLists/{address}` and
its `members` and `owners`. Reads stay granted; forwards and identities stay manageable.

Include it first, before any profile:

| Profile | Equivalent to |
|---------|---------------|
| `classpath://linagora-mail-functional-baseline-ldap-rest.json` | `linagora-mail-functional-baseline.json` + LDAP-rest denials |
| `classpath://linagora-mail-functional-admin-ldap-rest.json` | `linagora-mail-functional-admin.json` + LDAP-rest denials |

### Baselines are tenant-scoped only if you scope them

A baseline's `{domain}` is a *capture*, not a constraint. On its own, `/domains/{domain}/*` matches
`/domains/anyone-elses-domain/users`. What binds it to the caller is a `url.patterns.restrictions`
entry on the same variable name:

```json
"allowed.urls": [
  {"include": "classpath://functional-admin-mail-baseline.json"}
],
"url.patterns.restrictions": {
  "domain": {
    "backing.claim": "email",
    "operator": "HAS_DOMAIN"
  }
}
```

Without that block, the baseline is a full-platform grant. And note the corollary: a restriction is
only checked when the rule that matched actually captured the variable
(`WebAdminProxy.validatePatternRestrictions` skips unbound names). A rule like `/*`, `/users` or
`/mailingLists/*` captures no `domain` and therefore escapes the `domain` restriction entirely — this
is why the complete profiles list their unscoped endpoints explicitly rather than relying on
restrictions to hold them back.

### Baselines alone leave the frontend sidebar empty

The baselines are a **data plane**: they grant the endpoints an admin's actions hit, not the
endpoints the frontend probes to decide what to display. `twake-mail-admin` builds its left bar by
asking its copy of the rules whether a fixed list of entry-point reads is allowed, and hides every
entry whose read is not. In global mode those probes are `GET` on `/healthcheck`, `/domains`,
`/users`, `/registeredUsers`, `/tasks`, `/tasks/{id}`, `/mailboxes/{mailboxId}`, `/mailRepositories`,
`/events/deadLetter/groups`, `/quota`, `/mappings`, `/mailingLists`, `/servers/channels`,
`/cassandra/version`, `/metrics`, `/jmap/settings/reports` — and a baseline grants none of them,
because every baseline rule is anchored on `{domain}`. An administrator holding only
`functional-admin-mail-baseline.json` gets a working API and a completely empty sidebar.

The baselines are built for the frontend's **domain mode** (`mode: DOMAIN`), whose probes are
tenant-scoped and mostly satisfied: `/domains/{domain}/users`, `/domains/{domain}/aliases`,
`/domains/{domain}/team-mailboxes`, `/domains/{domain}/ratelimits` and `/quota/domains/{domain}` all
match. Even there, two entries stay hidden — `Mailing lists` probes bare `GET /mailingLists`, and
`Tasks` probes bare `GET /tasks`, while the baseline only has `/tasks/{domain}`.

So: use a baseline as-is for a domain-mode deployment, and add the entry points you want visible.
For a global-mode deployment, add them explicitly:

```json
"allowed.urls": [
  {"denied": true, "verb": ["POST"], "endpoint": "/domains/{domain}?action=deleteData"},

  {"verb": ["GET"], "endpoint": "/healthcheck"},
  {"verb": ["GET"], "endpoint": "/domains"},
  {"verb": ["GET"], "endpoint": "/users"},
  {"verb": ["GET"], "endpoint": "/tasks"},
  {"verb": ["GET"], "endpoint": "/mailingLists"},

  {"include": "classpath://functional-admin-mail-baseline.json"}
]
```

Mind what those reads actually expose: `GET /domains` and `GET /users` are unscoped and capture no
`domain`, so a `domain` restriction does not narrow them — the tenant admin sees the full domain and
user lists, and only their *contents* stay tenant-scoped through the baseline. That is the trade the
complete support profiles make deliberately. If it is not acceptable, keep the deployment in domain
mode rather than granting global entry points.

Bare `GET /mailingLists` deserves particular care: the calendar baseline allows listing only through
`?domain=`, precisely because an unfiltered list returns every tenant's lists. Adding
`{"verb": ["GET"], "endpoint": "/mailingLists"}` to make the sidebar entry appear also allows the
unfiltered call. There is no way, today, to show that entry without granting it — see below.

## Self-admin API

When `self.webadmin.enabled: true`, the following endpoints are available on `self.webadmin.port`:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/backchannel-logout` | `POST` | Receives an OIDC backchannel logout notification (`application/x-www-form-urlencoded` with `logout_token`). Extracts the `sid` claim and invalidates all matching cache entries |
| `/metrics` | `GET` | Prometheus text-format metrics |
| `/healthcheck` | `GET` | Returns 200 when the server has fully started |
| `/healthcheck/checks` | `GET` | Lists individual health checks |

## Proxy self-service endpoints

Available on the main proxy port, authenticated via OIDC. All endpoints require a valid Bearer token and apply the same `authorized.users` check as regular requests. They are never forwarded to the James backend.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/.proxy/allowed/urls` | `GET` | Returns the `allowed.urls` list for the caller's client as JSON. Returns 204 if not configured. Intended for frontends that adapt their UI based on permission level |
| `/.proxy/whoami` | `GET` | Returns the authenticated user identity: `{"email":"user@example.com"}` |
| `/.proxy/myDomain` | `GET` | Returns the caller's domain: `{"domain":"example.com"}`. Resolved from the `domain` claim in userinfo if present, otherwise extracted from the domain part of the email claim |
