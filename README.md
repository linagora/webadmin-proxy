# WebAdmin Proxy

A security proxy for [Apache James](https://james.apache.org/) WebAdmin API, adding OIDC authentication and fine-grained per-client access control.

## Why?

Apache James WebAdmin uses static bearer tokens and exposes some unauthenticated technical endpoints. It cannot be opened to the internet as-is and has no concept of users or per-operation access control.

This proxy sits in front of James WebAdmin and provides:

- **OIDC authentication** — validates Bearer tokens against a configured OIDC provider
- **Per-client routing** — maps OIDC client IDs to distinct James backends and tokens
- **URL-level access control** — restricts which HTTP methods and endpoints a client may call
- **Claim-based restrictions** — scopes URL variables to OIDC claim values (e.g. restrict a domain admin to their own domain)
- **Backchannel logout** — invalidates cached tokens when the OIDC provider terminates a session
- **Observability** — Prometheus metrics and health-check endpoints on a dedicated admin port

## Documentation

- [Motivations](docs/00-motivations.md)
- [Architecture](docs/01-architecture.md)
- [Configuration reference](docs/02-configuration.md)
- [Build](docs/03-build.md)
- [Run with Docker](docs/04-run-with-docker.md)

## Quick start

```bash
# Build
/opt/apache-maven-3.9.6/bin/mvn package -DskipTests

# Run (edit src/main/conf/configuration.json first)
java -jar target/webadmin-proxy-1.0.0-SNAPSHOT.jar
```

See [docs/04-run-with-docker.md](docs/04-run-with-docker.md) for Docker-based deployment.

## Contributing

Contributions are welcome. Please open a GitHub issue before writing code to align on scope and approach.

LINAGORA owns the codebase and reserves the right to accept or reject contributions, but author attribution is preserved in git history.

## License

[GNU Affero General Public License v3.0](LICENSE)

## Credits

Developed with love at [LINAGORA](https://linagora.com).
