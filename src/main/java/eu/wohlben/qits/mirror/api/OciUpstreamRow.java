package eu.wohlben.qits.mirror.api;

import java.time.Instant;

/**
 * One mirrored container registry, as the explorer lists it.
 *
 * <p>{@code host} is the promised field and the rest is dynamic, the same contract {@link
 * MirrorRepositoryRow} carries.
 *
 * @param host the registry a docker client names in an image reference — {@code docker.io}, {@code
 *     quay.io}, {@code registry.access.redhat.com}. It is the row's identity and what the miss path
 *     dials. Called {@code host} rather than {@code domain} because that is the word a puller uses.
 * @param namespace the local segment that fronts it: {@code docker pull <this service>/<namespace>/
 *     <image>:<tag>}. The other half of the pair — a namespace resolves to a repository row of type
 *     {@code oci-mirror} with this name.
 * @param cachedImages distinct image names cached under the namespace. One count query per row over
 *     three rows, which is why it is affordable. Zero is the normal state of a fresh upstream and
 *     says so — a mirror holds only what somebody pulled.
 * @param createdAt when the upstream was registered.
 */
public record OciUpstreamRow(
    String host, String namespace, long cachedImages, Instant createdAt) {}
