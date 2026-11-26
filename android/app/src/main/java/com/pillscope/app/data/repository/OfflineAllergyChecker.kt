package com.pillscope.app.data.repository

import com.pillscope.app.data.remote.dto.AllergyAlertDto
import com.pillscope.app.data.remote.dto.AllergyEntryDto
import kotlin.math.min

/**
 * On-device deterministic allergy cross-reactivity checker mirroring the backend safety classes,
 * so offline cabinet additions immediately surface allergy alerts before server sync.
 */
object OfflineAllergyChecker {
    private val drugClassMembers: Map<String, Set<String>> = mapOf(
        "penicillin" to setOf(
            "amoxicillin", "ampicillin", "penicillin", "penicillin g", "penicillin v",
            "piperacillin", "dicloxacillin", "flucloxacillin", "cloxacillin", "oxacillin",
        ),
        "cephalosporin" to setOf(
            "cephalexin", "cefadroxil", "cefazolin", "cefuroxime", "cefaclor",
            "cefixime", "ceftriaxone", "cefpodoxime", "cefdinir", "ceftazidime", "cefotaxime",
        ),
        "beta-lactam" to setOf(
            "amoxicillin", "ampicillin", "penicillin", "clavulanic acid", "cephalexin",
            "cefuroxime", "cefixime", "ceftriaxone", "meropenem", "imipenem", "aztreonam",
        ),
        "sulfonamide" to setOf(
            "sulfamethoxazole", "sulfadiazine", "sulfasalazine", "sulfacetamide",
            "co trimoxazole", "trimethoprim sulfamethoxazole", "dapsone", "celecoxib",
        ),
        "nsaid" to setOf(
            "aspirin", "ibuprofen", "diclofenac", "naproxen", "aceclofenac", "ketorolac",
            "indomethacin", "meloxicam", "piroxicam", "celecoxib", "etoricoxib",
            "mefenamic acid", "ketoprofen", "nimesulide",
        ),
        "salicylate" to setOf("aspirin", "salsalate", "diflunisal", "mesalamine", "sulfasalazine"),
        "fluoroquinolone" to setOf(
            "ciprofloxacin", "levofloxacin", "ofloxacin", "moxifloxacin", "norfloxacin",
        ),
        "macrolide" to setOf("azithromycin", "clarithromycin", "erythromycin", "roxithromycin"),
        "tetracycline" to setOf("doxycycline", "minocycline", "tetracycline"),
        "opioid" to setOf(
            "morphine", "codeine", "tramadol", "oxycodone", "hydrocodone",
            "fentanyl", "methadone", "buprenorphine", "tapentadol",
        ),
        "ace_inhibitor" to setOf("lisinopril", "enalapril", "ramipril", "captopril", "perindopril"),
        "arb" to setOf("losartan", "valsartan", "telmisartan", "olmesartan", "irbesartan", "candesartan"),
        "statin" to setOf("atorvastatin", "rosuvastatin", "simvastatin", "pravastatin", "lovastatin"),
        "ppi" to setOf("omeprazole", "pantoprazole", "esomeprazole", "rabeprazole", "lansoprazole"),
        "benzodiazepine" to setOf("alprazolam", "lorazepam", "diazepam", "clonazepam", "midazolam"),
        "ssri" to setOf("fluoxetine", "sertraline", "escitalopram", "citalopram", "paroxetine"),
    )

    private val classAliases: Map<String, String> = mapOf(
        "penicillin" to "penicillin",
        "penicillins" to "penicillin",
        "cephalosporin" to "cephalosporin",
        "cephalosporins" to "cephalosporin",
        "beta lactam" to "beta-lactam",
        "beta lactams" to "beta-lactam",
        "sulfa" to "sulfonamide",
        "sulfa drug" to "sulfonamide",
        "sulfa drugs" to "sulfonamide",
        "sulfonamide" to "sulfonamide",
        "sulfonamides" to "sulfonamide",
        "nsaid" to "nsaid",
        "nsaids" to "nsaid",
        "salicylate" to "salicylate",
        "salicylates" to "salicylate",
        "fluoroquinolone" to "fluoroquinolone",
        "fluoroquinolones" to "fluoroquinolone",
        "quinolone" to "fluoroquinolone",
        "macrolide" to "macrolide",
        "macrolides" to "macrolide",
        "tetracycline" to "tetracycline",
        "tetracyclines" to "tetracycline",
        "opioid" to "opioid",
        "opioids" to "opioid",
        "ace inhibitor" to "ace_inhibitor",
        "ace inhibitors" to "ace_inhibitor",
        "arb" to "arb",
        "arbs" to "arb",
        "statin" to "statin",
        "statins" to "statin",
        "ppi" to "ppi",
        "ppis" to "ppi",
        "benzodiazepine" to "benzodiazepine",
        "benzodiazepines" to "benzodiazepine",
        "ssri" to "ssri",
        "ssris" to "ssri",
    )

    private val nonAlnum = Regex("""[^a-z0-9]+""")

    fun detectConflicts(
        candidateSalts: List<String>,
        allergies: List<AllergyEntryDto>,
    ): List<AllergyAlertDto> {
        if (candidateSalts.isEmpty() || allergies.isEmpty()) return emptyList()
        val normSalts = candidateSalts.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()
        val alerts = mutableListOf<AllergyAlertDto>()
        val seen = mutableSetOf<Pair<String, String>>()

        for (entry in allergies) {
            val rawLabel = entry.allergen.trim()
            val normAllergen = normalize(rawLabel)
            if (normAllergen.isEmpty()) continue
            val classKey = resolveClassKey(normAllergen)
            val reactionSuffix = entry.reaction?.takeIf { it.isNotBlank() }
                ?.let { " (documented reaction: $it)" }
                .orEmpty()

            for (salt in normSalts) {
                val key = salt to normAllergen
                if (key in seen) continue
                if (salt == normAllergen || isCloseTypo(normAllergen, salt)) {
                    seen.add(key)
                    alerts.add(
                        AllergyAlertDto(
                            allergen = rawLabel,
                            canonicalSalt = salt,
                            matchType = "DIRECT_SALT",
                            matchedClass = null,
                            severity = "MAJOR",
                            reason = "Active salt '$salt' matches documented allergy '$rawLabel'$reactionSuffix.",
                        ),
                    )
                } else if (classKey != null && belongsToClass(salt, classKey)) {
                    seen.add(key)
                    alerts.add(
                        AllergyAlertDto(
                            allergen = rawLabel,
                            canonicalSalt = salt,
                            matchType = "DRUG_CLASS",
                            matchedClass = classKey,
                            severity = "MAJOR",
                            reason = "Active salt '$salt' belongs to the '$classKey' class matching allergy '$rawLabel'$reactionSuffix.",
                        ),
                    )
                }
            }
        }
        return alerts
    }

    private fun normalize(raw: String): String =
        nonAlnum.replace(raw.lowercase(), " ").trim().replace(Regex("""\s+"""), " ")

    private fun resolveClassKey(normAllergen: String): String? {
        classAliases[normAllergen]?.let { return it }
        if (normAllergen.length < 5) return null
        val match = classAliases.keys.firstOrNull { alias -> isCloseTypo(normAllergen, alias) }
        return match?.let { classAliases[it] }
    }

    private fun belongsToClass(salt: String, classKey: String): Boolean {
        if (salt in drugClassMembers[classKey].orEmpty()) return true
        return when (classKey) {
            "penicillin" -> salt.endsWith("cillin")
            "cephalosporin" -> salt.startsWith("cef") || salt.startsWith("ceph")
            "fluoroquinolone" -> salt.endsWith("floxacin")
            "ace_inhibitor" -> salt.endsWith("pril")
            "arb" -> salt.endsWith("sartan")
            "statin" -> salt.endsWith("statin") && salt !in setOf("nystatin", "cilastatin", "somatostatin")
            "ppi" -> salt.endsWith("prazole") && salt !in setOf("aripiprazole", "brexpiprazole")
            else -> false
        }
    }

    private fun isCloseTypo(a: String, b: String): Boolean {
        if (a.length < 5 || b.length < 5) return false
        val maxLen = maxOf(a.length, b.length)
        val dist = levenshtein(a, b)
        return dist <= 1 || (maxLen >= 8 && dist <= 2)
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            curr.copyInto(prev)
        }
        return prev[b.length]
    }
}
