package ai.fanak.den.core.model

/**
 * Classes of model licences, by the ids hubs use (Hugging Face `license`), for apps that must refuse some, e.g.
 * `DenConfig(allowLicence = { !Licences.nonCommercial(it) })`.
 */
object Licences {
    private val NON_COMMERCIAL = Regex("""(^|-)nc(-|$)|non-?commercial|research""", RegexOption.IGNORE_CASE)

    // Licences that bind the user to a use policy or a model-specific agreement.
    private val USE_POLICY = Regex("""^(llama\d|gemma|health-ai-developer-foundations|openrail|bigscience|creativeml)""", RegexOption.IGNORE_CASE)

    fun nonCommercial(id: String?) = id != null && NON_COMMERCIAL.containsMatchIn(id)

    fun usePolicy(id: String?) = id != null && USE_POLICY.containsMatchIn(id)

    // No licence given, or "other": the terms are only in the model card.
    fun unknown(id: String?) = id.isNullOrBlank() || id.equals("other", ignoreCase = true)
}
