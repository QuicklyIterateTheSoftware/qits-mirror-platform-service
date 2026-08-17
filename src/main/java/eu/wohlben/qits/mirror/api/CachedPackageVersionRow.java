package eu.wohlben.qits.mirror.api;

import java.time.Instant;
import java.util.List;

/** A cached version/tag and the files or manifests that make it available. */
public record CachedPackageVersionRow(
    String version,
    List<String> labels,
    List<String> files,
    long sizeBytes,
    Instant cachedAt,
    Instant lastAccessedAt) {}

