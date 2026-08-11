# qits-platform-mirror

The platform's pull-through caches, and nothing else.

One deployable Quarkus application over two library repositories — `qits-blobstore`
(the content-addressed store) and `qits-registries` (the npm, maven and OCI wire
protocols). This repository holds what a library cannot: configuration, a schema,
a seeder, and the explorer — a read-only admin API at `/mirror/api` with the
Angular client that draws it at `/mirror/`.

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

## The explorer

What a cache holds is decided by what somebody pulled, and what it drops is decided by
a window and a clock. So the surface that shows it is **read-only, whole**: two GETs,
no create, no delete, and no "evict now" button — a page that could delete a cached tag
would be a second eviction policy with no record of why it ran.

| Path | Answers |
|---|---|
| `GET /mirror/api/repositories` | `{"repositories":[{name, type, upstream, createdAt}]}` — every cache root and what it fronts |
| `GET /mirror/api/upstreams` | `{"upstreams":[{host, namespace, cachedImages, createdAt}]}` — every mirrored registry |
| `/mirror/` | the Angular client (`src/main/webui`, the `qits-platform-spa-mirror` submodule), built and served by Quinoa |

`name` and `type` on a repository and `host` on an upstream are the fields the client
is **promised**; it draws everything else as a dynamic column typed by what the value
turns out to be. So a field added on this side appears in the UI with no client
release — and a field that would be a guess must not be added at all, because a
plausible wrong number in a table is worse than a missing one.

**A failed read is a 5xx, never an empty answer.** This is the service where the cost
of the opposite is highest: a wrong "there is no such thing" is cached by every docker,
npm and maven client that asked. Nothing in `MirrorExplorer` or the two resources
catches a database error, so an unreadable store reaches the caller as a 500; `DbRetry`
wraps both reads, which is what covers a connection that dies mid-flight after the pool
has already handed it over. `MirrorApiTest` asserts both halves.

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

    git submodule update --init src/main/webui
    (cd src/main/webui && npm ci)
    ./mvnw -B -o clean verify -Dquarkus.http.test-port=0

**The clone-alone rule now has two lines in front of it.** An uninitialised gitlink is
an empty directory, and that is the one case Quinoa treats as a misconfiguration rather
than "no client": the build stops at `No package.json found in Web UI directory`.
`./mvnw test` still needs neither node nor the submodule — Quinoa is disabled by
default in test mode — but `verify` runs `package` on its way to failsafe, so it, like
`package`, needs both. Quinoa shells out to the **host's** node, which is why the
client stays on Angular 21: the platform's node is 22.22.0 and Angular CLI 22 wants
22.22.3.

The suite runs against a real PostgreSQL — zonky binaries spawned as a child
process, never a container — so the baseline is exercised against the database it
ships on. All three upstreams are in-process stubs; the suite has no network, and
its default for every upstream key is a closed port so a test that has not opted
into a stub cannot reach the internet by accident.

Needs `eu.wohlben.qits:qits-blobstore` and `qits-registries-{common,npm,maven,oci}`
installed locally (`./mvnw install` in each sibling repository) or released to the
platform's Maven repository, which the `qits-maven` repository in `pom.xml` names.
`qits-db-core` and `qits-arch-rules` come from that repository too, at released
versions — the datasource resilience baseline and the test that enforces it.

`qits-db-core` carries both halves of the platform's datasource resilience, and this
repository now uses both. `PatientPgDriver` is configuration rather than code — the
datasource block names it as a string — and covers a connection that was dead before a
request began. `DbRetry` covers one that dies mid-flight, and it has exactly one call
site here: the explorer's two reads, which a caller is waiting on. Nothing else needs
it. A pull writes through `qits-registries`, so a write seam a cutover can reach is in
those libraries; what is written here is the boot seeder (a failed boot is a restart)
and the eviction sweep (a background chore that logs and retries on the next schedule),
and neither has anyone waiting.

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
`--network host` with `--build-arg QITS_MAVEN_REPOSITORY_URL=…`, because `qits-blobstore` and
the three `qits-registries` jars exist only in the platform's own Maven repository and a docker
build reaches no other address for them — host networking rather than a custom one because buildkit
refuses custom networks.

**Both pipelines build the client before the image.** The step container sits on `qits-net`, where
the platform's npm registry answers; a docker `RUN` reaches that registry by no address at all, so
the Dockerfile neuters Quinoa's install/ci/build commands to `--version` and stages the bundle it was
handed. A missing bundle is a red build at a `test -f` guard, before the native compile — not a green
one shipping a service that answers `/mirror/` with a 404. `.config/qits/deployments.yml` is the
deploy answer:
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

Nothing this repository has deferred. The admin API and the explorer UI were the
open phase-2 work package in `byte-plane-split-plan.md`; both are above.

What is still ahead is not this service's to do alone: the **cutover**, which splits
the client configuration (npm scoped registries, dockerd `registry-mirrors`, the maven
repositories list) so third-party traffic arrives here rather than at `qits-artifacts`.

The packaged-surface probe list is **done, on the fast-jar, 2026-08-11** — Quinoa is off
in tests, so nothing else could have proved it. `/mirror/` answers 200 HTML with
`<base href="/mirror/">`, a deep link falls back to `index.html`, `/mirror/api/nope` is
a 404 rather than a page, `/mirror/q/health/ready` is 200, and a mistyped `/v2`,
`/artifacts/npm` or `/artifacts/maven` path answers its own router's 404. Bare `/mirror`
is a 404, the known Quinoa wart every client shares. **Not** proved: the native binary
and the image build, which ride the next deploy.
