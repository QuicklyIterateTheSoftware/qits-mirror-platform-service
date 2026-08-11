package eu.wohlben.qits.mirror.api;

import java.time.Instant;

/**
 * One cache root, as the explorer lists it.
 *
 * <p><b>Two fields are promised and the rest is not</b>, and the client is written to that: {@code
 * name} and {@code type} have real columns, everything else is drawn as a dynamic column by whatever
 * the value turns out to be. So a field added here appears in the UI with no client release — and a
 * field that would be a guess must not be added at all, because a plausible wrong number in a table
 * is worse than a missing one.
 *
 * <p>Declaration order is the wire order and the column order after it, so identity comes first and
 * bookkeeping last.
 *
 * @param name the repository row's name — the path segment a client addresses: {@code
 *     /artifacts/npm/npmjs/…}, {@code /artifacts/maven/central/…}, {@code /v2/quay/…}
 * @param type the <b>kebab wire form</b> ({@code npm-proxy}, {@code maven-proxy}, {@code
 *     oci-mirror}), never the stored screaming-snake key. The three are all this service registers.
 * @param upstream what this root is a cache <b>of</b> — the whole reason a row here is interesting.
 *     A config URL for the two package caches, the registry domain for an OCI namespace. Null where
 *     nothing answers, which is a row whose upstream was deleted: the namespace keeps serving what
 *     is cached and can fetch nothing new, and a blank cell is the honest picture of that.
 * @param createdAt when the row was written. Cheap — it is on the row already.
 */
public record MirrorRepositoryRow(String name, String type, String upstream, Instant createdAt) {}
