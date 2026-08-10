# qits-platform-mirror

The platform's pull-through caches, and nothing else.

One deployable Quarkus application over two library repositories — `qits-blobstore`
(the content-addressed store) and `qits-registries` (the npm, maven and OCI wire
protocols). This repository holds what a library cannot: configuration, a schema,
and a seeder.

## What it owns

Three repository types, all of them caches:

| Type | Row | Upstream | Served at |
|---|---|---|---|
| `NPM_PROXY` | `npmjs` | `registry.npmjs.org` | `/artifacts/npm/npmjs/…` |
| `MAVEN_PROXY` | `central` | `repo1.maven.org/maven2` | `/artifacts/maven/central/…` |
| `OCI_MIRROR` | `hub`, `quay`, `redhat` | one `oci_mirror_upstream` row each | `/v2/<slug>/…` |

Hosted publishing is not its job. The `qits-registries` modules carry both sides of
each format because the two share a table and a set of routes, so the hosted
profiles arrive on the classpath whether this service wants them or not —
`quarkus.arc.exclude-types` removes them from bean discovery. The result is that
the registered types and the keys `ck_artifact_repository_type` allows are the
same three, and a request to create anything else is a 400 that names what does
exist.

## Schema

`V1__mirror_baseline.sql` is a fresh baseline on this service's own PostgreSQL
database, not a replay of `qits-artifacts`' thirteen migrations. It creates every
table the library entities map — the blob core plus the three formats — with
`qits-artifacts`' column names and constraints verbatim, and prefills the three
OCI mirror namespaces with the upstream each fronts. The two npm/maven cache roots
come from `MirrorRepositorySeeder` at boot instead: a migration prefills static
platform knowledge, and a seeded repository name is not that.

## Build

    ./mvnw -B -o clean verify -Dquarkus.http.test-port=0

The suite runs against a real PostgreSQL — zonky binaries spawned as a child
process, never a container — so the baseline is exercised against the database it
ships on. All three upstreams are in-process stubs; the suite has no network, and
its default for every upstream key is a closed port so a test that has not opted
into a stub cannot reach the internet by accident.

Needs `eu.wohlben.qits:qits-blobstore` and `qits-registries-{common,npm,maven,oci}`
installed locally (`./mvnw install` in each sibling repository).

## Deployment

The database arrives through the platform's generic resource contract —
`QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD`. None of them has a default:
an unset variable kills the process at Flyway's `migrate-at-start` naming the
missing variable, rather than opening some fallback store nobody meant.

Readiness is at `/mirror/q/health/ready`.

**The protocol routes do not follow that segment, and cannot.** Their prefixes are
literals in the `qits-registries` jars, so this service answers npm and maven on
the same `/artifacts/*` paths `qits-artifacts` does, and OCI at the host root like
every registry. Two services cannot both be `/artifacts/*` behind one gateway
entry — splitting the client configuration (npm scoped registries, dockerd
`registry-mirrors`, the maven repositories list) is the cutover phase's job.

## Not here yet

Eviction GC, the admin JSON API and the explorer UI. Phase 2 follow-ups; see
`byte-plane-split-plan.md` in the superproject.
