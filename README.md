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
- **API composition** — fan out to several services and merge one response
- Verifying the caller's identity and forwarding it inward
- Hiding the internal service topology from clients

It holds **no domain logic**. It calls, merges, and reshapes. "May this user open
this project?" is `content-server`'s answer to give, never the gateway's. The
failure mode this rule prevents is the gateway slowly absorbing business rules
until every feature change has to pass through it.

## Stack, and why

| | Choice |
| --- | --- |
| Language | Java 21 (LTS) |
| Framework | Spring Boot 4.1 |
| Web stack | **Spring MVC**, not WebFlux |
| Concurrency | **Virtual threads** (`spring.threads.virtual.enabled=true`) |
| HTTP client | `RestClient` |
| Build | Gradle wrapper |

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

`/graph` is the first composition endpoint and exists to prove the call chain
end to end:

```json
{
  "service": "gateway-api",
  "thread": "VirtualThread[#49,tomcat-handler-5]/runnable@ForkJoinPool-1-worker-1",
  "upstream": { "graph-rag": { "service": "graph-rag-api" } }
}
```

The `thread` field is there to make the virtual thread visible while the platform
is being built out; drop it once that stops being interesting.

When an upstream fails the gateway answers `502` with a body naming the upstream,
rather than leaking a stack trace:

```json
{ "error": "upstream_unavailable", "upstream": "graph-rag" }
```

## Configuration

| Property | Default | Notes |
| --- | --- | --- |
| `server.port` | `8000` | The platform convention; Spring's own default is 8080. |
| `loresentry.upstream.graph-rag.base-url` | `http://graph-rag-api` | In-cluster Service DNS. |
| `spring.http.clients.connect-timeout` | `2s` | Applies to every outbound client. |
| `spring.http.clients.read-timeout` | `10s` | Raise this before proxying AI streaming responses. |

Any of them can be overridden by environment variable, e.g.
`LORESENTRY_UPSTREAM_GRAPHRAG_BASEURL`.

Timeouts are currently global. Once upstreams have genuinely different latency
profiles — a graph query against Neptune is not an auth lookup — give each client
its own `HttpClientSettings` instead.

## Run locally

```bash
./gradlew bootRun
curl localhost:8000/health
```

`/graph` needs `graph-rag` reachable. Either point it at a local instance:

```bash
./gradlew bootRun --args='--loresentry.upstream.graph-rag.base-url=http://127.0.0.1:8001'
```

or at the cluster:

```bash
kubectl port-forward -n prod svc/graph-rag-api 8001:80
```

## Test

```bash
./gradlew build
```

Covers context startup, that virtual threads are actually enabled, and the
upstream client's success and failure paths against `MockRestServiceServer`. No
AWS or network access required.

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

- JWT verification and identity forwarding. `/graph` is unauthenticated today.
- Streaming passthrough for AI chat responses. Needs the read timeout raised and
  the ALB `idle_timeout.timeout_seconds` above its 60s default.
- Retries and circuit breaking on upstream calls.
