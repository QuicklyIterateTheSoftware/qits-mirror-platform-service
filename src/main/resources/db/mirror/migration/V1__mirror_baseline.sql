-- qits-platform-mirror's own Flyway lineage, on its own named `mirror` datasource and its own
-- PostgreSQL database. A FRESH BASELINE, not a copy of qits-artifacts' thirteen migrations.
--
-- WHY FRESH. The libraries carry no migrations by design (byte-plane-split-plan.md phase 1): each
-- consuming service owns its schema. qits-artifacts keeps its lineage untouched and its data; this
-- service starts empty, so replaying thirteen migrations to arrive at the same tables would carry
-- over a widen-and-re-widen history of a check constraint that lands somewhere this service does not
-- want, plus four tables (daemon_binary, docs_*, the two git ones) it has no entities for.
--
-- WHAT IS HERE. Every table the library entities map, and no others. That is the blob core
-- (artifact_repository, artifact_record, artifact_metadata) plus the three formats' tables. Column
-- names, types, lengths, keys and foreign keys are qits-artifacts' verbatim, so a row means the same
-- thing in both databases and a future move of cached content between them is a copy rather than a
-- translation.
--
-- POSTGRESQL DIALECT, and it is the only one this file has to be right in: the deployment is
-- postgres and so is the suite (real binaries spawned by zonky, no docker). The one difference from
-- the H2 originals is `clob` -> `text`; everything else is spelled the same.

-- === the blob core ==============================================================================

-- A named, typed container. The name is the natural key; the type selects the validation profile.
--
-- THE CHECK CONSTRAINT IS THE THREE CACHE TYPES, AND EXACTLY THOSE.
--
-- The rule this follows is that the constraint and the registered RepositoryTypeProfile beans must
-- name the same set. Break it in either direction and the failure is bad:
--
--   * a key the database accepts that no profile claims is a row nothing on this classpath can
--     enforce — the validation its blobs were accepted under is unavailable, and serving or
--     extending it would be guessing (RepositoryTypeProfiles.require answers 400, at use, long after
--     the row was written);
--   * a key a profile claims that the database refuses turns a clean 400 at the API boundary into a
--     constraint violation and a 500 from somewhere inside a transaction.
--
-- This service registers exactly NPM_PROXY, MAVEN_PROXY and OCI_MIRROR — the hosted and CI profiles
-- that ride in on the library jars are excluded from bean discovery (quarkus.arc.exclude-types in
-- application.properties). So the two sets agree, and the constraint below is not a narrower opinion
-- than the code's: it is the same opinion, written where the database can hold it.
--
-- Named rather than inline, which is what qits-artifacts' V2 had to go back and fix: an unnamed
-- inline check gets a generated name that is an artifact of DDL order, and the next widening then
-- has to look it up in information_schema before it can drop it. A widening here is
-- `alter table artifact_repository drop constraint ck_artifact_repository_type;` and one add.
create table artifact_repository (
    name varchar(255) not null,
    type varchar(64) not null,
    created_at timestamp(6) with time zone not null,
    primary key (name),
    constraint ck_artifact_repository_type
        check (type in ('NPM_PROXY','MAVEN_PROXY','OCI_MIRROR'))
);

-- One immutable metadata record per validated upload, and its flat string metadata map.
--
-- NOTHING IN THIS SERVICE WRITES EITHER TABLE, and they are here anyway. Every profile registered
-- here is a protocol profile, so BlobService's validating upload path is refused for all three and
-- no artifact_record row can be created. But the entities are mapped by the blob core this service
-- runs on and are assigned to its persistence unit, so the tables have to exist: a mapped entity
-- with no table is a boot that succeeds and a query that fails much later. They are the seam the
-- explorer's listing reads through too, which is the phase-2 admin surface.
create table artifact_record (
    id varchar(255) not null,
    repository varchar(255) not null,
    blob_id varchar(64) not null,
    mediatype varchar(255) not null,
    size_bytes bigint not null,
    created_at timestamp(6) with time zone not null,
    accessed_at timestamp(6) with time zone,
    primary key (id)
);

create table artifact_metadata (
    record_id varchar(255) not null,
    meta_key varchar(255) not null,
    meta_value varchar(4000),
    primary key (record_id, meta_key)
);

create index idx_artifact_record_repository on artifact_record (repository);
create index idx_artifact_record_blob_id on artifact_record (blob_id);
create index idx_artifact_record_created_at on artifact_record (created_at);
create index idx_artifact_record_repository_accessed on artifact_record (repository, accessed_at);

alter table artifact_record
    add constraint fk_artifact_record_repository foreign key (repository) references artifact_repository (name);
alter table artifact_metadata
    add constraint fk_artifact_metadata_record foreign key (record_id) references artifact_record (id);

-- === npm ========================================================================================

-- One cached version of one package. The TARBALL BYTES are an ordinary blob keyed by their sha256;
-- npm's own two hashes are stored BESIDE it rather than instead of it — `shasum` is sha1 and
-- `integrity` a base64 sha512 SRI string, neither of which the store can address by, and both of
-- which the installing client verifies end to end. For a proxied version they are upstream's values
-- untouched, which is what keeps this cache incapable of silently corrupting a package.
--
-- `manifest_json` is the version's manifest exactly as it arrived. Only `dist` is replaced at serve
-- time, because a tarball URL is a property of the request's authority, not of the package.
create table npm_version (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    version varchar(128) not null,
    tarball_blob_id varchar(64) not null,
    integrity varchar(255),
    shasum varchar(64),
    manifest_json text not null,
    created_at timestamp(6) with time zone not null,
    accessed_at timestamp(6) with time zone,
    primary key (repository, package_name, version)
);

-- A movable pointer at a version. Written from a cached packument's own dist-tags.
create table npm_dist_tag (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    tag varchar(128) not null,
    version varchar(128) not null,
    updated_at timestamp(6) with time zone not null,
    primary key (repository, package_name, tag)
);

-- The packument cache. Packuments are the one npm document that genuinely mutates — a new version
-- appears upstream without anything here changing — so unlike a tarball this is cached with a TTL
-- (qits.artifacts.npm.proxy.packument-ttl) and revalidated with the stored `etag`.
--
-- `doc` is upstream's document VERBATIM, not rewritten before storage, for two reasons: the rewrite
-- target depends on the request (X-Forwarded-Host, else the authority actually dialled), so a stored
-- rewrite would be wrong for half the callers; and the original URLs are what the tarball miss path
-- fetches from, so discarding them would strand any package whose tarballs are not on upstream's
-- canonical /<pkg>/-/<file> layout.
create table npm_proxy_packument (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    doc text not null,
    etag varchar(255),
    fetched_at timestamp(6) with time zone not null,
    primary key (repository, package_name)
);

-- A version's identity, kept after its row is gone. Eviction deletes npm_version rows, and version
-- immutability is enforced by looking for one — so without this a collected version's name would
-- quietly re-open. It matters less on a cache than on a hosted registry (nothing publishes here),
-- but the entity is mapped and the table is one line.
create table npm_version_tombstone (
    repository varchar(255) not null,
    package_name varchar(255) not null,
    version varchar(128) not null,
    tarball_blob_id varchar(64),
    collected_at timestamp(6) with time zone not null,
    primary key (repository, package_name, version)
);

create index idx_npm_version_package on npm_version (repository, package_name);
create index idx_npm_version_package_accessed on npm_version (repository, package_name, accessed_at);
create index idx_npm_dist_tag_package on npm_dist_tag (repository, package_name);

alter table npm_version
    add constraint fk_npm_version_repository foreign key (repository) references artifact_repository (name);
alter table npm_dist_tag
    add constraint fk_npm_dist_tag_repository foreign key (repository) references artifact_repository (name);
alter table npm_proxy_packument
    add constraint fk_npm_proxy_packument_repository foreign key (repository) references artifact_repository (name);
alter table npm_version_tombstone
    add constraint fk_npm_version_tombstone_repository foreign key (repository) references artifact_repository (name);

-- === maven ======================================================================================

-- One cached file: the path IS the identity. Upstream's own .sha1/.md5/.sha256/.sha512 files are
-- rows here too — they are immutable paths like any other, and caching upstream's copy rather than
-- deriving one locally is what keeps the client's verification END TO END.
create table maven_artifact (
    repository varchar(255) not null,
    path varchar(1024) not null,
    blob_id varchar(64) not null,
    size_bytes bigint not null,
    created_at timestamp(6) with time zone not null,
    accessed_at timestamp(6) with time zone,
    primary key (repository, path)
);

-- The one maven document that mutates. maven-metadata.xml lists the versions upstream has, so a new
-- release changes it with nothing here changing — it cannot be an immutable path, and it cannot be
-- derived from the cached rows either, because those are the versions this cache happens to hold
-- rather than the versions that exist.
--
-- Two validators, because maven repositories are older than universal ETag support: Central answers
-- both, a mirror behind a plain file server may answer only Last-Modified, and either one turns an
-- expiry into a 304 rather than a document.
create table maven_proxy_metadata (
    repository varchar(255) not null,
    path varchar(1024) not null,
    doc text not null,
    etag varchar(255),
    last_modified varchar(64),
    fetched_at timestamp(6) with time zone not null,
    primary key (repository, path)
);

create index idx_maven_artifact_repository_accessed on maven_artifact (repository, accessed_at);

alter table maven_artifact
    add constraint fk_maven_artifact_repository foreign key (repository) references artifact_repository (name);
alter table maven_proxy_metadata
    add constraint fk_maven_proxy_metadata_repository foreign key (repository) references artifact_repository (name);

-- === OCI ========================================================================================

-- The per-(repository, image) manifest registry. Manifest BYTES are an ordinary blob — a manifest is
-- content-addressed JSON — and get no row of their own; THIS table is what scopes them to a name, so
-- the globally-deduped blob store cannot serve one namespace's manifest out of another's. It also
-- carries the mediaType clients dispatch on, and it is what makes a multi-arch pull work at all: an
-- index's children are addressed by digest and untagged, and a tag table alone cannot resolve them.
--
-- `digest` is the bare 64-hex, the same form as artifact_record.blob_id; the wire's `sha256:` prefix
-- is stripped at the route boundary.
create table oci_manifest (
    repository varchar(255) not null,
    image_name varchar(255) not null,
    digest varchar(64) not null,
    media_type varchar(255) not null,
    size_bytes bigint not null,
    created_at timestamp(6) with time zone not null,
    accessed_at timestamp(6) with time zone,
    primary key (repository, image_name, digest)
);

-- A movable pointer at a manifest digest.
create table oci_tag (
    repository varchar(255) not null,
    image_name varchar(255) not null,
    tag varchar(128) not null,
    manifest_digest varchar(64) not null,
    updated_at timestamp(6) with time zone not null,
    accessed_at timestamp(6) with time zone,
    primary key (repository, image_name, tag)
);

-- One upstream registry, and the local namespace segment that fronts it.
--
-- A TABLE RATHER THAN A CONFIG MAP: config keys are invisible, while a row has a CRUD API and a UI,
-- so an operator can see which upstreams this service mirrors without reading a deployment's env.
--
-- `domain` is the upstream's IDENTITY — docker.io, quay.io, registry.access.redhat.com — and the
-- endpoint is derived from it (https://<domain>, with docker.io -> registry-1.docker.io as the one
-- well-known exception, in MirrorEndpoints). `slug` is what a puller writes, it is unique because it
-- is a namespace, and it is a foreign key into artifact_repository because every upstream is PAIRED
-- with a repository row of type OCI_MIRROR — so namespace resolution on a pull is a table read and
-- never a config lookup.
--
-- Credentials are deliberately absent: a client's `docker login` does not traverse a pull-through
-- hop — this service dials upstream as itself — so a private registry needs a SERVER-side credential,
-- which arrives later as an additive column pair the day a Hub 429 makes it necessary.
create table oci_mirror_upstream (
    domain varchar(255) not null,
    slug varchar(255) not null,
    created_at timestamp(6) with time zone not null,
    primary key (domain),
    constraint uq_oci_mirror_upstream_slug unique (slug)
);

-- What this cache knows about a tag's freshness. An OCI tag is a movable pointer — `jdk-25` and
-- `9.6` move under toolchain and security updates — so it is the one mirrored thing with a TTL.
-- Everything else is addressed by digest and kept until eviction.
create table oci_mirror_tag_check (
    repository varchar(255) not null,
    image_name varchar(255) not null,
    tag varchar(128) not null,
    checked_at timestamp(6) with time zone not null,
    primary key (repository, image_name, tag)
);

create index idx_oci_manifest_image on oci_manifest (repository, image_name);
create index idx_oci_manifest_image_accessed on oci_manifest (repository, image_name, accessed_at);
create index idx_oci_tag_image on oci_tag (repository, image_name);
create index idx_oci_tag_image_accessed on oci_tag (repository, image_name, accessed_at);

alter table oci_manifest
    add constraint fk_oci_manifest_repository foreign key (repository) references artifact_repository (name);
alter table oci_tag
    add constraint fk_oci_tag_repository foreign key (repository) references artifact_repository (name);
alter table oci_mirror_tag_check
    add constraint fk_oci_mirror_tag_check_repository foreign key (repository) references artifact_repository (name);

-- The pairing invariant, in the schema rather than in a comment: an upstream's slug IS a repository
-- row. Deleting an upstream leaves that row and everything cached under it (the append-only posture),
-- which this direction of the key allows and the reverse would forbid.
alter table oci_mirror_upstream
    add constraint fk_oci_mirror_upstream_repository foreign key (slug) references artifact_repository (name);

-- === the prefill ================================================================================
-- Three public registries with static domains, which is what makes them lineage material rather than
-- deployment data: a fresh deployment mirrors quay, Red Hat and Docker Hub with no manual step. The
-- repository row goes FIRST because the upstream's slug references it.
--
-- The two CACHE ROOTS npm and maven need are deliberately NOT here. `npmjs` and `central` are
-- ordinary seeded rows and come from MirrorRepositorySeeder at boot, matching how qits-artifacts
-- split the same decision: a migration prefills static platform knowledge, and a seeded repository
-- name is not that.
insert into artifact_repository (name, type, created_at) values
    ('hub',    'OCI_MIRROR', current_timestamp),
    ('quay',   'OCI_MIRROR', current_timestamp),
    ('redhat', 'OCI_MIRROR', current_timestamp);

insert into oci_mirror_upstream (domain, slug, created_at) values
    ('docker.io',                  'hub',    current_timestamp),
    ('quay.io',                    'quay',   current_timestamp),
    ('registry.access.redhat.com', 'redhat', current_timestamp);
