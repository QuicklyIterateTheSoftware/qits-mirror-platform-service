package eu.wohlben.qits.mirror.api;

import java.time.Instant;
import java.util.List;

/** One package, Maven coordinate, or OCI image held by a cache root. */
public record CachedPackageRow(
    String name,
    List<CachedPackageVersionRow> versions,
    long sizeBytes,
    Instant lastAccessedAt) {}

