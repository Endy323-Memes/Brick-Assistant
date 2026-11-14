package com.brick.assistant.brain

import java.util.Locale

/**
 * Phobia filters (Settings › Phobia filters). A filter that's on stops searches about its subject: the question,
 * or the card a search came back with (an animal's class, a page's title or description). A filter can also be
 * set to allow the search anyway, in which case the answer comes through but the card shows no picture.
 */
object Phobias {
    class Phobia(val key: String, val name: String, val about: String, val rx: Regex)

    private fun rx(s: String) = Regex("\\b(?:$s)\\b", RegexOption.IGNORE_CASE)

    val ALL = listOf(
        Phobia("arachnophobia", "Arachnophobia", "spiders, scorpions, ticks and mites",
            rx("arachnids?|arachnophobia|spiders?(?![- ]?(?:man|men|woman|verse|gwen|solitaire|plant|plants|lil(?:y|ies)))|spiderlings?|tarantulas?|black widows?|brown recluses?|" +
                "recluse spiders?|wolf spiders?|jumping spiders?|orb[- ]weavers?|huntsman spiders?|funnel[- ]web\\w*|birdeaters?|scorpions?|daddy ?long ?legs|harvestmen|" +
                "ticks?|mites?|chiggers?|cobwebs?|spider ?webs?|webs? of a spider")),
        Phobia("entomophobia", "Entomophobia", "insects and creepy-crawlies",
            rx("insects?|entomophobia|bed ?bugs?|stink ?bugs?|june ?bugs?|lightning ?bugs?|ladybugs?|ladybirds?|beetles?|cockroach(?:es)?|roach(?:es)?|ants?(?![- ]?man)|" +
                "fire ?ants?|termites?|bees?|bumblebees?|honeybees?|wasps?|hornets?|yellow ?jackets?|(?:house|fruit|horse|black|crane|sand|bot|tsetse) ?fl(?:y|ies)|maggots?|" +
                "larva|larvae|grubs?|caterpillars?|moths?|mosquito(?:e?s)?|gnats?|crickets?|grasshoppers?|locusts?|cicadas?|earwigs?|silverfish|" +
                "centipedes?|millipedes?|lice|fleas?|aphids?|weevils?|praying mantis(?:es)?|mantis(?:es)?|stick insects?|dragonfl(?:y|ies)|wood ?lice|roly ?pol(?:y|ies)|pill ?bugs?")),
        Phobia("ophidiophobia", "Ophidiophobia", "snakes",
            rx("snakes?|ophidiophobia|serpents?|vipers?|pit ?vipers?|cobras?(?! kai)|king cobras?|rattlesnakes?|rattlers?|(?:green |giant )?anacondas?(?= snakes?)|" +
                "(?:ball|burmese|reticulated|rock) pythons?|pythons?(?= snakes?)|boa constrictors?|boas?(?= snakes?)|(?:black|green) mambas?|adders?|copperheads?|cottonmouths?|" +
                "water moccasins?|garter snakes?|corn snakes?|sea snakes?|taipans?|asps?")),
        Phobia("galeophobia", "Galeophobia", "sharks",
            rx("sharks?|galeophobia|great whites?|megalodon|hammerheads?|tiger sharks?|bull sharks?|mako|whale sharks?")),
        Phobia("thalassophobia", "Thalassophobia", "the deep sea and its creatures",
            rx("thalassophobia|deep[- ](?:sea|ocean|water)s?|ocean (?:floor|depths?)|abyss(?:al)?|mariana trench|challenger deep|anglerfish|angler fish|gulper eels?|" +
                "giant (?:squids?|isopods?)|colossal squids?|frilled sharks?|goblin sharks?|viperfish|oarfish|sea monsters?|kraken|leviathans?|how deep (?:is|are) the (?:ocean|sea)")),
        Phobia("trypophobia", "Trypophobia", "clusters of small holes",
            rx("trypophobia|lotus (?:seed )?(?:pods?|heads?)|honeycombs?|surinam(?:e)? toads?|clustered holes|holes? in (?:skin|flesh)|sea sponges?|coral polyps?")),
        Phobia("musophobia", "Musophobia", "mice and rats",
            rx("musophobia|mice|mouse(?! (?:pad|click|button|wheel|cursor|pointer|sensitivity|dpi|settings|speed|acceleration))|rats?|rodents?|vermin")),
        Phobia("ornithophobia", "Ornithophobia", "birds",
            rx("ornithophobia|birds?(?! box)|pigeons?|crows?|ravens?|seagulls?|gulls?|vultures?|owls?|geese|parrots?|ostrich(?:es)?|emus?|cassowar(?:y|ies)")),
        Phobia("cynophobia", "Cynophobia", "dogs",
            rx("cynophobia|(?<!hot |corn |top |under|sun|chili )dogs?(?! days?)|pupp(?:y|ies)|wolf|wolves|pit ?bulls?|rottweilers?|german shepherds?|dobermans?|hounds?|canines?")),
        Phobia("coulrophobia", "Coulrophobia", "clowns",
            rx("coulrophobia|clowns?|pennywise|it the clown|killer klowns?")),
        Phobia("hemophobia", "Hemophobia", "blood, wounds and surgery",
            rx("hemophobia|haemophobia|blood(?!borne)(?! (?:moon|orange|type|pressure|sugar|test|work|drive|donation))|bleeding|(?<!al )gore|gory|wounds?|gash(?:es)?|" +
                "open fractures?|compound fractures?|amputat\\w+|autops(?:y|ies)|surgery|surgeries|surgical|dissect\\w*")),
        Phobia("trypanophobia", "Trypanophobia", "needles and injections",
            rx("trypanophobia|(?<!space |pine |sewing |knitting )needles?|syringes?|injections?|injected|blood draws?|getting (?:a )?shots?|flu shots?|iv drips?")),
        Phobia("emetophobia", "Emetophobia", "vomit",
            rx("emetophobia|vomit\\w*|throw(?:ing|s)? up|threw up|puk(?:e|ing)|barf\\w*|stomach (?:flu|bug)|norovirus")),
    )

    private val BY_KEY = ALL.associateBy { it.key }
    fun of(key: String): Phobia? = BY_KEY[key]

    /** The filters (from [on]) whose subject [text] is about. */
    fun hits(text: String?, on: Set<String>): List<Phobia> {
        if (text.isNullOrBlank() || on.isEmpty()) return emptyList()
        val t = text.lowercase(Locale.US)
        return on.mapNotNull { BY_KEY[it] }.filter { it.rx.containsMatchIn(t) }
    }

    /** What Brick says when a search is filtered out. */
    fun blockedLine(p: Phobia): String = "$BLOCKED ${p.name.lowercase(Locale.US)}. You can allow it in settings, under Phobia filters."

    private const val BLOCKED = "That search is off for"

    /** Is [text] (an answer or its card) a filtered-out search? Those aren't saved and get no "Don't save" button. */
    fun isBlocked(text: String?): Boolean = text?.contains(BLOCKED) == true
}
