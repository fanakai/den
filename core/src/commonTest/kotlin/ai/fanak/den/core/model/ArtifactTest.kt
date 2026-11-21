package ai.fanak.den.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtifactTest {
    private val v = Variant("m", ModelFormat.GGUF, "q4_0", 1, 1)

    @Test
    fun oneFileOrSeveralByRole() {
        assertEquals("/m/model.gguf", Artifact(v, "/m/model.gguf").path)
        val vlm = Artifact(v, mapOf(FileRole.MODEL to "/m/model.gguf", FileRole.PROJECTOR to "/m/mmproj.gguf"))
        assertEquals("/m/mmproj.gguf", vlm.file(FileRole.PROJECTOR))
        assertEquals(null, vlm.file(FileRole.TOKENIZER))
        // Sent to the engine process as JSON, the roles by name.
        val json = Json.encodeToString(Artifact.serializer(), vlm)
        assertEquals(true, "\"projector\":\"/m/mmproj.gguf\"" in json, json)
        assertEquals(vlm, Json.decodeFromString(Artifact.serializer(), json))
        assertFailsWith<IllegalArgumentException> { Artifact(v, mapOf(FileRole.TOKENIZER to "/t")) }
    }
}
