package com.sam.twintracker.data

/**
 * A default infant immunisation timeline (birth – 18 months) modelled on
 * common WHO / IAP style schedules. It is only a starting point: doses can be
 * added, edited or removed in the app — always follow your paediatrician.
 */
object VaccineCatalog {

    data class Vaccine(val key: String, val name: String, val keywords: List<String>)
    data class ScheduledDose(val key: String, val dose: String, val ageDays: Int, val ageLabel: String)

    val vaccines = listOf(
        Vaccine("bcg", "BCG", listOf("bcg", "b c g", "tuberculosis")),
        Vaccine("opv", "OPV (oral polio)", listOf("opv", "oral polio", "polio drops", "polio drop")),
        Vaccine("hepb", "Hepatitis B", listOf("hep b", "hepatitis b", "hepb", "hbv")),
        Vaccine("dtap", "DTaP / DTwP", listOf("dtap", "dtp", "dpt", "dtwp", "diphtheria", "tetanus", "pertussis", "pentavalent", "triple antigen")),
        Vaccine("ipv", "IPV (polio injection)", listOf("ipv", "polio injection", "polio shot", "inactivated polio")),
        Vaccine("hib", "Hib", listOf("hib", "h i b", "haemophilus", "hemophilus")),
        Vaccine("rota", "Rotavirus", listOf("rota", "rotavirus", "rota virus")),
        Vaccine("pcv", "PCV (pneumococcal)", listOf("pcv", "pneumococcal", "prevnar")),
        Vaccine("flu", "Influenza", listOf("flu", "influenza")),
        Vaccine("mmr", "MMR", listOf("mmr", "measles", "mumps", "rubella", "m m r")),
        Vaccine("typhoid", "Typhoid conjugate", listOf("typhoid", "tcv")),
        Vaccine("hepa", "Hepatitis A", listOf("hep a", "hepatitis a", "hepa")),
        Vaccine("varicella", "Varicella", listOf("varicella", "chickenpox", "chicken pox")),
    )

    /** Combination vaccines → the component keys they cover. */
    val combos = mapOf(
        "hexavalent" to listOf("dtap", "ipv", "hib", "hepb"),
        "six in one" to listOf("dtap", "ipv", "hib", "hepb"),
        "6 in 1" to listOf("dtap", "ipv", "hib", "hepb"),
        "hexaxim" to listOf("dtap", "ipv", "hib", "hepb"),
        "infanrix hexa" to listOf("dtap", "ipv", "hib", "hepb"),
        "pentavalent" to listOf("dtap", "hib", "hepb"),
        "five in one" to listOf("dtap", "hib", "hepb"),
        "5 in 1" to listOf("dtap", "hib", "hepb"),
        "mmrv" to listOf("mmr", "varicella"),
    )

    val defaultSchedule = listOf(
        ScheduledDose("bcg", "BCG", 0, "Birth"),
        ScheduledDose("opv", "OPV 0", 0, "Birth"),
        ScheduledDose("hepb", "Hepatitis B 1", 0, "Birth"),

        ScheduledDose("dtap", "DTaP 1", 42, "6 weeks"),
        ScheduledDose("ipv", "IPV 1", 42, "6 weeks"),
        ScheduledDose("hib", "Hib 1", 42, "6 weeks"),
        ScheduledDose("hepb", "Hepatitis B 2", 42, "6 weeks"),
        ScheduledDose("rota", "Rotavirus 1", 42, "6 weeks"),
        ScheduledDose("pcv", "PCV 1", 42, "6 weeks"),

        ScheduledDose("dtap", "DTaP 2", 70, "10 weeks"),
        ScheduledDose("ipv", "IPV 2", 70, "10 weeks"),
        ScheduledDose("hib", "Hib 2", 70, "10 weeks"),
        ScheduledDose("rota", "Rotavirus 2", 70, "10 weeks"),
        ScheduledDose("pcv", "PCV 2", 70, "10 weeks"),

        ScheduledDose("dtap", "DTaP 3", 98, "14 weeks"),
        ScheduledDose("ipv", "IPV 3", 98, "14 weeks"),
        ScheduledDose("hib", "Hib 3", 98, "14 weeks"),
        ScheduledDose("hepb", "Hepatitis B 3", 98, "14 weeks"),
        ScheduledDose("rota", "Rotavirus 3", 98, "14 weeks"),
        ScheduledDose("pcv", "PCV 3", 98, "14 weeks"),

        ScheduledDose("flu", "Influenza 1", 182, "6 months"),
        ScheduledDose("flu", "Influenza 2", 213, "7 months"),
        ScheduledDose("mmr", "MMR 1", 274, "9 months"),
        ScheduledDose("typhoid", "Typhoid conjugate", 274, "9 months"),
        ScheduledDose("hepa", "Hepatitis A 1", 365, "12 months"),
        ScheduledDose("pcv", "PCV booster", 456, "15 months"),
        ScheduledDose("mmr", "MMR 2", 456, "15 months"),
        ScheduledDose("varicella", "Varicella 1", 456, "15 months"),
        ScheduledDose("dtap", "DTaP booster 1", 547, "18 months"),
        ScheduledDose("ipv", "IPV booster", 547, "18 months"),
        ScheduledDose("hib", "Hib booster", 547, "18 months"),
        ScheduledDose("hepa", "Hepatitis A 2", 547, "18 months"),
    )

    fun nameFor(key: String) = vaccines.firstOrNull { it.key == key }?.name ?: key

    /** Returns vaccine keys mentioned in free text (already lower-cased). */
    fun match(text: String): List<String> {
        val t = " ${text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ")} "
        val found = linkedSetOf<String>()
        combos.forEach { (word, keys) -> if (t.contains(" $word ")) found += keys }
        vaccines.forEach { v ->
            if (v.keywords.any { t.contains(" $it ") }) found += v.key
        }
        // "mmrv" contains no " mmr " token, handled by combos; "pentavalent" keyword overlaps dtap — fine.
        return found.toList()
    }
}
