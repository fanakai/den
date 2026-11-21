package ai.fanak.den.core.backend

import ai.fanak.den.core.model.ModelFormat
import kotlinx.serialization.Serializable

// CORE: in this repository, built and tested in CI and on phones. COMMUNITY: maintained outside. EXPERIMENTAL: may
// change or go away.
@Serializable
enum class SupportTier { CORE, COMMUNITY, EXPERIMENTAL }

@Serializable
enum class BackendMode { CHAT, EMBEDDINGS }

// Where a backend runs the model: on this phone, on the local network (a PC or board), or in the cloud.
@Serializable
enum class Location { DEVICE, LAN, CLOUD }

/**
 * How the pool shares a backend's accelerator. STANDARD: models load while RAM allows. EXCLUSIVE_ACCEL: the
 * accelerator holds one model at a time whatever the free RAM (some NPUs), so the idle one unloads first. UNMETERED:
 * runs elsewhere (a remote server), outside the phone's RAM.
 */
@Serializable
enum class SlotPolicy { STANDARD, EXCLUSIVE_ACCEL, UNMETERED }

/** What a backend is, as plain data: what the router, the pool and the Den screen read instead of its id. */
@Serializable
data class BackendDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val licence: String,
    val url: String,
    val tier: SupportTier,
    val modes: Set<BackendMode>,
    val formats: Set<ModelFormat>,
    val slots: SlotPolicy = SlotPolicy.STANDARD,
    val location: Location = Location.DEVICE,
) {
    // The fields left empty; a descriptor with any is incomplete.
    fun missing(): List<String> = buildList {
        if (id.isBlank()) add("id")
        if (name.isBlank()) add("name")
        if (version.isBlank()) add("version")
        if (author.isBlank()) add("author")
        if (licence.isBlank()) add("licence")
        if (!url.startsWith("https://")) add("url")
        if (modes.isEmpty()) add("modes")
        if (formats.isEmpty()) add("formats")
    }
}
