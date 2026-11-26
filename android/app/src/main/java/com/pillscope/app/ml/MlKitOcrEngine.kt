package com.pillscope.app.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable

object OnDeviceSaltChipExtractor {
    private val saltSynonyms: Map<String, String> = mapOf(
        "amoxycillin" to "amoxicillin",
        "paracetamol" to "acetaminophen",
        "clavulanate" to "clavulanic acid",
        "clavulanate potassium" to "clavulanic acid",
        "potassium clavulanate" to "clavulanic acid",
        "salbutamol" to "albuterol",
        "adrenaline" to "epinephrine",
        "noradrenaline" to "norepinephrine",
        "lignocaine" to "lidocaine",
        "frusemide" to "furosemide",
        "phenobarbitone" to "phenobarbital",
        "glibenclamide" to "glyburide",
        "pethidine" to "meperidine",
        "cefalexin" to "cephalexin",
        "acetylsalicylic acid" to "aspirin",
        "cholecalciferol" to "vitamin d3",
        "colecalciferol" to "vitamin d3",
        "cyanocobalamin" to "vitamin b12",
        "methylcobalamin" to "mecobalamin",
        "folic acid" to "folate",
        "esomperazole" to "esomeprazole",
    )

    private val preservedInorganic: Set<String> = setOf(
        "calcium carbonate",
        "calcium citrate",
        "calcium gluconate",
        "potassium chloride",
        "sodium chloride",
        "sodium bicarbonate",
        "magnesium hydroxide",
        "ferrous sulfate",
        "ferrous sulphate",
        "ferrous fumarate",
        "zinc sulfate",
        "zinc sulphate",
        "aluminum hydroxide",
        "aluminium hydroxide",
        "lithium carbonate",
    )

    private val counterionSuffixes: Set<String> = setOf(
        "dihydrochloride", "hydrochloride", "hydrobromide", "hcl",
        "sodium", "potassium", "calcium", "magnesium",
        "maleate", "tartrate", "bitartrate", "succinate", "fumarate", "hemifumarate",
        "besylate", "besilate", "mesylate", "mesilate", "tosylate",
        "bisulfate", "bisulphate", "sulfate", "sulphate", "phosphate",
        "citrate", "acetate", "nitrate", "bromide", "chloride", "gluconate",
        "trihydrate", "dihydrate", "monohydrate", "hemihydrate", "anhydrous", "hydrate",
        "ip", "bp", "usp", "sr", "xr", "er", "pr", "dr", "cr", "ec", "mr", "dt",
    )

    private val noiseWords: Set<String> = setOf(
        "each", "tablet", "tablets", "capsule", "capsules", "film", "coated",
        "contains", "equivalent", "eq", "to", "elemental", "excipients", "and",
        "strip", "pack", "oral", "suspension", "syrup", "drops", "solution",
        "dosage", "store", "keep", "batch", "mfg", "exp", "mrp", "net", "qty",
        "schedule", "prescription", "drug", "caution", "warning", "composition",
        "uncoated", "dispersible", "chewable", "delayed", "extended", "release",
    )

    private val nonAlnumRegex = Regex("""[^a-z0-9]+""")

    private val genericSaltSegmentRegex = Regex(
        """(?<name>[A-Za-z][A-Za-z0-9\-\s]{2,55}?)\s*(?:\(\s*(?<pval>\d+(?:\.\d+)?)\s*(?<punit>mg|mcg|ug|g|gm|ml|iu|%)\s*\)|(?<val>\d+(?:\.\d+)?)\s*(?<unit>mg|mcg|ug|g|gm|ml|iu|%)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private fun normalizeCanonicalSalt(rawSalt: String): String {
        val cleaned = nonAlnumRegex.replace(rawSalt.lowercase(), " ").trim().replace(Regex("""\s+"""), " ")
        if (cleaned.isEmpty()) return ""
        saltSynonyms[cleaned]?.let { return it }
        if (cleaned in preservedInorganic) {
            return cleaned.replace("sulphate", "sulfate").replace("aluminium", "aluminum")
        }

        val tokens = cleaned.split(" ").toMutableList()
        while (tokens.size > 1 && tokens.last() in counterionSuffixes) {
            val candidate = tokens.dropLast(1).joinToString(" ")
            saltSynonyms[candidate]?.let { return it }
            tokens.removeAt(tokens.lastIndex)
        }
        while (tokens.size > 1 && tokens.first() in setOf("sodium", "potassium", "calcium", "magnesium")) {
            val remainder = tokens.drop(1).joinToString(" ")
            saltSynonyms[remainder]?.let { return it }
            if ("${tokens.first()} $remainder" in preservedInorganic) break
            tokens.removeAt(0)
        }
        val joined = tokens.joinToString(" ")
        return saltSynonyms[joined] ?: joined
    }

    private fun extractSaltFromTokens(tokens: List<String>): String {
        val filtered = tokens
            .filter { it !in noiseWords && !it.all { ch -> ch.isDigit() } }
            .toMutableList()
        if (filtered.isEmpty()) return ""

        if (filtered.size >= 3) {
            val lastThree = filtered.takeLast(3).joinToString(" ")
            saltSynonyms[lastThree]?.let { return it }
        }
        if (filtered.size >= 2) {
            val lastTwo = filtered.takeLast(2).joinToString(" ")
            saltSynonyms[lastTwo]?.let { return it }
            if (lastTwo in preservedInorganic) {
                return normalizeCanonicalSalt(lastTwo)
            }
        }

        while (filtered.size > 1 && filtered.last() in counterionSuffixes) {
            val candidateTwo = if (filtered.size >= 2) filtered.takeLast(2).joinToString(" ") else ""
            if (candidateTwo in saltSynonyms) return saltSynonyms.getValue(candidateTwo)
            if (candidateTwo in preservedInorganic) return normalizeCanonicalSalt(candidateTwo)
            filtered.removeAt(filtered.lastIndex)
        }

        if (filtered.size >= 2) {
            val lastTwo = filtered.takeLast(2).joinToString(" ")
            if (
                lastTwo in saltSynonyms ||
                lastTwo in preservedInorganic ||
                filtered.last() == "acid" ||
                filtered[filtered.size - 2] in setOf("vitamin", "sodium", "potassium", "calcium", "magnesium", "ferrous", "clavulanic")
            ) {
                return normalizeCanonicalSalt(lastTwo)
            }
        }
        return normalizeCanonicalSalt(filtered.last())
    }

    fun extractSaltChips(rawOcrText: String): List<LocalSaltChip> {
        if (rawOcrText.isBlank()) return emptyList()
        val found = linkedMapOf<String, LocalSaltChip>()
        for (match in genericSaltSegmentRegex.findAll(rawOcrText)) {
            val rawName = match.groups["name"]?.value?.trim().orEmpty()
            val value = match.groups["pval"]?.value ?: match.groups["val"]?.value ?: continue
            val unit = match.groups["punit"]?.value ?: match.groups["unit"]?.value ?: continue
            val nameTokens = nonAlnumRegex.replace(rawName.lowercase(), " ")
                .split(" ")
                .filter { it.isNotEmpty() }
            if (nameTokens.isEmpty()) continue
            val canonical = extractSaltFromTokens(nameTokens)
            if (canonical.length < 3 || canonical in noiseWords || canonical in counterionSuffixes) {
                continue
            }
            if (!found.containsKey(canonical)) {
                found[canonical] = LocalSaltChip(
                    canonicalSalt = canonical,
                    strengthText = "${value}${unit.lowercase()}",
                )
            }
        }
        return found.values.toList()
    }

    fun extractPrimaryBrandGuess(rawOcrText: String): String {
        val firstLine = rawOcrText
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { line -> line.length >= 3 && line.any { it.isLetter() } }
            ?: return ""
        return firstLine.take(64)
    }
}

class MlKitOcrEngine : Closeable {
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS,
    )

    suspend fun recognizeText(bitmap: Bitmap, rotationDegrees: Int = 0): String {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        val result = recognizer.process(image).await()
        return result.text.trim()
    }

    override fun close() {
        runCatching { recognizer.close() }
    }
}
