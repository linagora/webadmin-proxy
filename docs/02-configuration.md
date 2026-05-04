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
| `cors.allow.origin` | no | Allowed CORS origin(s). Accepts a single string or a JSON array. Use `"*"` to allow all origins, or list specific origins (e.g. `["https://app.example.com", "https://admin.example.com"]`). Absent = no CORS headers added |
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
  {"denied": true, "verbs": ["DELETE"], "endpoint": "/domains/{domain}/aliases"},
  {"endpoint": "/domains/{domain}/*"}
]
```

Each rule has:

| Field | Required | Description |
|-------|----------|-------------|
| `endpoint` | yes | Endpoint pattern (see pattern syntax below) |
| `verb` | no | List of HTTP verbs this rule applies to (e.g. `["GET", "PUT"]`). If omitted, the rule matches all verbs |
| `denied` | no | `true` to explicitly deny matching requests with 403. Defaults to `false` |

### Endpoint pattern syntax

| Token | Meaning |
|-------|---------|
| `{varname}` | Captures exactly one path segment (no `/`). The captured value is available to `url.patterns.restrictions` |
| `%` | Matches the local part of an email address (no `@`, no `/`). Pure consumer — no named capture |
| `*` | Matches any remaining characters, including `/` |
| `?param={varname}` | Captures a query parameter value into a named variable |
| `?param=value` | Requires query parameter to equal a literal value |

Extra query parameters in the request are ignored. All listed query parameters must be present.

Examples:
- `/users` — exact path
- `/domains/{domain}/users` — one variable segment
- `/users/%@{domain}/mailboxes` — email address user, domain captured
- `/domains/{domain}/aliases/*` — any sub-path under aliases
- `/quota?scope={scope}` — captures a query parameter

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
