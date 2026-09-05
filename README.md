# qits-mirror-platform-service

The platform's pull-through caches, and nothing else, deployed as the `qits-platform-mirror`
application.

One deployable Quarkus application over one library repository, `qits-registries-javalib` —
`qits-blobstore` (the content-addressed store) and the `qits-registries-*` jars (the npm, maven
and OCI wire protocols). This repository holds what a library cannot: configuration, a schema,
a seeder, and the explorer — an admin API at `/mirror/api` (two listings, a
drill-down, and one guarded invalidate door) with the
Angular client that draws it, served at `/` on this service's own host,
`mirror.<env>.<domain>`. That is the same authority dockerd, npm and maven already
dial: the edge picks the gate per request, so the browser plane and the machine
plane share one name.

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
a window and a clock. So the surface that shows it is **read-only but for one route**:
no create, no publish, and no "clear the cache" — a page that decided what to keep
would be a second eviction policy with no record of why it ran.

The exception is the **invalidate door**, and it is a repair rather than a policy.
On 2026-09-05 a cached `quarkus-proxy-registry-3.34.6.pom` answered `500` to every
request for four days — the bytes were fine and upstream was fine, the row had gone
bad — and nothing on this service could clear it: the `maven-proxy` window is `P90D`,
the entry was cached on 2026-09-01, and the sweep is a clock and not a hand. Meanwhile
it blocked release gates across the platform. **A cache with no way to drop one entry
is a cache whose faults are permanent.** The door takes one entry by its exact path,
refuses anything that is not a pull-through, touches no blob, and what it removes comes
back on the very next request — which is the whole difference from a collection.

| Path | Answers |
|---|---|
| `GET /mirror/api/repositories` | `{"repositories":[{name, type, upstream, createdAt}]}` — every cache root and what it fronts |
| `GET /mirror/api/upstreams` | `{"upstreams":[{host, namespace, cachedImages, createdAt}]}` — every mirrored registry |
| `GET /mirror/api/repositories/{repository}/packages` | what one root holds, folded into coordinates |
| `DELETE /mirror/api/repositories/{repository}/entries?path=…` | evicts one cached entry; `{repository, path, kind, rowsRemoved}`. **The only guarded route here** — `qits:admin` or `qits:system`. `404` if nothing was cached there, `409` if the root is not a maven pull-through |
| `/` on `mirror.<env>.<domain>` | the Angular client (`src/main/webui`, the `qits-mirror-platform-frontend` submodule), built and served by Quinoa |

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

`V2__blob_tables.sql` adds the blob store's three tables — `blob`, `blob_content`
and `blob_chunk` — on the same database. It is a **verbatim copy** of
`qits-blobstore`'s `src/main/resources/db/blobstore-tables.sql`, because a library
owns no schema and ships no Flyway migrations: the canonical text lives there and
each consumer copies it into its own lineage, which keeps a later drift readable as
a diff. `qits.artifacts.blobs-datasource=mirror` is what points the store at them.

**Blob bytes are rows now, and the service is stateless.** There is no blob
directory and no volume: a tarball and the row that names it commit or fail
together, so neither can outlive the other. A restart loses only fetches in flight.

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
seven days, read off `blob.stored_at`) gates identity rows and not only blob
deletions: deleting a row while its blob is inside the window would leave the blob
row-less, and row-less is untouchable by construction, so such an identity is
withheld whole and re-planned next run. **The blob sweep** is the one thing that
frees storage, and it carries no policy: a blob dies only when no type reaches it
any more, checked again against a census taken after the row deletions and once
more inside the store's per-blob advisory lock.

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
installed locally (`./mvnw install` in `components/qits-registries/qits-registries-javalib`,
which builds them all) or released to the platform's Maven repository, which the
`qits-maven` repository in `pom.xml` names.
Both are pinned to `1.0.0-pgblobs-SNAPSHOT` while the PostgreSQL blob store is on
its branch, so a build here needs those branches installed until they release.
`qits-db-core` and `qits-arch-rules` come from that repository too, at released
versions — the datasource resilience baseline and the test that enforces it.
`qits-userflows` joins them in **test scope** for the catalogue below.

### The userflow catalogue

`src/test/java/eu/wohlben/qits/mirror/stories/` is **thirteen user stories over seven
classes**, all against one launched artifact (one `StoryProfile`, so one mirror and one
cache) with a recording stand-in where each of the three registries this service caches
really is. Each story emits its steps, its notes and an **observed** network diagram
under `target/userstories/`.

Everything here is arranged around **one negative**. A cache's central claim is not
about a response, it is about a request that was never made — so the warm story on each
plane ends in `assertNoEdgesTo(<the registry>)`, taken against a stand-in that is up and
recording throughout. A hit that quietly dialled upstream would pass every other test in
this repository and cost money on every CI run.

| category     | stories | what it settles |
| ------------ | ------- | ---------------- |
| `caching`    | 2 | the npm cold miss (counted on the *upstream's* end) and then the warm read that reaches nobody |
| `npm`        | 2 | the same pair driven by the **real npm CLI** — which follows the rewritten `dist.tarball` and verifies upstream's `integrity` end to end |
| `maven`      | 2 | the JVM plane: cached vs derived checksums, five reads for four fetches, then a resolve that never leaves the process |
| `oci`        | 2 | the container plane: manifest byte-for-byte with its digest, blobs verified as they stream, then a pull that reaches no registry |
| `outage`     | 2 | the registry goes **dark** mid-story: what is cached keeps installing, what was never cached is a 502, and past the TTL the stale document is served |
| `refusals`   | 2 | a no is passed through and **remembered nowhere**, and an upstream that cannot answer is a 502 and never a 404 |
| `operations` | 1 | the explorer's read-only inventory — and that reading it dials **none** of the three registries |

Five things they prove that no `@QuarkusTest` here can: that the cache roots exist
because the process *booted* (`MirrorStartupSeed` runs in `NORMAL` and never under
`TEST`), that the shipped `${QITS_RESOURCE_DB_*}` datasource expression really resolves
the platform's generic resource contract, that the absolute `dist.tarball` url built
from the request is right (npm follows it), that a real gzipped archive survives the
round trip, and that `/mirror/api`, `/artifacts/npm|maven`, `/v2` and health coexist on
one port.

Two mechanisms are worth knowing before editing a story:

- **`stories/support/RecordingUpstream`** is the far side, and it replaced
  `qits-service-mock`. A `MockService` serializes every body as JSON, can only be armed
  from the instance that started it, cannot decline to answer, and does not record the
  status it gave — which rules out a real tarball, a jar, an OCI layer, and the outage
  story's mid-story blackout. `qits-service-mock` is no longer a dependency here; it
  remains the right tool for standing in for another JSON API.
- **`stories/support/AccessLogTap`** is the incoming tap for the npm CLI stories, and it
  is **armed**. npm talks to the launched process over a socket this JVM never touches,
  so its traffic exists only in the server's access log — but every RestAssured story's
  traffic is in that log too, so an always-on source would draw each of those edges
  twice. Exactly one class arms it.

Three classes pin `@TestMethodOrder`, and in each it is load-bearing rather than tidy:
"warm" is a state the cold story creates, and a cumulative upstream recording is
attributed by a cursor, so the cold story's fetches belong on the cold story's diagram
and the warm story's empty slice is what the negative reads.

**`skipITs` is true and stays true.** The second step of
`.config/qits/ci-event-release-request.yml` names every story class —
`-DskipITs=false "-Dit.test=…"`, with `-Dquarkus.quinoa=false` — and publishes
`target/userstories/` as the docs bundle `@userflows/qits-platform-mirror`. It declares
`gating: false`, and it runs once per release-request fold rather than per commit. Default-on would drag the packaged-surface probe below into
a client-less run the day it lands, and would make a plain `verify` spawn a second
postgres for what CI runs anyway. `-Dnative` flips the property, so a native build runs
the catalogue against the binary.

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

**How it ships.** A release builds `docker/Dockerfile` — a Mandrel builder stage that
native-compiles this module, a `ubi-minimal` runtime stage that carries only the binary — and pushes
it as `qits/qits-platform-mirror:<version>` (`.config/qits/ci-event-release.yml`). **Nothing builds
a push any more**: per-push CI is retired platform-wide, and the other pipeline,
`.config/qits/ci-event-release-request.yml`, runs the same build — minus the push — against a
release request's fold, `release/<id>`, as the gating half of the QA gate. Both builds run
`--network host` with `--build-arg QITS_MAVEN_REPOSITORY_URL=…`, because `qits-blobstore` and
the three `qits-registries` jars exist only in the platform's own Maven repository and a docker
build reaches no other address for them — host networking rather than a custom one because buildkit
refuses custom networks.

**Both pipelines build the client before the image.** The step container sits on `qits-net`, where
the platform's npm registry answers; a docker `RUN` reaches that registry by no address at all, so
the Dockerfile neuters Quinoa's install/ci/build commands to `--version` and stages the bundle it was
handed. A missing bundle is a red build at a `test -f` guard, before the native compile — not a green
one shipping a service that answers `/` with a 404. `.config/qits/deployments.yml` is the
deploy answer:
**a platform service** (one cache warmed by every environment, not a copy per tier) with
`resources: postgresql:db` and the health gate at `/mirror/q/health/ready`. The one thing that
grammar cannot say — the loopback host port `127.0.0.1:8082:8080` its non-qits-net clients need — is
a run-arg, written by the bootstrap CLI. **There is no blobs volume any more**: the container is
stateless except for its database, so a deployment still mounting one is carrying dead bytes. Every
registry address the build reads is `qits-artifacts`', never this deployment's: a mirror must not
build through itself.

The database arrives through the platform's generic resource contract —
`QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD`. None of them has a default:
an unset variable kills the process at Flyway's `migrate-at-start` naming the
missing variable, rather than opening some fallback store nobody meant.

Readiness is at `/mirror/q/health/ready`.

**The protocol routes do not follow that segment, and cannot.** Their prefixes are
literals in the `qits-registries` jars, so this service answers npm and maven on
the same `/artifacts/*` paths `qits-artifacts` does, and OCI at the host root like
every registry. The per-service host is what settles that collision: those paths are
reached on `mirror.<env>.<domain>`, so no gateway entry has to choose between two
services for `/artifacts/*`. Splitting the client configuration (npm scoped registries,
dockerd `registry-mirrors`, the maven repositories list) is still the cutover phase's job.

Because the client now sits at `/`, those three roots are inside the SPA fallback's
reach for the first time, and `quarkus.quinoa.ignored-path-prefixes` lists them
absolutely — `/mirror,/artifacts,/v2` — so a mistyped machine path is a 404 rather
than a page.

## Not here yet

The admin API and the explorer UI were the open phase-2 work package in
`byte-plane-split-plan.md`; both are above.

**The invalidate door evicts maven-proxy entries only, and that is deliberate rather
than half-done.** npm and OCI are caches too and will want the same repair, but an npm
entry is a package *and* a version and an OCI entry is an image *and* either a tag or a
digest — neither is a `path`, so each needs a parameter of its own and a test that
proves the eviction reached the right one of two tables. Guessing those spellings before
anything needs them would be worse than the `409` the door answers today, which names
the type instead of pretending the entry is missing.

**The read path does not yet heal itself**, and that half does not live here: the
maven wire is `qits-registries-javalib`'s (`MavenRoutes`, `MavenUpstream`,
`MavenRegistryService`), consumed as a pinned jar. A cached entry whose serving fails on
a storage-side fault should evict itself and refetch inside the request rather than
waiting for a hand on this door — together with an upsert in `recordProxiedArtifact`,
whose check-then-insert is the race that produced the 2026-09-05 fault in the first
place. Both are that repository's to make, and arrive here as a
`qits.registries.version` bump.

What is still ahead is not this service's to do alone: the **cutover**, which splits
the client configuration (npm scoped registries, dockerd `registry-mirrors`, the maven
repositories list) so third-party traffic arrives here rather than at `qits-artifacts`.

The packaged-surface probe list needs a **rerun after the root-path flip**, and the
userflow catalogue this repository now has is not it: every story runs against
a **client-less** artifact (`-Dquarkus.quinoa=false`, and the webui submodule arrives
empty in a step container), so everything below that is about the SPA is out of its
reach by construction. The list is still run by hand on the fast-jar.
It was green on 2026-08-11 against the old `/mirror/` mount; what it must now show is
`/` answering 200 HTML with `<base href="/">`, a deep link falling back to
`index.html`, `/mirror/api/nope` and `/mirror/q/nope` 404 rather than a page, and a
mistyped `/v2`, `/artifacts/npm` or `/artifacts/maven` path answering a 404 — those
three are inside the fallback's reach now and are held back only by the absolute
`ignored-path-prefixes` list. `/mirror/q/health/ready` is 200. **Not** proved: the
native binary and the image build, which ride the next deploy.
