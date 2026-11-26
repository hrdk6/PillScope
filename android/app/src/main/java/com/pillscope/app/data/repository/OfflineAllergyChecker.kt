package com.pillscope.app.data.repository

import com.pillscope.app.data.remote.dto.AllergyAlertDto
import com.pillscope.app.data.remote.dto.AllergyEntryDto
import kotlin.math.min

/**
 * On-device deterministic allergy cross-reactivity checker mirroring all 25 backend drug classes
 * in backend/app/services/allergy_checker.py so offline cabinet additions immediately surface
 * accurate allergy alerts before server sync.
 */
object OfflineAllergyChecker {
    private val drugClassMembers: Map<String, Set<String>> = mapOf(
        "penicillin" to setOf(
            "amoxicillin", "ampicillin", "penicillin", "penicillin g", "penicillin v",
            "benzylpenicillin", "phenoxymethylpenicillin", "piperacillin", "dicloxacillin",
            "flucloxacillin", "cloxacillin", "oxacillin", "nafcillin", "ticarcillin",
            "bacampicillin", "pivampicillin", "sultamicillin",
        ),
        "cephalosporin" to setOf(
            "cephalexin", "cefadroxil", "cefazolin", "cefuroxime", "cefaclor", "cefprozil",
            "cefixime", "ceftriaxone", "cefpodoxime", "cefdinir", "ceftazidime", "cefotaxime",
            "cefepime", "ceftaroline", "cefoperazone", "cefditorin", "cefditoren",
        ),
        "carbapenem" to setOf(
            "meropenem", "imipenem", "ertapenem", "doripenem", "faropenem",
        ),
        "beta-lactam" to setOf(
            "amoxicillin", "ampicillin", "penicillin", "penicillin g", "penicillin v",
            "benzylpenicillin", "phenoxymethylpenicillin", "piperacillin", "dicloxacillin",
            "flucloxacillin", "cloxacillin", "oxacillin", "nafcillin", "ticarcillin",
            "clavulanic acid", "sulbactam", "tazobactam",
            "cephalexin", "cefadroxil", "cefazolin", "cefuroxime", "cefaclor", "cefprozil",
            "cefixime", "ceftriaxone", "cefpodoxime", "cefdinir", "ceftazidime", "cefotaxime",
            "cefepime", "ceftaroline", "meropenem", "imipenem", "ertapenem", "doripenem",
            "faropenem", "aztreonam",
        ),
        "sulfonamide" to setOf(
            "sulfamethoxazole", "sulfadiazine", "sulfisoxazole", "sulfasalazine", "sulfacetamide",
            "sulfadoxine", "mafenide", "silver sulfadiazine", "co trimoxazole",
            "trimethoprim sulfamethoxazole", "dapsone", "celecoxib",
        ),
        "nsaid" to setOf(
            "aspirin", "ibuprofen", "diclofenac", "naproxen", "aceclofenac", "ketorolac",
            "indomethacin", "meloxicam", "piroxicam", "celecoxib", "etoricoxib",
            "mefenamic acid", "ketoprofen", "flurbiprofen", "etodolac", "nabumetone",
            "oxaprozin", "sulindac", "nimesulide", "dexketoprofen", "lornoxicam",
            "tenoxicam", "parecoxib",
        ),
        "salicylate" to setOf(
            "aspirin", "salsalate", "diflunisal", "bismuth subsalicylate",
            "mesalamine", "mesalazine", "sulfasalazine", "methyl salicylate",
        ),
        "fluoroquinolone" to setOf(
            "ciprofloxacin", "levofloxacin", "ofloxacin", "moxifloxacin", "norfloxacin",
            "gatifloxacin", "gemifloxacin", "delafloxacin", "lomefloxacin", "sparfloxacin",
            "prulifloxacin", "pazufloxacin",
        ),
        "macrolide" to setOf(
            "azithromycin", "clarithromycin", "erythromycin", "roxithromycin",
            "fidaxomicin", "spiramycin", "telithromycin",
        ),
        "tetracycline" to setOf(
            "doxycycline", "minocycline", "tetracycline", "demeclocycline", "tigecycline",
            "omadacycline", "sarecycline", "eravacycline", "lymecycline", "oxytetracycline",
        ),
        "opioid" to setOf(
            "morphine", "codeine", "tramadol", "oxycodone", "hydrocodone", "hydromorphone",
            "fentanyl", "meperidine", "methadone", "buprenorphine", "tapentadol",
            "oxymorphone", "pentazocine", "nalbuphine", "butorphanol",
        ),
        "ace_inhibitor" to setOf(
            "lisinopril", "enalapril", "ramipril", "captopril", "perindopril",
            "benazepril", "fosinopril", "quinapril", "trandolapril", "moexipril", "imidapril",
        ),
        "arb" to setOf(
            "losartan", "valsartan", "telmisartan", "olmesartan", "irbesartan",
            "candesartan", "azilsartan", "eprosartan",
        ),
        "statin" to setOf(
            "atorvastatin", "rosuvastatin", "simvastatin", "pravastatin", "lovastatin",
            "fluvastatin", "pitavastatin",
        ),
        "ppi" to setOf(
            "omeprazole", "pantoprazole", "esomeprazole", "rabeprazole", "lansoprazole",
            "dexlansoprazole", "ilaprazole",
        ),
        "aminoglycoside" to setOf(
            "gentamicin", "amikacin", "tobramycin", "streptomycin", "neomycin",
            "kanamycin", "plazomicin", "netilmicin",
        ),
        "nitroimidazole" to setOf(
            "metronidazole", "tinidazole", "ornidazole", "secnidazole", "satranidazole",
        ),
        "aromatic_anticonvulsant" to setOf(
            "phenytoin", "carbamazepine", "oxcarbazepine", "phenobarbital", "lamotrigine", "primidone",
        ),
        "beta_blocker" to setOf(
            "metoprolol", "atenolol", "propranolol", "bisoprolol", "carvedilol",
            "nebivolol", "labetalol", "nadolol", "sotalol", "timolol", "esmolol",
            "pindolol", "betaxolol",
        ),
        "corticosteroid" to setOf(
            "prednisone", "prednisolone", "methylprednisolone", "dexamethasone", "hydrocortisone",
            "betamethasone", "triamcinolone", "budesonide", "fluticasone", "mometasone",
            "beclomethasone", "deflazacort",
        ),
        "benzodiazepine" to setOf(
            "alprazolam", "lorazepam", "diazepam", "clonazepam", "midazolam",
            "temazepam", "oxazepam", "chlordiazepoxide", "nitrazepam", "clobazam", "etizolam",
        ),
        "ssri" to setOf(
            "fluoxetine", "sertraline", "escitalopram", "citalopram", "paroxetine", "fluvoxamine",
        ),
        "antihistamine" to setOf(
            "cetirizine", "levocetirizine", "loratadine", "desloratadine", "fexofenadine",
            "diphenhydramine", "chlorpheniramine", "hydroxyzine", "promethazine",
            "bilastine", "ebastine",
        ),
        "local_anesthetic_amide" to setOf(
            "lidocaine", "bupivacaine", "ropivacaine", "mepivacaine", "prilocaine", "articaine",
        ),
        "local_anesthetic_ester" to setOf(
            "procaine", "benzocaine", "tetracaine", "chloroprocaine",
        ),
    )

    private val classAliases: Map<String, String> = mapOf(
        "penicillin" to "penicillin",
        "penicillins" to "penicillin",
        "penicillin antibiotics" to "penicillin",
        "penicillin allergy" to "penicillin",
        "cephalosporin" to "cephalosporin",
        "cephalosporins" to "cephalosporin",
        "cefalosporin" to "cephalosporin",
        "cefalosporins" to "cephalosporin",
        "carbapenem" to "carbapenem",
        "carbapenems" to "carbapenem",
        "beta lactam" to "beta-lactam",
        "beta lactams" to "beta-lactam",
        "beta lactam antibiotics" to "beta-lactam",
        "betalactam" to "beta-lactam",
        "sulfa" to "sulfonamide",
        "sulfa drug" to "sulfonamide",
        "sulfa drugs" to "sulfonamide",
        "sulfa antibiotics" to "sulfonamide",
        "sulfonamide" to "sulfonamide",
        "sulfonamides" to "sulfonamide",
        "sulphonamide" to "sulfonamide",
        "sulphonamides" to "sulfonamide",
        "cotrimoxazole" to "sulfonamide",
        "nsaid" to "nsaid",
        "nsaids" to "nsaid",
        "nonsteroidal anti inflammatory" to "nsaid",
        "nonsteroidal anti inflammatory drugs" to "nsaid",
        "non steroidal anti inflammatory" to "nsaid",
        "salicylate" to "salicylate",
        "salicylates" to "salicylate",
        "fluoroquinolone" to "fluoroquinolone",
        "fluoroquinolones" to "fluoroquinolone",
        "quinolone" to "fluoroquinolone",
        "quinolones" to "fluoroquinolone",
        "macrolide" to "macrolide",
        "macrolides" to "macrolide",
        "tetracycline" to "tetracycline",
        "tetracyclines" to "tetracycline",
        "opioid" to "opioid",
        "opioids" to "opioid",
        "opiate" to "opioid",
        "opiates" to "opioid",
        "narcotic" to "opioid",
        "narcotics" to "opioid",
        "ace inhibitor" to "ace_inhibitor",
        "ace inhibitors" to "ace_inhibitor",
        "acei" to "ace_inhibitor",
        "arb" to "arb",
        "arbs" to "arb",
        "angiotensin receptor blocker" to "arb",
        "angiotensin receptor blockers" to "arb",
        "statin" to "statin",
        "statins" to "statin",
        "hmg coa reductase inhibitor" to "statin",
        "ppi" to "ppi",
        "ppis" to "ppi",
        "proton pump inhibitor" to "ppi",
        "proton pump inhibitors" to "ppi",
        "aminoglycoside" to "aminoglycoside",
        "aminoglycosides" to "aminoglycoside",
        "nitroimidazole" to "nitroimidazole",
        "nitroimidazoles" to "nitroimidazole",
        "aromatic anticonvulsant" to "aromatic_anticonvulsant",
        "aromatic anticonvulsants" to "aromatic_anticonvulsant",
        "beta blocker" to "beta_blocker",
        "beta blockers" to "beta_blocker",
        "corticosteroid" to "corticosteroid",
        "corticosteroids" to "corticosteroid",
        "steroid" to "corticosteroid",
        "steroids" to "corticosteroid",
        "glucocorticoid" to "corticosteroid",
        "benzodiazepine" to "benzodiazepine",
        "benzodiazepines" to "benzodiazepine",
        "ssri" to "ssri",
        "ssris" to "ssri",
        "selective serotonin reuptake inhibitor" to "ssri",
        "antihistamine" to "antihistamine",
        "antihistamines" to "antihistamine",
        "amide anesthetic" to "local_anesthetic_amide",
        "amide local anesthetic" to "local_anesthetic_amide",
        "ester anesthetic" to "local_anesthetic_ester",
        "ester local anesthetic" to "local_anesthetic_ester",
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
            "carbapenem" -> salt.endsWith("penem")
            "fluoroquinolone" -> salt.endsWith("floxacin")
            "ace_inhibitor" -> salt.endsWith("pril")
            "arb" -> salt.endsWith("sartan")
            "statin" -> salt.endsWith("statin") && salt !in setOf("nystatin", "cilastatin", "somatostatin")
            "ppi" -> salt.endsWith("prazole") && salt !in setOf("aripiprazole", "brexpiprazole")
            "beta_blocker" -> salt.endsWith("olol")
            "benzodiazepine" -> salt.endsWith("pam") || salt.endsWith("lam")
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
