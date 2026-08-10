# qits-platform-mirror as a GraalVM-native container IMAGE — the same shape as qits-artifacts',
# qits-platform-dns' and qits-gateway's docker/Dockerfile: a Mandrel builder stage compiles the
# binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw verify -Dnative` produces
# `target/qits-platform-mirror` locally with no container involved. What this adds is the image
# around it — and a way for a machine with no GraalVM (a CI runner, a laptop on a plain OpenJDK) to
# get a binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root, on the network where the platform's Maven repository answers:
#
#     docker build --network qits-net \
#       --build-arg QITS_MAVEN_REPOSITORY_URL=http://qits-platform-artifacts:8080/artifacts/maven/maven \
#       -t qits/platform-mirror:latest -f docker/Dockerfile .
#
# BOTH FLAGS ARE REQUIRED and neither has a workable default. This repository is ONE application over
# two library repositories: qits-blobstore and qits-registries-{common,npm,maven,oci} are jars
# published to the platform's own Maven repository and available from nowhere else — not Maven
# Central, and not the builder container's empty ~/.m2. A RUN step reaches the public internet and
# nothing else, so without `--network` there is no address that answers at all. See
# .config/qits/ci-post-receive.yml, which passes exactly this pair.
#
# A MIRROR MUST NOT BUILD THROUGH ITSELF. Every registry address in this file is qits-artifacts' —
# the Maven repository above, and the two FROM lines below — so this service's first deploy, and
# every later one, is independent of whether this service is up. Do not repoint them at this
# deployment's own published port when the cutover moves the third-party namespaces here.
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ------------
# `docker run --rm qits/platform-mirror:latest` dies before the HTTP listener, at Flyway, because
# this service ships its datasource as the platform's generic resource contract and nothing resolves
# it:
#
#   quarkus.datasource.mirror.jdbc.url=${QITS_RESOURCE_DB_URL}
#   quarkus.datasource.mirror.username=${QITS_RESOURCE_DB_USERNAME}
#   quarkus.datasource.mirror.password=${QITS_RESOURCE_DB_PASSWORD}
#
# THAT IS THE DESIGN AND A FALLBACK IS NOT A FIX. A cache that invented an empty store would answer
# every lookup as a miss and refetch the internet on every build, healthily, forever. The refusal is
# what turns that into a visible failure.
#
# ON THE PLATFORM NOBODY SETS THESE BY HAND. This repository's .config/qits/deployments.yml carries
# `resources: postgresql:db`; qits-deployments creates the role and the database `qits_platform_mirror`
# on the tier's postgres before the successor container starts and injects all three variables.
#
# One more path has no safe default, and it is the one that holds the bytes:
#
#   QITS_ARTIFACTS_BLOBS_DIR   Where cached tarballs, jars and layers live on disk. Every one of them
#                              is indexed by a row in the database above — blobs whose index did not
#                              survive are not data, they are orphans — so the two travel together.
#                              The shipped default is ${user.home}/.qits/data/mirror/blobs, which
#                              inside this image resolves to nothing usable: UID 1001 has no
#                              /etc/passwd entry in ubi-minimal and a native image reads user.home
#                              through getpwuid, so setting HOME does not fix it.
#
# So a deployment mounts a volume at /data (which this image owns, see the runtime stage) and names
# both:
#
#   docker run -v qits-platform-mirror-data:/data \
#     -p 127.0.0.1:8082:8080 \
#     -e QITS_RESOURCE_DB_URL=jdbc:postgresql://some-postgres:5432/qits_platform_mirror \
#     -e QITS_RESOURCE_DB_USERNAME=... -e QITS_RESOURCE_DB_PASSWORD=... \
#     -e QITS_ARTIFACTS_BLOBS_DIR=/data/mirror/blobs \
#     qits/platform-mirror:latest
#
# THE PUBLISHED HOST PORT IS NOT OPTIONAL FOR THIS SERVICE, unlike its siblings. Its clients are not
# on qits-net: dockerd resolves no qits-net name, so a `registry-mirrors` entry has to be a host
# address, and `/v2` behind the gateway is already qits-artifacts' entry. The loopback bind is
# deliberate — this is an unauthenticated cache and nothing outside the host should reach it.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `localhost:8081/quay/…` is the platform's pull-through mirror of quay.io: the first build fetches
# the image from upstream and caches it, every later build is served from local disk. The tag is
# upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves it.
FROM localhost:8081/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# One module, so this is the module. `.dockerignore` is what keeps the copy cheap and stops a stale
# target/ shadowing what this stage compiles.
COPY . .

# -Dnative flips quarkus.native.enabled through the `native` profile in pom.xml. Cap native-image RAM
# so the build is deterministic on small machines.
#
# Tests are skipped because `./mvnw verify` on a clone is their gate (README.md) and re-running them
# here proves the same thing more slowly — they spawn a real postgres child process, which this stage
# is not the right place to arrange.
#
# `-s .qits-maven-settings.xml` is what lets the `qits-maven` repository be an http:// URL at all:
# Maven blocks external http repositories by default, and an exact repository-id mirror is the one
# thing that outranks the blocker. The settings file permits that one id and nothing else.
ARG QITS_MAVEN_REPOSITORY_URL=http://localhost:8081/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
RUN ./mvnw -B -ntp -s .qits-maven-settings.xml package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g

# --- runtime --------------------------------------------------------------------------------------
# `localhost:8081/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8081/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# Nothing is installed here. This service fetches over HTTP, hashes bytes and writes files; it shells
# out to nothing at all. A package added here is a package on a host serving anonymous reads.
#
# `quarkus.package.output-name` names the binary `qits-platform-mirror` (no -runner suffix), so the
# process is identifiable in `ps` / `docker top`. One module, so it is at target/ and not
# service/target/.
COPY --from=build --chown=1001:root /src/target/qits-platform-mirror /work/qits-platform-mirror
RUN chmod +x /work/qits-platform-mirror

# The mount point for the blobs, created here so it is OWNED. Docker seeds a fresh named volume from
# whatever the image has at the mount path, ownership included; with no such directory it makes one
# root-owned 0755 and the first cache write dies on an AccessDeniedException instead of anything that
# names the cause. Group-writable and group-root because a ubi image is meant to survive being
# assigned an arbitrary UID with GID 0. (A bind mount still takes its ownership from the host —
# nothing in an image can fix that.)
RUN mkdir -p /data && chown -R 1001:root /data && chmod -R 775 /data

# Nothing is named here: no QUARKUS_DATASOURCE_* and no QITS_ARTIFACTS_BLOBS_DIR. Both belong to the
# deployment's storage, and this image refuses to start until a deployment says where they go.

# One port for all of it — /artifacts/npm/**, /artifacts/maven/**, /v2/** and /mirror/q/** are four
# route stacks in one process, not four listeners.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the published host port, not a peer on qits-net.
ENTRYPOINT ["/work/qits-platform-mirror", "-Dquarkus.http.host=0.0.0.0"]
