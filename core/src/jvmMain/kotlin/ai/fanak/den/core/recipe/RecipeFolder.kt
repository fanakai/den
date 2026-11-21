package ai.fanak.den.core.recipe

import java.io.File

// Every *.yaml under dir, e.g. recipes/SM8750.yaml.
fun RecipeFiles.load(dir: File): List<Recipe> =
    dir.walkTopDown()
        .filter { it.isFile && it.extension == "yaml" }
        .sortedBy { it.path }
        .map { parse(it.readText(), it.relativeTo(dir).path) }
        .toList()
