# loresentry-gateway

The public API entry point for Lore Sentry. Everything from the internet reaches
the platform through this service; every other service is `ClusterIP` and has no
route from outside the cluster.

```
Cloudflare → ALB → gateway → authentication · content · ai-chat · graph-rag
```

## Responsibilities

- Single public API surface for clients
- Routing to internal services
- The CORS boundary for every browser client
- **API composition** — fan out to several services and merge one response
- Verifying the caller's identity and forwarding it inward
- Hiding the internal service topology from clients

It holds **no domain logic**. It calls, merges, and reshapes. "May this user open
this project?" is `content-server`'s answer to give, never the gateway's. The
failure mode this rule prevents is the gateway slowly absorbing business rules
until every feature change has to pass through it.

## Stack, and why

| | Version | Notes |
| --- | --- | --- |
| Java | **21** (LTS) | Virtual threads are stable here. Toolchain-pinned in `build.gradle`. |
| Spring Boot | **4.1.1** | |
| Spring Framework | 7.0.9 | Pulled in by Boot 4.1.1. |
| Web stack | `spring-boot-starter-webmvc` | **Servlet MVC, not WebFlux.** Boot 4 renamed the old `-web` starter, so the choice is visible in the dependency list. |
| Concurrency | Virtual threads | `spring.threads.virtual.enabled=true` |
| HTTP client | `RestClient` via `spring-boot-starter-restclient` | Boot 4 split RestClient support out of the web starter. |
| Build | Gradle 9.7.1 (wrapper) | No local Gradle install needed — use `./gradlew`. |
| Container base | `eclipse-temurin:21-jdk-alpine` → `21-jre-alpine` | Multi-stage; the runtime image carries only the JRE. |

### A note on "Spring Boot 3"

This service was specified as Spring Boot 3. Spring Initializr no longer offers a
3.x line — the available releases are 4.0.x and 4.1.x — so it is built on 4.1.1.
The part of the decision that mattered, **MVC plus virtual threads rather than
WebFlux**, is unchanged and is if anything more explicit on Boot 4.

Four API differences will trip up any Boot 3 example you copy from:

| Boot 3 | Boot 4.1 |
| --- | --- |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `ClientHttpRequestFactorySettings` | `HttpClientSettings` |
| `RestClient` in the web starter | separate `spring-boot-starter-restclient` |
| `org.springframework.boot.test.autoconfigure.web.client.RestClientTest` | `org.springframework.boot.restclient.test.autoconfigure.RestClientTest` |

### Why Spring, when two services are FastAPI

`authentication-server` and `content-server` are Spring Boot. Writing the gateway
in Spring keeps the project at two languages instead of three. A third language
means a third build pipeline, a third dependency ecosystem, and a third body of
on-call knowledge, which is a real cost for a small team.

It also matters that the gateway is the security boundary. Token handling can use
the same Spring Security configuration as the service that issues the tokens,
rather than a second hand-rolled implementation.

### Why MVC and not WebFlux

A composition gateway is almost entirely waiting on other services, which used to
be the textbook argument for reactive. Virtual threads removed it: blocking code
on a virtual thread does not hold an OS thread while it waits, so plain MVC
handles high fan-out concurrency without the reactive programming model.

What that buys:

- Ordinary sequential code and readable stack traces
- Plain debugger stepping, no operator chains to unwind
- One Spring model shared with the other Spring services, not two

Fan-out stays ordinary Java:

```java
var content = CompletableFuture.supplyAsync(() -> contentClient.get(id));
var graph   = CompletableFuture.supplyAsync(() -> graphClient.get(id));
CompletableFuture.allOf(content, graph).join();
```

### Why not Spring Cloud Gateway

Despite the name it is a reverse proxy — a filter chain for routing and rate
limiting. Calling three services and merging their responses is not what it is
built for, and composition logic pushed into filters is hard to test and debug.
It is also Netty-based and reactive, which reintroduces exactly what MVC plus
virtual threads was chosen to avoid. With a handful of internal services there is
no routing work left for a proxy layer to do either.

### Why not GraphQL federation

API composition is GraphQL's home problem and it may be worth revisiting later.
Adopting it now brings schema design, DataLoader and N+1 handling, a caching
story, and awkward streaming for AI responses all at once — too much for four
services and one client.

## Endpoints

| Method | Path | Behaviour |
| --- | --- | --- |
| `GET` | `/health` | `{"status":"ok"}`. Used by the Kubernetes probes and the ALB target group. |
| `GET` | `/` | `{"service":"gateway-api"}` |
| `GET` | `/graph` | Calls `graph-rag` and returns its payload nested under `upstream`. |
| `GET` | `/ai-chat` | Same, against `ai-chat`. |
| `GET` | `/auth` | Same, against `authentication`. |
| `GET` | `/content` | Same, against `content`. |
| `GET POST PATCH PUT DELETE` | `/projects`, `/projects/**` | Relayed to `content` verbatim. |
| `GET POST PATCH PUT DELETE` | `/files`, `/files/**` | Relayed to `content` verbatim. |
| `GET POST PATCH PUT DELETE` | `/episodes`, `/episodes/**` | Relayed to `content` verbatim. |

The four single-segment upstream endpoints are **relay probes**, not finished API
surface. They exist to prove each call chain end to end before the services have
any domain logic:

```json
{
  "service": "gateway-api",
  "thread": "VirtualThread[#49,tomcat-handler-5]/runnable@ForkJoinPool-1-worker-1",
  "upstream": { "graph-rag": { "service": "graph-rag-api" } }
}
```

The `thread` field is there to make the virtual thread visible while the platform
is being built out; drop it once that stops being interesting.

## Relaying to content

The content endpoints are relayed **verbatim**: the public `/projects` is the
`/projects` that `content` serves. Nothing is rewritten, so there is no mapping
table to keep in sync and the path in the API docs is the path the browser calls.

Declaration is **per namespace**, not per route. Content already owes the
frontend more than twenty endpoints and **not one of them needs two services'
responses combined**, so a method per route would mean copying every new content
endpoint into this repository, and silently answering `404` whenever that was
forgotten.

That is still not a catch-all `/**`. Each mapping names which service owns which
namespace, so `content`'s own `/health`, `/health/db` and `/` stay off the public
surface, and there is one place to put identity. When a path does need
composition, declaring that path with a more specific mapping wins over the
namespace — Spring picks the more specific pattern.

What the relay does with a request:

- passes the method, path, query string and body straight through
- **sets `X-User-Id` itself** from the resolved identity, and never forwards the
  client's headers wholesale, so the client cannot smuggle one in
- returns the upstream status and body **untouched**, including error bodies. The
  upstream knows why it failed; re-wrapping would erase that and force the client
  to unpack two layers
- keeps `Location` and `Cache-Control`, and drops hop-by-hop headers the servlet
  container recalculates

URI encoding is off for upstream calls (`UpstreamClient.restClient`). The path
and query arrive from the servlet already encoded, and encoding them again turns
`%EC` into `%25EC`.

## Identity — temporary and unauthenticated

```
X-User-Id: <authentication user id (canonical UUID)>
```

`IdentityResolver` decides who the caller is. The only implementation today is
`ClientHeaderIdentityResolver`, which **believes the client**. So this gateway
does not authenticate: anyone can put any UUID in that header and read or write
that user's projects. It is a deliberate step to get the frontend onto the real
API, and it is the reason JWT verification is the next piece of work here.

Swapping in verification means replacing that one class. The relay only reads the
resolved value, and it *sets* the upstream header rather than copying it, so a
verified identity will overwrite anything the client sent.

Rejections match what `authentication` and `content` do, so the three services
answer the same way:

| Header | Result |
| --- | --- |
| Absent | `401 USER_CONTEXT_REQUIRED` |
| Not a canonical UUID | `400 INVALID_REQUEST` |
| Present more than once | `400 INVALID_REQUEST` |

## Errors

The gateway's own failures use the same three fields as the services — `code`,
`message` and `next_action` — because all three answer the same frontend. An
unreachable upstream also names which one:

```json
{ "code": "UPSTREAM_UNAVAILABLE", "message": "Upstream service is unavailable.",
  "next_action": "RETRY_LATER", "upstream": "content" }
```

An upstream that *answers* with an error is not this: that body passes through
unchanged.

## Configuration

| Property | Default | Notes |
| --- | --- | --- |
| `server.port` | `8000` | The platform convention; Spring's own default is 8080. |
| `loresentry.upstream.graph-rag.base-url` | `http://graph-rag-api` | In-cluster Service DNS. |
| `loresentry.upstream.ai-chat.base-url` | `http://ai-chat-api` | |
| `loresentry.upstream.authentication.base-url` | `http://authentication-api` | |
| `loresentry.upstream.content.base-url` | `http://content-api` | |
| `spring.http.clients.connect-timeout` | `2s` | Applies to every outbound client. |
| `spring.http.clients.read-timeout` | `10s` | Raise this before proxying AI streaming responses. |
| `loresentry.cors.allowed-origin-patterns` | see below | Browser origins allowed to call the API. |
| `loresentry.cors.allow-credentials` | `true` | Needed for cookie-based sessions. |
| `loresentry.cors.max-age` | `1h` | How long a browser may cache a preflight response. |

Any of them can be overridden by environment variable, e.g.
`LORESENTRY_UPSTREAM_GRAPHRAG_BASEURL`.

Timeouts are currently global. Once upstreams have genuinely different latency
profiles — a graph query against Neptune is not an auth lookup — give each client
its own `HttpClientSettings` instead.

### CORS

The gateway is the only service a browser talks to, so it is the only place CORS
is configured. The internal services have none, and need none.

```yaml
loresentry:
  cors:
    allowed-origin-patterns:
      - https://loresentry.com
      - https://*.loresentry.com     # www, app, and any future subdomain
      - http://localhost:[*]         # local frontend on any port
      - http://127.0.0.1:[*]
```

`allowedOriginPatterns` is used rather than `allowedOrigins` because
`allowCredentials: true` forbids a literal `*` — the browser rejects a wildcard
origin on a credentialed request. Patterns keep the wildcard while still echoing
one concrete origin back per request, which is what the browser requires.

The patterns are matched as whole origins, not substrings, so lookalikes are
refused with `403`:

| Origin | |
| --- | --- |
| `https://loresentry.com` | allowed |
| `https://app.loresentry.com` | allowed |
| `http://localhost:3000` | allowed |
| `https://loresentry.com.evil.com` | **403** |
| `https://loresentry.evil.com` | **403** |
| `http://loresentry.com` | **403** — plain HTTP is not an allowed pattern |

Spring adds `Vary: Origin` automatically, which matters once a CDN or Cloudflare
caches API responses: without it a response allowing one origin could be served
to another.

Allowing `localhost` on **any** port is a deliberate development convenience —
it lets a local frontend call the deployed API. It is also the loosest part of
this policy, since it is paired with `allowCredentials: true`. Narrow it to the
real frontend origins when the service handles anything worth stealing.

## Run locally

```bash
./gradlew bootRun
curl localhost:8000/health
```

The upstream endpoints need their services reachable. Either point one at a local
instance:

```bash
./gradlew bootRun --args='--loresentry.upstream.graph-rag.base-url=http://127.0.0.1:8001'
```

or at the cluster:

```bash
kubectl port-forward -n prod svc/graph-rag-api      8001:80
kubectl port-forward -n prod svc/ai-chat-api        8002:80
kubectl port-forward -n prod svc/authentication-api 8003:80
kubectl port-forward -n prod svc/content-api        8004:80
```

## Test

```bash
./gradlew build
```

39 tests, no AWS or network access required:

| Test | Covers |
| --- | --- |
| `GatewayApplicationTests` | Context startup, and that virtual threads are actually enabled. |
| `client/UpstreamClientTest` | The shared `UpstreamClient` logic — `/` and `/health` calls, and failure wrapping — parameterized over all four clients against `MockRestServiceServer`. No Spring context. |
| `config/UpstreamWiringTest` | That each client is wired to **its own** upstream host. Sentinel base URLs are injected and outbound requests recorded, so swapping two `RestClient` beans in `RestClientConfig` fails the build. |
| `web/UpstreamRoutesTest` | That `/graph`, `/ai-chat`, `/auth` and `/content` reach the right client, and that an upstream failure becomes a `502`. |
| `web/CorsTest` | Preflight and actual requests for six allowed origins and four rejected lookalikes, plus the advertised methods, max-age and `Vary` header. |

`@RestClientTest` is **not** used for the client tests. Its auto-configured
`MockRestServiceServer` can only bind to one `RestClient` per context, and this
application has four — the slice fails with *"MockServerRestClientCustomizer has
been bound to more than one RestClient"*. Building the client by hand with
`MockServerRestClientCustomizer` avoids the context entirely and runs faster.

## Deploy

`main` push runs [`.github/workflows/ci-cd.yaml`](.github/workflows/ci-cd.yaml):

```
test → docker build → ECR gateway/api:build-<run>-<attempt>
     → invoke loresentry-update-gitops → commit to loresentry-gitops → Argo CD
```

CI never touches Kubernetes. The image tag in
`workload/overlays/prod/kustomization.yaml` is the deployment record, and a
rollback is `git revert` of that commit.

## Not implemented yet

- **JWT verification.** Identity is whatever the client claims (see above), so
  every relayed content endpoint is effectively public. This is the next task.
- Relaying `authentication`'s own `/auth/**` routes. Only the probe exists.
- Composition. Nothing the frontend asks for needs it yet; the `/health/db`
  aggregation is the shape to follow when something does.
- Streaming passthrough for AI chat responses. Needs the read timeout raised and
  the ALB `idle_timeout.timeout_seconds` above its 60s default.
- Retries and circuit breaking on upstream calls.
