package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Period
import java.util.Locale

/** What's asked about a war, battle or other event. */
enum class HistoryAspect { OVERVIEW, KEY_POINTS, WHEN, HOW_LONG, WHO_WON, WHO_FOUGHT, CAUSE, DEATHS, WHERE, SIGNIFICANCE, ON_THIS_DAY }

/** A history question: [subject] is the event ("world war ii"); [month]/[day] for "on this day". */
data class HistoryAsk(val subject: String, val aspect: HistoryAspect, val month: Int? = null, val day: Int? = null)

/** "Today in history": the year and what happened. */
data class PastEvent(val year: Int, val text: String)

object History {
    /** Words that make a subject an event rather than a person or a thing. */
    private const val EVENT = "wars?|battles?|revolutions?|rebellions?|uprisings?|revolts?|invasions?|sieges?|crusades?|conquests?|empires?|dynast(?:y|ies)|" +
        "depression|recession|holocaust|genocides?|massacres?|attacks?|bombings?|crisis|treat(?:y|ies)|pandemics?|plagues?|black death|movements?|reformation|" +
        "renaissance|enlightenment|industrial revolution|ww ?(?:i{1,2}|[12])|world wars?|9 ?11|9/11|d day|pearl harbou?r|moon landing|landings?|assassinations?|" +
        "elections?|independence|raids?|coups?|riots?|blockades?|airlift|armistice|occupations?|partition|apartheid|prohibition|gold rush|dust bowl|new deal|" +
        "space race|cultural revolution|fall of|sinking of|signing of|expeditions?|campaigns?|offensives?|operation \\w+|disasters?|earthquakes?|eruptions?|" +
        "great fire|spanish flu|reconstruction|slavery|segregation|civil rights|suffrage|colonization|exploration|middle ages|dark ages|bronze age|iron age|" +
        "stone age|ice age|era|age of|cold war|watergate|chernobyl|titanic|hiroshima|nagasaki|manhattan project|berlin wall|iron curtain|bay of pigs|" +
        "tiananmen|magna carta|declaration of independence|emancipation proclamation|boston tea party|trail of tears|louisiana purchase|lewis and clark|" +
        "storming of the bastille|reign of terror|napoleonic|roman empire|ottoman empire|mongol empire|byzantine|viking age|meiji|ming|qing|tudor|victorian era|" +
        "great leap forward|marshall plan|korean war|vietnam war|gulf war|iraq war|crimean|boer|opium wars?|thirty years|hundred years|wars of the roses|" +
        "spanish armada|great migration|harlem renaissance|roaring twenties|great purge|nuremberg trials|cuban missile crisis|apollo 11|challenger disaster|" +
        "columbine|hurricane katrina|great recession|arab spring|brexit|covid(?:-19)? pandemic|spanish civil war|russian revolution|french revolution|" +
        "american revolution|glorious revolution|haitian revolution|mexican revolution|chinese civil war|partition of india"
    private val EVENT_RX = Regex("\\b(?:$EVENT)\\b")

    fun isEvent(s: String) = EVENT_RX.containsMatchIn(s.lowercase(Locale.US))

    /** How events are often said, and their Wikipedia titles. */
    private val ALIASES: List<Pair<Regex, String>> = listOf(
        "(?:the )?(?:ww ?(?:2|ii)|world war (?:2|ii|two)|(?:the )?second world war)" to "World War II",
        "(?:the )?(?:ww ?(?:1|i)|world war (?:1|i|one)|(?:the )?first world war|(?:the )?great war)" to "World War I",
        "(?:the )?(?:american )?civil war|the war between the states" to "American Civil War",
        "(?:the )?(?:american )?revolutionary war|(?:the )?american revolution|(?:the )?(?:american )?war of independence|the revolution" to "American Revolutionary War",
        "9 ?11|9/11|nine eleven|(?:the )?september 11(?:th)?(?: attacks?)?|(?:the )?(?:9 ?11|9/11) attacks?" to "September 11 attacks",
        "(?:the )?d ?day(?: landings?)?|(?:the )?normandy (?:landings?|invasion)|(?:the )?invasion of normandy" to "Normandy landings",
        "(?:the )?vietnam(?: war)?" to "Vietnam War",
        "(?:the )?korean war" to "Korean War",
        "pearl harbou?r|(?:the )?(?:attack on|bombing of) pearl harbou?r" to "Attack on Pearl Harbor",
        "(?:the )?(?:great )?depression" to "Great Depression",
        "(?:the )?holocaust" to "The Holocaust",
        "(?:the )?cold war" to "Cold War",
        "(?:the )?moon landing|(?:the )?first moon landing|apollo 11" to "Apollo 11",
        "(?:the )?(?:jfk|kennedy) assassination|(?:the )?assassination of (?:jfk|john f kennedy|president kennedy|kennedy)" to "Assassination of John F. Kennedy",
        "(?:the )?(?:mlk|martin luther king) assassination|(?:the )?assassination of (?:mlk|martin luther king(?: jr)?)" to "Assassination of Martin Luther King Jr.",
        "(?:the )?lincoln assassination|(?:the )?assassination of (?:abraham )?lincoln" to "Assassination of Abraham Lincoln",
        "(?:the )?gulf war|(?:the )?(?:first )?persian gulf war|desert storm|operation desert storm" to "Gulf War",
        "(?:the )?iraq war|(?:the )?second gulf war" to "Iraq War",
        "(?:the )?war in afghanistan|(?:the )?afghanistan war" to "War in Afghanistan (2001–2021)",
        "(?:the )?french revolution" to "French Revolution",
        "(?:the )?russian revolution" to "Russian Revolution",
        "(?:the )?industrial revolution" to "Industrial Revolution",
        "(?:the )?civil rights movement" to "Civil rights movement",
        "(?:the )?black death|(?:the )?bubonic plague" to "Black Death",
        "(?:the )?spanish flu|(?:the )?1918 (?:flu|pandemic|influenza)" to "Spanish flu",
        "(?:the )?covid(?: 19)?(?: pandemic)?|(?:the )?coronavirus pandemic" to "COVID-19 pandemic",
        "(?:the )?titanic|(?:the )?sinking of the titanic" to "Sinking of the Titanic",
        "(?:the )?fall of (?:the )?berlin wall|(?:the )?berlin wall" to "Fall of the Berlin Wall",
        "(?:the )?cuban missile crisis" to "Cuban Missile Crisis",
        "(?:the )?bay of pigs(?: invasion)?" to "Bay of Pigs Invasion",
        "(?:the )?(?:fall of rome|fall of the (?:western )?roman empire)" to "Fall of the Western Roman Empire",
        "(?:the )?hundred years'? war|(?:the )?100 years'? war" to "Hundred Years' War",
        "(?:the )?thirty years'? war|(?:the )?30 years'? war" to "Thirty Years' War",
        "(?:the )?war of 1812" to "War of 1812",
        "(?:the )?space race" to "Space Race",
        "(?:the )?hiroshima(?: and nagasaki)?(?: bombings?)?|(?:the )?(?:atomic )?bombings? of hiroshima(?: and nagasaki)?|(?:the )?atomic bomb(?:ings)?" to "Atomic bombings of Hiroshima and Nagasaki",
        "(?:the )?chernobyl(?: disaster)?" to "Chernobyl disaster",
        "(?:the )?watergate(?: scandal)?" to "Watergate scandal",
        "(?:the )?crusades" to "Crusades",
        "(?:the )?renaissance" to "Renaissance",
        "(?:the )?boston tea party" to "Boston Tea Party",
        "(?:the )?great recession|(?:the )?2008 (?:financial )?crisis|(?:the )?2008 recession" to "2008 financial crisis",
    ).map { (p, t) -> Regex("^(?:$p)$") to t }

    /** The Wikipedia title for an event as said ("ww2" -> "World War II"), or the words tidied. */
    fun title(subject: String): String {
        val s = subject.lowercase(Locale.US).replace('-', ' ').replace(Regex("\\s+"), " ").trim().removeSuffix(" in history").trim()
        ALIASES.firstOrNull { it.first.matches(s) }?.let { return it.second }
        return s.removePrefix("the ").trim()
    }

    // ------------------------------------------------------------------ understanding the question

    private val LEAD = Regex(
        "^(?:(?:hey |ok |okay )?brick[, ]+)?(?:(?:can you |could you |would you |please |do you know |i want to know |i'd like to know |i wanna know |tell me |" +
            "let me know |quick question |look up |lookup |search (?:for )?|google |find out |any idea |remind me |so |hey )+)?",
    )
    private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december"

    /** A history question about an event (wars, battles, revolutions...) or "today in history"; people are [Lookup]'s. */
    fun parse(raw: String, now: LocalDate = LocalDate.now()): Request.History? {
        var s = raw.lowercase(Locale.US).replace('’', '\'').replace(Regex("[?!.,]"), " ").replace(Regex("\\s+"), " ").trim()
        s = Colloquial.spoken(s).replace(LEAD, "").trim()
        if (s.isEmpty()) return null
        fun g(p: String) = Patterns.get(p).find(s)?.groupValues

        // On this day: "today in history", "what happened on this day", "what happened on july 20 in history".
        if (Patterns.get("^(?:what (?:happened|went down|occurred) )?(?:on )?(?:this day|today|this date)(?: in history)?$|^(?:today|this day|this date) in history$|" +
                "^(?:what are )?(?:some )?(?:historical events|history facts|events in history|things that happened)(?: (?:on|for) (?:today|this day|this date))?$|" +
                "^what happened (?:on )?(?:this day|today|this date) (?:in|throughout) history$|^on this day(?: in history)?$|^what happened today in history$|^history today$").matches(s)) {
            return Request.History(HistoryAsk("today", HistoryAspect.ON_THIS_DAY, now.monthValue, now.dayOfMonth))
        }
        g("^(?:what (?:happened|went down|occurred) )?(?:on )?($MONTHS) (\\d{1,2})(?:st|nd|rd|th)? (?:in|throughout) history$|^what happened on ($MONTHS) (\\d{1,2})(?:st|nd|rd|th)?$|^(?:this day in history|on this day) (?:for )?($MONTHS) (\\d{1,2})(?:st|nd|rd|th)?$")?.let { m ->
            val month = m[1].ifEmpty { m[3].ifEmpty { m[5] } }
            val day = (m[2].ifEmpty { m[4].ifEmpty { m[6] } }).toIntOrNull()
            val mi = Regex("^($MONTHS)$").find(month)?.let { java.time.Month.valueOf(month.uppercase(Locale.US)).value }
            if (mi != null && day != null && day in 1..31) return Request.History(HistoryAsk("$month $day", HistoryAspect.ON_THIS_DAY, mi, day))
        }

        fun ask(subject: String, a: HistoryAspect): Request.History? {
            val t = subject.trim().replace(Regex("\\s+(?:please|for me|real quick|again|in history|in detail|briefly|in short|exactly|actually|all about|about|anyway|anyways|even)$"), "").trim()
            if (t.isEmpty() || t.split(' ').size > 9 || !isEvent(t)) return null
            return Request.History(HistoryAsk(t, a))
        }

        // How long, when.
        (g("^how long (?:did|was|were|does|do) (.+?) (?:last|go on|go for|take|run|drag on)$") ?: g("^how long (?:was|were|did) (.+)$") ?: g("^(?:the )?(?:length|duration) of (.+)$"))
            ?.let { m -> ask(m[1], HistoryAspect.HOW_LONG)?.let { return it } }
        (g("^when (?:did|was|were|does|do) (.+?) (?:start|begin|began|end|finish|finished|over|happen|happened|take place|occur|break out|kick off|conclude|ended|started|signed|fought|built|founded|written|ratified|passed|declared|launched|won|lost|abolished|discovered|invented)$")
            ?: g("^what (?:year|years|date|day|century|decade) (?:did|was|were) (.+?)(?: (?:start|begin|end|happen|take place|over|in|fought))?$")
            ?: g("^when (?:was|were|is) (.+)$")
            ?: g("^(?:the )?(?:dates?|years?|start|end|start date|end date) (?:of|for) (.+)$"))
            ?.let { m -> ask(m[1], HistoryAspect.WHEN)?.let { return it } }
        // Who won, who fought.
        (g("^who (?:won|win|wins|was victorious in|came out on top in|lost|prevailed in|ended up winning|ended up losing|came out ahead in|came out victorious in|took|got the win in) (.+)$") ?: g("^(?:the )?(?:winner|winners|victor|victors|outcome|result|results) (?:of|in) (.+)$")
            ?: g("^(.+?) (?:winner|winners|outcome|result)$") ?: g("^how did (.+?) end$") ?: g("^did (?:the )?(?:us|usa|united states|america|allies|north|south|union|confederacy|britain|uk|france|germany|japan|russia) (?:win|lose) (.+)$"))
            ?.let { m -> ask(m[1], HistoryAspect.WHO_WON)?.let { return it } }
        (g("^who (?:fought|was fighting|was involved|were involved|took part|participated|were the sides|was on each side|were the (?:sides|countries|nations|participants)) (?:in|during|of) (.+)$")
            ?: g("^who (?:fought|was fighting) (.+)$") ?: g("^(?:what|which) (?:countries|nations|sides|armies) (?:fought|were|was) (?:in|involved in) (.+)$")
            ?: g("^(?:the )?(?:sides|participants|belligerents|combatants|countries involved) (?:in|of) (.+)$"))
            ?.let { m -> ask(m[1], HistoryAspect.WHO_FOUGHT)?.let { return it } }
        // Why it started, what it led to.
        (g("^(?:why|how) did (.+?) (?:start|begin|happen|break out|occur|kick off|come about)$") ?: g("^what (?:caused|started|triggered|sparked|led to|began) (.+)$")
            ?: g("^(?:the )?(?:main )?(?:causes?|reasons?|origins?|roots?|triggers?) (?:of|for|behind) (.+)$") ?: g("^why was there (?:a |an )?(.+)$")
            ?: g("^what (?:were|was) the (?:main )?(?:causes?|reasons?) (?:of|for|behind) (.+)$")
            ?: g("^what (?:kicked off|set off|brought about|led up to|got|started off|sparked off) (.+?)(?: going| started)?$")
            ?: g("^why (?:did|was|were) (?:there )?(?:a |an )?(.+?) (?:even )?(?:happen|start|fought|a thing)$"))
            ?.let { m -> ask(m[1], HistoryAspect.CAUSE)?.let { return it } }
        (g("^why (?:was|is|were|are) (.+?) (?:so )?(?:important|significant|a turning point|a big deal|historic|historically important|remembered|relevant|famous|notable)$")
            ?: g("^what (?:was|were|is|are) the (?:significance|importance|impact|effects?|results?|outcomes?|consequences|legacy|aftermath) of (.+)$")
            ?: g("^(?:the )?(?:significance|importance|impact|effects?|consequences|legacy|aftermath) of (.+)$")
            ?: g("^what did (.+?) (?:lead to|change|result in|accomplish|do for .+)$") ?: g("^how did (.+?) (?:change|affect|impact|shape) .+$"))
            ?.let { m -> ask(m[1], HistoryAspect.SIGNIFICANCE)?.let { return it } }
        // The toll, the place.
        (g("^how many (?:people |soldiers |americans |civilians |troops |men |lives |jews |casualties )?(?:died|were killed|were lost|lost their lives|perished|were there|died in|were killed in)(?: in| during| from| at)? (.+)$")
            ?: g("^(?:the )?(?:death toll|casualties|casualty count|number of (?:deaths|dead|casualties)|body count|losses) (?:of|in|for|from|during) (.+)$")
            ?: g("^how deadly was (.+)$") ?: g("^how many died in (.+)$"))
            ?.let { m -> ask(m[1], HistoryAspect.DEATHS)?.let { return it } }
        (g("^where (?:did|was|were|is) (.+?) (?:happen|take place|fought|occur|located|held|signed)?$") ?: g("^(?:the )?location of (.+)$"))
            ?.let { m -> ask(m[1], HistoryAspect.WHERE)?.let { return it } }
        // Key points, summaries.
        (g("^(?:what (?:are|were) )?(?:the |some )?(?:key|important|main|major|big|notable|biggest|most important|significant|crucial) (?:points|facts|events|moments|takeaways|things|details|battles|dates|turning points|parts|figures|people|leaders) (?:of|in|about|from|during|on|to know about) (.+)$")
            ?: g("^(?:give me |list |tell me )?(?:some |a few |the )?(?:facts|fun facts|interesting facts|details) (?:about|on|of) (.+)$")
            ?: g("^(.+?) (?:key points|key facts|key events|facts|timeline|summary|overview|recap|cliff ?notes|highlights|in a nutshell|tldr|tl dr)$")
            ?: g("^(?:a |the )?(?:timeline|summary|overview|recap|rundown|breakdown|brief history|short history|quick history) of (.+)$")
            ?: g("^(?:summarize|summarise|sum up|recap|break down|walk me through|outline) (.+)$")
            ?: g("^(?:give me |tell me )?(?:the )?(?:highlights|cliff ?notes|gist|short version|basics) of (.+)$")
            ?: g("^(?:give me |get me |i want |i need )?(?:a |the )?(?:quick |short |brief |fast |little )?(?:rundown|run down|breakdown|summary|overview|recap|timeline|crash course|tldr|primer) (?:of|on|for|about) (.+)$"))
            ?.let { m -> ask(m[1], HistoryAspect.KEY_POINTS)?.let { return it } }
        // What happened.
        (g("^what (?:happened|went on|was going on|went down|occurred|took place) (?:during|in|at|on|with|throughout|over the course of) (.+)$")
            ?: g("^(?:tell me |teach me |talk to me |inform me |educate me )?(?:about|on) (.+)$")
            ?: g("^(?:explain|describe) (?:to me )?(?:what (?:happened (?:in|during) )?)?(.+)$")
            ?: g("^what (?:was|were|is|are) (.+)$")
            ?: g("^(?:the )?(?:history|background|story) (?:of|behind|about) (.+)$")
            ?: g("^(?:i want to learn|i wanna learn|teach me) (?:about )?(.+)$")
            ?: g("^(?:what do you know|what can you tell me) about (.+)$")
            ?: g("^(?:what's|what is|what was) the (?:deal|story|scoop|lowdown|tea|situation|background) (?:with|on|about|behind) (.+)$")
            ?: g("^(?:fill me in|catch me up|brief me|clue me in|school me|bring me up to speed|get me up to speed|give me (?:some )?info|info|information|background) (?:on|about|with) (.+)$")
            ?: g("^(?:what's|what was|what were|what is) (.+?) (?:all )?about$")
            ?: g("^(?:what's|what is) (.+)$"))
            ?.let { m -> ask(m[1], HistoryAspect.OVERVIEW)?.let { return it } }
        return null
    }

    // ------------------------------------------------------------------ answering

    private val SENT = Regex("(?<=[.!?])\\s+(?=[A-Z\"“])")

    private fun sentences(text: String) = SENT.split(text.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim())
        .map { it.trim() }.filter { it.length > 25 }

    private fun pick(all: List<String>, rx: Regex, skip: Set<String> = emptySet()) = all.firstOrNull { it !in skip && rx.containsMatchIn(it) }

    /** "about 73 million" for 73000000. */
    fun bigNumber(n: Long): String = when {
        n >= 1_000_000 -> "about " + "%.1f".format(Locale.US, n / 1_000_000.0).removeSuffix(".0") + " million"
        n >= 10_000 -> "about " + "%,d".format(Locale.US, (n / 1000) * 1000)
        else -> "%,d".format(Locale.US, n)
    }

    /** Everything Brick says and shows about an event. */
    suspend fun find(ask: HistoryAsk): LookupResult? = withContext(Dispatchers.IO) {
        val title = title(ask.subject)
        val p = Lookup.page(title, emptySet()) ?: return@withContext null
        val name = p.title.substringBefore(" (").trim()
        val intro = Sports.intro(p.title)?.second ?: p.extract
        val all = sentences(intro)
        val claims = p.qid?.let { Lookup.claims(it) }
        val f = if (claims != null) Lookup.factsFor(claims, listOf("P580", "P582", "P585", "P276", "P17", "P710", "P1346", "P1120", "P793", "P828", "P361", "P1542")) else emptyMap()

        val start = f["P580"]?.firstOrNull()
        val end = f["P582"]?.firstOrNull()
        val at = f["P585"]?.firstOrNull()
        val whenRow = when {
            start != null && end != null -> "${Lookup.prettyDate(start)} - ${Lookup.prettyDate(end)}"
            start != null -> "${Lookup.prettyDate(start)} -"
            at != null -> Lookup.prettyDate(at)
            else -> null
        }
        val deaths = f["P1120"]?.firstOrNull()?.substringBefore('.')?.toLongOrNull()
        val rows = buildList {
            whenRow?.let { add("WHEN" to it) }
            (Lookup.listSaid(f["P276"], 3) ?: f["P17"]?.firstOrNull())?.let { add("WHERE" to it) }
            Lookup.listSaid(f["P710"], 4)?.let { add("SIDES" to it) }
            Lookup.listSaid(f["P1346"], 2)?.let { add("WINNER" to it) }
            deaths?.let { add("DEATHS" to bigNumber(it)) }
            f["P361"]?.firstOrNull()?.let { add("PART OF" to it) }
            Lookup.listSaid(f["P793"], 4)?.let { add("EVENTS" to it) }
        }
        val summary = all.take(2).joinToString(" ").ifBlank { p.extract }
        val keyPoints = all.drop(2).filter { it.length <= 260 }.take(6).ifEmpty { all.take(5) }

        val answer: String? = when (ask.aspect) {
            HistoryAspect.OVERVIEW -> all.firstOrNull() ?: summary
            HistoryAspect.KEY_POINTS -> all.firstOrNull()?.let { Sports.brief(it, 110) + " The key points are on the card." }
            HistoryAspect.WHEN -> when {
                start != null && end != null -> "$name lasted from ${Lookup.prettyDate(start)} to ${Lookup.prettyDate(end)}."
                start != null -> "$name began on ${Lookup.prettyDate(start)}."
                at != null -> "$name happened on ${Lookup.prettyDate(at)}."
                else -> pick(all, Regex("\\b(?:1\\d{3}|20\\d{2}|\\d{1,3} (?:BC|BCE|AD|CE))\\b"))
            }
            HistoryAspect.HOW_LONG -> {
                val a = start?.let { Lookup.dateOf(it) }
                val b = end?.let { Lookup.dateOf(it) }
                if (a != null && b != null) {
                    val per = Period.between(a, b)
                    val parts = listOfNotNull(per.years.takeIf { it > 0 }?.let { "$it year${if (it == 1) "" else "s"}" },
                        per.months.takeIf { it > 0 }?.let { "$it month${if (it == 1) "" else "s"}" },
                        per.days.takeIf { it > 0 && per.years == 0 }?.let { "$it day${if (it == 1) "" else "s"}" })
                    "$name lasted ${parts.joinToString(" and ").ifBlank { "less than a day" }}, from ${Lookup.prettyDate(start)} to ${Lookup.prettyDate(end)}."
                } else pick(all, Regex("\\b(?:lasted|years|months|until|ended)\\b"))
            }
            HistoryAspect.WHO_WON -> {
                // The outcome first ("ended in a stalemate", "a decisive victory"), a treaty only when nothing says who won.
                val said = pick(all, Regex("\\b(?:won the war|emerged victorious|victorious|(?:decisive |allied |\\w+ )?victory (?:for|of|over)|unconditional(?:ly)? surrender\\w*|surrendered|stalemate|status quo|inconclusive|neither side|both sides claimed|ended (?:with|in) (?:a|an|the)|resulted in (?:a|an|the))\\b", RegexOption.IGNORE_CASE))
                    ?: pick(all, Regex("\\b(?:defeated|won|victory|triumph\\w*|prevailed)\\b(?! of)", RegexOption.IGNORE_CASE))
                    ?: pick(all, Regex("\\b(?:armistice|ceasefire|treaty|peace)\\b", RegexOption.IGNORE_CASE))
                val winner = Lookup.listSaid(f["P1346"], 2)
                if (winner != null) "$winner won $name." else said
            }
            HistoryAspect.WHO_FOUGHT -> {
                val said = pick(all, Regex("\\b(?:fought between|between|against|alliance|allies|axis|belligerents|sides|coalition)\\b", RegexOption.IGNORE_CASE))
                Lookup.listSaid(f["P710"], 4)?.let { "$name was fought between $it." } ?: said
            }
            HistoryAspect.CAUSE -> {
                val said = pick(all, Regex("\\b(?:caus\\w*|trigger\\w*|spark\\w*|began (?:when|with|after)|outbreak|led to|in response to|because|origins?|precipitated|tensions|followed|after the)\\b", RegexOption.IGNORE_CASE), setOf(all.firstOrNull().orEmpty()))
                    ?: pick(all, Regex("\\b(?:caus\\w*|trigger\\w*|spark\\w*|began|outbreak|in response to|because)\\b", RegexOption.IGNORE_CASE))
                val causes = Lookup.listSaid(f["P828"], 3)
                said ?: causes?.let { "$name's causes include $it." }
            }
            HistoryAspect.DEATHS -> {
                val said = pick(all, Regex("\\b(?:killed|deaths?|died|dead|casualties|lives|fatalities|perished|death toll)\\b", RegexOption.IGNORE_CASE))
                if (deaths != null) "${bigNumber(deaths).replaceFirstChar { it.uppercase() }} people died in $name." else said
            }
            HistoryAspect.WHERE -> (Lookup.listSaid(f["P276"], 4) ?: f["P17"]?.firstOrNull())?.let { "$name took place in $it." }
                ?: pick(all, Regex("\\b(?:in|at|across|throughout)\\s+[A-Z]"))
            HistoryAspect.SIGNIFICANCE -> pick(all, Regex("\\b(?:significan\\w*|important|turning point|led to|resulted in|marked|legacy|consequence\\w*|impact|changed|transformed|influenc\\w*|reshaped|paved the way|one of the (?:most|largest|deadliest|first))\\b", RegexOption.IGNORE_CASE), setOf(all.firstOrNull().orEmpty()))
                ?: all.getOrNull(1)
            HistoryAspect.ON_THIS_DAY -> null
        }

        // Said briefly; the card has the rest (the summary, the facts and the key points).
        // Said short (the card has the summary, the facts and the key points).
        LookupResult(p.title, p.description, null, summary, p.image, p.url, rows, answer?.let { if (ask.aspect == HistoryAspect.KEY_POINTS) it else Sports.brief(it, 130) },
            listOf("Wikipedia") + if (claims != null) listOf("Wikidata") else emptyList(),
            list = keyPoints, listTitle = "KEY POINTS", heading = "HISTORY")
    }

    /** What happened on a day in past years (Wikipedia's "On this day"), newest first. */
    suspend fun onThisDay(month: Int, day: Int): List<PastEvent> = withContext(Dispatchers.IO) {
        val r = Http.getJsonCached("https://en.wikipedia.org/api/rest_v1/feed/onthisday/events/${"%02d".format(month)}/${"%02d".format(day)}") ?: return@withContext emptyList()
        val a = r.optJSONArray("events") ?: return@withContext emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { e ->
            val y = e.optInt("year", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: return@mapNotNull null
            PastEvent(y, e.optString("text").trim())
        }.filter { it.text.isNotEmpty() }.sortedByDescending { it.year }
    }

    /** Two from across the years for saying aloud: one from the middle, the oldest. */
    fun spread(events: List<PastEvent>): List<PastEvent> {
        if (events.size <= 2) return events
        return listOf(events[events.size / 2], events.last()).distinct()
    }
}
