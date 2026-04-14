# Motivations

## The problem with James WebAdmin

Apache James exposes a powerful WebAdmin REST API for administrative operations: managing domains, users, mailboxes, quotas, task scheduling, and more. However, it was designed for internal use only:

- **Authentication is a static token** — a single shared secret, configured at startup. There is no per-user identity.
- **Some endpoints are unauthenticated** — technical endpoints (e.g. `/healthcheck`) are public by design.
- **No access control** — every holder of the token can perform any operation. There is no way to say "client A may only manage users in domain X".
- **Cannot be exposed to the internet** — the combination of the above makes it unsuitable for direct internet exposure.

## The target use case

Modern deployments of Twake Mail involve multiple tenants and multiple administrative roles:

- A **super-admin** manages the full James instance.
- A **domain admin** manages users and aliases within their own domain only.
- A **provisioning service** creates users programmatically but must not touch quotas or task management.

Each of these actors authenticates through the same OIDC provider (e.g. LemonLDAP) but carries a different `client_id` in their token, and potentially different claims (e.g. `domain`, `admin`).

## What this proxy provides

WebAdmin Proxy intercepts every request before it reaches James, and enforces:

1. **Token validation** — the Bearer token is resolved against the OIDC provider (introspect + userinfo). Expired or inactive tokens are rejected with 401.
2. **Audience check** — the token's `aud` claim must match the configured `oidc.audience`. Tokens issued for a different service are rejected.
3. **Client routing** — the `client_id` from the introspect response selects which James backend and token to use.
4. **Claim validation** — optional extra claims (e.g. `"admin": "1"`) can be required per client.
5. **URL allowlist** — each client has an optional list of allowed endpoint patterns and HTTP verbs. Requests outside the allowlist are rejected with 403.
6. **Variable-to-claim binding** — URL template variables (e.g. `{domain}`) can be constrained to match an OIDC claim value, preventing cross-domain access.
7. **Authorized users** — an optional static allowlist of usernames per client, for environments where OIDC claim configuration is impractical.

The result is a single proxy instance that can safely front one or more James backends and serve multiple administrative clients with distinct permissions.
