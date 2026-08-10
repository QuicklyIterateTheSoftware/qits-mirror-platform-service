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

## Eviction

What keeps a cache from growing forever. Every byte here came from upstream and can
be fetched again, so the only question worth asking of an entry is whether anything
still uses it: **an identity unaccessed for longer than its type's window is
evicted**, and its blobs are reclaimed once no surviving row of any type names them.
Creation counts as the first access, so nothing is eligible before the window has
passed since it was cached.

There is no route and no button. The policy is configuration and the only trigger is
the clock:

| Key | Ships as |
|---|---|
| `qits.mirror.eviction.enabled` | `true` |
| `qits.mirror.eviction.dry-run` | `false` — plan and report, delete nothing |
| `qits.mirror.eviction.cron` | `0 20 3 * * ?` |
| `qits.mirror.eviction.window.npm-proxy` | `P30D` |
| `qits.mirror.eviction.window.maven-proxy` | `P90D` |
| `qits.mirror.eviction.window.oci-mirror` | `P30D` |

maven's window is longer because a library is resolved when something builds
*against* it, and that cadence is not a month. A type with no window here does not
fall back to a number: it fails its own line in the run, because guessing a window is
guessing what may be deleted.

What each type counts as an identity, and how it goes:

- **npm** — a cached version (`<package>@<version>`) and a cached packument
  (`<package> (packument)`). A packument's age is `max(fetched_at, the newest access
  among that package's versions)`, so the document of a package something is still
  installing is never evicted out from under it. Eviction writes **no tombstone**:
  the version is upstream's, and re-fetching it is the point.
- **maven** — a cached file, by path, and a cached `maven-metadata.xml`
  (`<path> (metadata)`), whose age folds in the files under its directory. The unit
  is a file rather than a coordinate: a cache repairs itself on the next request, so
  there is no half-version to prevent.
- **OCI** — a cached tag, and a manifest no tag names. Both, because upstream drift
  leaves both behind: a tag moves, and the manifest it used to name becomes a row
  nobody can reach. A child of a still-tagged index may age out on its own; its bytes
  survive as long as the index needs them, because the index's closure still names
  them.

Two mechanisms sit under all three. **The grace window** (`qits.artifacts.gc.blob-grace-period`,
seven days) gates identity rows and not only blob unlinks: deleting a row while its
blob file is inside the window would leave the file row-less, and row-less is
untouchable by construction, so such an identity is withheld whole and re-planned
next run. **The blob sweep** is the one thing that frees disk, and it carries no
policy: a blob dies only when no type reaches it any more, checked again against a
census taken after the row deletions and once more inside the store's write lock.

Every run leaves one log line with the counts per type and for the blob loop.

## Build

    ./mvnw -B -o clean verify -Dquarkus.http.test-port=0

The suite runs against a real PostgreSQL — zonky binaries spawned as a child
process, never a container — so the baseline is exercised against the database it
ships on. All three upstreams are in-process stubs; the suite has no network, and
its default for every upstream key is a closed port so a test that has not opted
into a stub cannot reach the internet by accident.

Needs `eu.wohlben.qits:qits-blobstore` and `qits-registries-{common,npm,maven,oci}`
installed locally (`./mvnw install` in each sibling repository) or released to the
platform's Maven repository, which the `qits-maven` repository in `pom.xml` names.

This module compiles to a GraalVM native image, which is what a deployment runs:

```
sdk env && ./mvnw -B verify -Dnative
```

`.sdkmanrc` names `25.0.2-graalce`, so this needs no container. `-Dnative` is a
profile trigger and nothing else — without the `native` profile in `pom.xml` it
would build a jar and report success — and without a GraalVM on the path Quarkus
falls back to a 1.8 GB Mandrel image over docker, green either way. Recognise the
fallback by the image pull.

## Deployment

**How it ships.** A push builds `docker/Dockerfile` — a Mandrel builder stage that native-compiles
this module, a `ubi-minimal` runtime stage that carries only the binary — and pushes it as
`qits/qits-platform-mirror:<sha>`; a release rebuilds the same content under the released version
(`.config/qits/ci-post-receive.yml` and `.config/qits/ci-event-release.yml`). Both builds run
`--network qits-net` with `--build-arg QITS_MAVEN_REPOSITORY_URL=…`, because `qits-blobstore` and
the three `qits-registries` jars exist only in the platform's own Maven repository and a docker
build reaches no other address for them. `.config/qits/deployments.yml` is the deploy answer:
**a platform service** (one cache warmed by every environment, not a copy per tier) with
`resources: postgresql:db` and the health gate at `/mirror/q/health/ready`. Everything that grammar
cannot say — the loopback host port `127.0.0.1:8082:8080` its non-qits-net clients need, and the
volume the blobs live on — is a run-arg, written by the bootstrap CLI. Every registry address the
build reads is `qits-artifacts`', never this deployment's: a mirror must not build through itself.

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

The admin JSON API and the explorer UI. Phase 2 follow-ups; see
`byte-plane-split-plan.md` in the superproject.
