package io.odxproxy.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// --- ODXBrain (ODXProxy 0.10.0+, /brain/v1/...) ---
//
// The result types are views over ODXBrain's response, which is kept as [raw] exactly as
// received. ODXBrain's /v1 is additive, so a field this SDK doesn't know yet is still in
// `raw`. The typed accessors only read; nothing is filtered, sorted or thresholded.

/** Odoo edition, as ODXBrain spells it on the wire. */
public enum class OdxBrainEdition(public val wireValue: String) {
    COMMUNITY("community"),
    ENTERPRISE("enterprise");

    public companion object {
        /** `ENTERPRISE` iff an Odoo version string carries `+e` (e.g. `"20.0+e-20260925"`). */
        @JvmStatic
        public fun fromOdooVersion(version: String): OdxBrainEdition =
            if (version.contains("+e")) ENTERPRISE else COMMUNITY
    }
}

/**
 * Facts about an Odoo instance that ODXBrain filters on. **A `null` fact is omitted from
 * the request and filters nothing.** Never guess or default one: a wrong version silently
 * removes correct answers. Get them from [io.odxproxy.OdxBrain.facts].
 */
public data class OdxBrainFacts @JvmOverloads public constructor(
    /** Odoo's serie, e.g. `"19.0"` or `"saas~16.3"`. */
    public val odooVersion: String? = null,
    public val edition: OdxBrainEdition? = null
)

/** Where [io.odxproxy.OdxBrain.facts] reads the Odoo version from. */
public enum class OdxBrainFactsSource {
    /** `/v2/odoo/version`, falling back to v1 when the instance has no JSON-2 (Odoo 18 or older). */
    AUTO,
    /** `/api/odoo/version` (`server_serie`, `server_version`). */
    V1,
    /** `/v2/odoo/version` (`version_info`, `version`). */
    V2
}

/** An entry of `excluded[]`: something that would have ranked but doesn't apply on this instance. */
public class OdxBrainExcluded(public val raw: JsonObject) {
    /** Set for a model exclusion (`/brain/v1/resolve`). */
    public val model: String? get() = raw.string("model")
    /** Set for an SOP exclusion (`/brain/v1/sops/resolve`). */
    public val id: String? get() = raw.string("id")
    public val reason: String? get() = raw.string("reason")
    /** The model that replaces [model] on this Odoo version, when there is one. */
    public val successor: String? get() = raw.string("successor")
    override fun toString(): String = raw.toString()
}

/** One ranked model. Use every candidate, in order; never threshold on [score]. */
public class OdxBrainModelCandidate(public val raw: JsonObject) {
    public val model: String? get() = raw.string("model")
    /** Comparable only within one response. The ordering is the contract, the scale is not. */
    public val score: Double? get() = (raw["score"] as? JsonPrimitive)?.doubleOrNull
    /** The model's availability rests on inference, not audited source. */
    public val isProvisional: Boolean get() = (raw["provisional"] as? JsonPrimitive)?.booleanOrNull == true
    override fun toString(): String = raw.toString()
}

/**
 * Result of `POST /brain/v1/resolve` ([io.odxproxy.OdxBrain.resolveModel]). [raw] is
 * ODXBrain's result, unaltered.
 */
public class OdxBrainModelResult(public val raw: JsonObject) {
    /** Every candidate, best first. */
    public val candidates: List<OdxBrainModelCandidate>
        get() = raw.objects("candidates").map { OdxBrainModelCandidate(it) }
    /** Embedding model id (e.g. `bge-m3`). Persist it with [dim] and [corpusVersion] to detect a swap. */
    public val model: String? get() = raw.string("model")
    public val dim: Int? get() = (raw["dim"] as? JsonPrimitive)?.intOrNull
    public val corpusVersion: String? get() = raw.string("corpus_version")
    public val usage: JsonObject? get() = raw["usage"] as? JsonObject
    /** Models that would have ranked but don't exist on that Odoo version or edition. Empty when absent. */
    public val excluded: List<OdxBrainExcluded> get() = raw.objects("excluded").map { OdxBrainExcluded(it) }
    /** `false` when neither `odoo_version` nor `edition` was sent. `null` when absent. */
    public val filtered: Boolean? get() = (raw["filtered"] as? JsonPrimitive)?.booleanOrNull
    /**
     * Set when the `odoo_version` sent is newer than the audited range. Its value is the
     * newest **audited** version (e.g. `"19.0"` for a 20.0 instance), not the one sent.
     */
    public val versionUnaudited: String? get() = raw.string("version_unaudited")
    override fun toString(): String = raw.toString()
}

/** One ranked SOP. Check [title] against the task before acting: embeddings match the topic, not the operation. */
public class OdxBrainSopCandidate(public val raw: JsonObject) {
    public val id: String? get() = raw.string("id")
    /** Opaque (in practice a content hash): compare it, never parse it. */
    public val version: String? get() = raw.string("version")
    public val title: String? get() = raw.string("title")
    /** Comparable only within one response. The ordering is the contract, the scale is not. */
    public val score: Double? get() = (raw["score"] as? JsonPrimitive)?.doubleOrNull
    override fun toString(): String = raw.toString()
}

/** `sops` of an SOP result: the approved SOP set the answer came from. */
public class OdxBrainSopSet(public val raw: JsonObject) {
    public val generation: Long? get() = (raw["generation"] as? JsonPrimitive)?.longOrNull
    /** Ties an agent's action to the approved SOP set; log it with every run. */
    public val root: String? get() = raw.string("root")
    override fun toString(): String = raw.toString()
}

/**
 * Result of `POST /brain/v1/sops/resolve` ([io.odxproxy.OdxBrain.resolveSop]). [raw] is
 * ODXBrain's result, unaltered. Check [isNoMatch] before acting.
 */
public class OdxBrainSopResult(public val raw: JsonObject) {
    /** Every candidate, best first. */
    public val candidates: List<OdxBrainSopCandidate>
        get() = raw.objects("candidates").map { OdxBrainSopCandidate(it) }
    public val model: String? get() = raw.string("model")
    public val dim: Int? get() = (raw["dim"] as? JsonPrimitive)?.intOrNull
    public val sops: OdxBrainSopSet? get() = (raw["sops"] as? JsonObject)?.let { OdxBrainSopSet(it) }
    public val usage: JsonObject? get() = raw["usage"] as? JsonObject
    /** `"none"` when no SOP is a confident match. `null` when absent. */
    public val match: String? get() = raw.string("match")
    /** `match == "none"`: **stop and ask the user**; don't run the nearest procedure. */
    public val isNoMatch: Boolean get() = match == "none"
    /** SOPs filtered out on this instance. Empty when absent. */
    public val excluded: List<OdxBrainExcluded> get() = raw.objects("excluded").map { OdxBrainExcluded(it) }
    /** Facts the caller didn't send. Empty when absent. */
    public val factsMissing: List<String>
        get() = (raw["facts_missing"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    override fun toString(): String = raw.toString()
}

private fun JsonObject.string(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

private fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

