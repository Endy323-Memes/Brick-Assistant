package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** What's asked about a character: one trait ("is sheldon smart"), a role ("is vader the villain"), which role, or their personality. */
enum class CharacterWant { TRAIT, ROLE, WHICH_ROLE, PERSONALITY }

/** The parts a character can play in a story. */
enum class CharRole(val said: String) {
    PROTAGONIST("the protagonist"), ANTAGONIST("the antagonist"), HERO("a good guy"), VILLAIN("a villain"),
    ANTIHERO("an antihero"), MAIN("a main character"), SIDE("a side character"),
}

/** A question about a character. [name] as said ("walter white"), [work] when named ("breaking bad"), [trait] a [Trait.key]. */
data class CharacterAsk(
    val name: String,
    val want: CharacterWant,
    val work: String? = null,
    val trait: String? = null,
    val role: CharRole? = null,
    /** The question as asked, for a web search when Brick can't place the character. */
    val said: String = name,
)

/** A trait: how it's asked ([ask], said by you) and how sources show it ([shows], in an article). [opposite] is another trait's key. */
class Trait(val key: String, val said: String, ask: String, shows: String, val opposite: String? = null) {
    val ask: List<String> = ask.split('|')
    val shows = Regex("\\b(?:$shows)", RegexOption.IGNORE_CASE)
}

object Characters {
    val TRAITS: List<Trait> = listOf(
        Trait("goofy", "goofy", "goofy|silly|clumsy|wacky|zany|a goofball|goofball|dorky|a dork|derpy|a klutz|klutzy|bumbling|a clown|the comic relief|comic relief|comedic relief",
            "goof|silly|silliness|clums|wacky|zany|dork|klutz|bumbling|buffoon|comic relief|comedic relief|slapstick|oaf|doofus|lovable idiot"),
        Trait("funny", "funny", "funny|hilarious|humorous|witty|comedic|a jokester|jokester|a comedian",
            "funny|hilarious|humou?r(?:ous)?\\b|witty|wit\\b|comedic|comic relief|jokes?\\b|quip|wisecrack|playful"),
        Trait("dumb", "dumb", "dumb|stupid|an idiot|idiotic|dim witted|dimwitted|unintelligent|foolish|a fool|dense|clueless|an airhead|airheaded|oblivious",
            "dumb|stupid|idiot|dim-?witted|unintelligen|foolish|fools?\\b|dense\\b|clueless|airhead|oblivious|dull-?witted|simple-?minded|ignoran|naive|naïve", "smart"),
        Trait("smart", "smart", "smart|intelligent|clever|a genius|genius|brilliant|wise|a mastermind|book smart|street smart|gifted|a prodigy|bright",
            "intelligen|genius|brilliant|brillian|clever|smart\\b|gifted|prodig|mastermind|intellect|wise\\b|wisdom|savant|highly educated|cunning|shrewd|resourceful|quick-?witted|analytical", "dumb"),
        Trait("arrogant", "arrogant", "arrogant|cocky|conceited|egotistical|egotistic|smug|pompous|prideful|proud|haughty|entitled|stuck up|full of himself|full of herself|full of themselves|condescending|a show off|showoff|a know it all",
            "arrogan|cocky|cockiness|conceit|egotis|big ego|inflated ego|huge ego|massive ego|smug|pompous|prideful|excessive pride|(?:his|her|their) pride\\b|haughty|hubris|condescen|superiority|self-important|entitle|braggart|boastful|show-?off|know-it-all", "humble"),
        Trait("humble", "humble", "humble|modest|down to earth",
            "humble|humility|modest|down-to-earth|unassuming|self-effacing", "arrogant"),
        Trait("narcissistic", "narcissistic", "narcissistic|a narcissist|narcissist|self absorbed|self centered|self obsessed|vain|an egomaniac|egomaniac|egomaniacal|vain",
            "narcissis|self-absorb|self-cent|self-obsess|vain\\b|vanity|egomania|grandios|megaloman|need for admiration|admiration of others"),
        Trait("stubborn", "stubborn", "stubborn|obstinate|headstrong|hard headed|hardheaded|bullheaded|bull headed|pig headed|pigheaded|strong willed|inflexible|uncompromising",
            "stubborn|obstina|headstrong|hard-?headed|bull-?headed|pig-?headed|strong-willed|inflexib|uncompromising|refuses to (?:back down|give up|admit|listen|change)|unwilling to (?:compromise|admit|change)"),
        Trait("determined", "determined", "determined|driven|persistent|relentless|resilient|tenacious|hard working|hardworking",
            "determined|determination|driven\\b|persisten|relentless|resilien|tenaci|never gives? up|hard-?working|perseveran|ambitious"),
        Trait("powerful", "powerful", "powerful|strong|overpowered|op|the strongest|strongest|unbeatable|invincible|a god|godlike|super strong|really strong|the most powerful|most powerful|unstoppable",
            "powerful|strongest|superhuman|super-?strength|superpowers?|immense (?:power|strength)|great (?:power|strength)|incredible (?:power|strength)|god-?like|omnipoten|invincib|invulnerab|formidable|mightiest|immortal|magical (?:power|abilit)|supernatural (?:power|abilit)|(?:his|her|their) (?:powers|abilities)|unmatched|overpowered|unstoppable", "weak"),
        Trait("weak", "weak", "weak|powerless|frail|a weakling|weakling|feeble",
            "weak\\b|weakling|powerless|frail|feeble|physically weak", "powerful"),
        Trait("kind", "kind", "kind|nice|sweet|caring|compassionate|gentle|friendly|good hearted|kind hearted|kindhearted|warm hearted|generous|selfless|wholesome|a sweetheart|sweetheart|pure",
            "kind(?:ness|-hearted|hearted|ly)?\\b(?! of)|caring\\b|compassion|gentle\\b|good-natured|good-hearted|sweet-natured|selfless|generous|generosity|empath|friendly|warm-hearted|benevolen|altruis|wholesome|big-hearted|soft-hearted", "cruel"),
        Trait("cruel", "cruel", "cruel|mean|sadistic|heartless|cold hearted|coldhearted|ruthless|vicious|merciless|callous|brutal|a bully|bully|spiteful|vindictive",
            "cruel|sadis|heartless|cold-?hearted|ruthless|vicious|merciless|callous|brutal|bully|bullies|spiteful|vindictive|malicious|tyrann|sociopath|psychopath|without remorse|no remorse", "kind"),
        Trait("evil", "evil", "evil|wicked|malevolent|sinister|a monster|monstrous|pure evil|demonic|diabolical",
            "evil\\b|wicked|malevolen|sinister|monstrous|diabolical|villainous|demonic|nefarious|dark lord|malicious", "kind"),
        Trait("crazy", "unhinged", "crazy|insane|unhinged|psychotic|a psychopath|psychopath|a sociopath|sociopath|psycho|a psycho|unstable|mentally ill|mad|deranged|nuts",
            "crazy|insan|unhinged|psychot|psychopath|sociopath|unstable|instabil|mentally ill|mental illness|madness|deranged|mad\\b|lunatic|maniac|delusion"),
        Trait("brave", "brave", "brave|courageous|fearless|heroic|bold|daring|valiant",
            "brave|braver|courag|fearless|heroic|heroism|bold\\b|boldly|daring|valiant|valor|selfless sacrifice", "cowardly"),
        Trait("cowardly", "cowardly", "cowardly|a coward|coward|timid|spineless|a scaredy cat|scaredy cat|fearful|easily scared",
            "coward|timid|spineless|fearful|easily (?:frightened|scared)|scaredy", "brave"),
        Trait("loyal", "loyal", "loyal|faithful|devoted|trustworthy|dependable|reliable|a good friend",
            "loyal|faithful|devot|trustworth|dependab|reliab|steadfast", "treacherous"),
        Trait("treacherous", "treacherous", "treacherous|a traitor|traitor|disloyal|backstabbing|a backstabber|backstabber|two faced|untrustworthy",
            "treacher|traitor|betrays\\b|betraying|betrayal of|disloyal|backstab|two-faced|untrustworth|double-cross|turncoat", "loyal"),
        Trait("manipulative", "manipulative", "manipulative|a manipulator|manipulator|deceptive|deceitful|scheming|conniving|a liar|liar|dishonest|sneaky|devious|cunning|calculating|two faced",
            "manipulat|deceit|decepti|deceiv|scheming|schemer|conniv|liar|\\blies\\b|lying|dishonest|devious|sneaky|duplicit|calculating|machiavellian|cunning", "honest"),
        Trait("honest", "honest", "honest|truthful|sincere|genuine|honorable|honourable|moral|righteous|principled|noble|a good person|good person",
            "honest|truthful|sincer|integrity|honou?rable|moral compass|strong morals|principled|noble\\b|righteous|sense of justice|moral code", "manipulative"),
        Trait("selfish", "selfish", "selfish|greedy|materialistic|self serving|money hungry|a gold digger",
            "selfish|greed|self-serving|materialis|avaric|money-hungry|gold-?digg|self-interest"),
        Trait("lazy", "lazy", "lazy|a slacker|slacker|unmotivated|laid back|apathetic|a couch potato",
            "lazy|laziness|slacker|sloth|apath|unmotivated|laid-back|lethargic|idle\\b|couch potato", "determined"),
        Trait("shy", "shy", "shy|introverted|an introvert|introvert|quiet|reserved|awkward|socially awkward|antisocial|a loner|loner|withdrawn|anxious|nervous",
            "shy|shyness|introver|reserved\\b|quiet\\b|socially awkward|awkward|loner|withdrawn|reclus|antisocial|anxious|anxiety|nervous|insecur|timid", "outgoing"),
        Trait("outgoing", "outgoing", "outgoing|extroverted|an extrovert|extrovert|social|sociable|loud|bubbly|energetic|hyper|hyperactive|enthusiastic",
            "outgoing|extrover|sociable|gregarious|loud\\b|loudmouth|bubbly|energetic|hyperactiv|enthusias|exuberan|boisterous|talkative", "shy"),
        Trait("charismatic", "charismatic", "charismatic|charming|likable|likeable|lovable|loveable|cool|popular|confident|smooth|suave",
            "charism|charming|charm\\b|likab|likeab|lovab|loveab|confiden|suave|smooth-talking|magnetic"),
        Trait("annoying", "annoying", "annoying|irritating|obnoxious|insufferable|unlikable|unlikeable|a pain|grating|unbearable|whiny|a brat|bratty|spoiled",
            "annoying|irritating|obnoxious|insufferable|unlikab|unlikeab|grating|whin|bratty|brat\\b|spoiled|exasperat|pest\\b|nuisance"),
        Trait("sarcastic", "sarcastic", "sarcastic|snarky|sassy|cynical|deadpan|sardonic|grumpy",
            "sarcas|snark|deadpan|sardonic|cynic|sassy|sass\\b|dry wit|acerbic|biting"),
        Trait("angry", "hot-tempered", "angry|hot headed|hotheaded|short tempered|quick tempered|aggressive|violent|temperamental|grumpy|rude|mean spirited|irritable|moody|volatile|explosive",
            "anger|angry|hot-?headed|short temper|short-tempered|quick temper|quick-tempered|temper\\b|rage\\b|raging|aggressi|irritab|grumpy|grouch|volatile|rude\\b|rudeness|abrasive|hostile|belligerent|explosive|temperamental|moody"),
        Trait("cheerful", "cheerful", "cheerful|happy|optimistic|positive|upbeat|happy go lucky|carefree|joyful|bubbly|sunny|a ray of sunshine",
            "cheerful|optimis|upbeat|happy-go-lucky|carefree|joyful|joyous|cheery|sunny disposition|positive attitude|lighthearted|light-hearted", "gloomy"),
        Trait("gloomy", "troubled", "sad|depressed|gloomy|pessimistic|tragic|a tragic character|brooding|dark|troubled|tortured|tormented|broken|lonely|traumatized",
            "depress|gloomy|melanchol|pessimis|brooding|troubled|tortured|tormented|tragic|trauma|grief|grieving|guilt-ridden|lonely|loneliness|haunted by|self-loathing|despair", "cheerful"),
        Trait("naive", "naive", "naive|innocent|gullible|childish|immature|childlike|a child at heart",
            "naiv|naïv|innocence\\b|an innocent\\b|gullib|childlike|childish|immatur|wide-eyed"),
        Trait("mature", "mature", "mature|responsible|level headed|levelheaded|calm|collected|composed|patient|stoic|serious",
            "mature\\b|maturity|responsib|level-headed|calm\\b|composed|patien|stoic|disciplined|rational|pragmatic|sensible", "naive"),
        Trait("mysterious", "mysterious", "mysterious|enigmatic|secretive|cryptic|a mystery|mystery",
            "myster|enigma|secretive|cryptic|shadowy"),
        Trait("ambitious", "ambitious", "ambitious|power hungry|ruthlessly ambitious|a go getter",
            "ambiti|power-hungry|lust for power|thirst for power|desire for power|hunger for power|social climb|go-getter"),
        Trait("jealous", "jealous", "jealous|envious|insecure|bitter|petty",
            "jealous|envy|envious|insecur|inferiority|bitter\\b|bitterness|petty|resent"),
        Trait("rebellious", "rebellious", "rebellious|a rebel|rebel|reckless|impulsive|wild|a rule breaker|a troublemaker|troublemaker|chaotic|unpredictable",
            "rebel|reckless|impulsiv|wild\\b|rule-break|troublemak|mischiev|chaotic|unpredictab|defiant|disobedien|hot-blooded|rash\\b"),
        Trait("protective", "protective", "protective|overprotective|a protector|protector|motherly|fatherly|a parent figure",
            "protective|protector|protects\\b|protect (?:his|her|their)|motherly|fatherly|maternal|paternal|father figure|mother figure|parental"),
        Trait("perverted", "flirtatious", "flirty|a flirt|flirtatious|a womanizer|womanizer|a player|perverted|a pervert|pervert|creepy|a creep|seductive",
            "flirt|womani[sz]|lecher|pervert|perverse|creepy|creep\\b|seducti|seduc|promiscu|lustful"),
        Trait("rich", "wealthy", "wealthy|a billionaire|billionaire|a millionaire|millionaire|loaded|filthy rich|super rich",
            "wealth|billionaire|millionaire|a fortune\\b|vast fortune|affluen|rich\\b|riches|heir(?:ess)?\\b"),
    )
    private val TRAIT_BY_KEY = TRAITS.associateBy { it.key }
    // Traits close enough to answer "sort of" when the one asked isn't said outright (Patrick isn't called goofy, but dumb and naive).
    private val RELATED = mapOf(
        "goofy" to listOf("funny", "dumb", "naive"), "funny" to listOf("goofy", "sarcastic"), "dumb" to listOf("naive", "goofy"),
        "arrogant" to listOf("narcissistic", "selfish"), "narcissistic" to listOf("arrogant", "selfish"), "stubborn" to listOf("determined", "rebellious"),
        "powerful" to listOf("brave"), "cruel" to listOf("evil", "crazy"), "evil" to listOf("cruel", "crazy"), "crazy" to listOf("evil", "angry"),
        "kind" to listOf("loyal", "protective", "cheerful"), "manipulative" to listOf("treacherous", "selfish"), "annoying" to listOf("arrogant", "naive", "outgoing"),
        "naive" to listOf("dumb", "cheerful"), "shy" to listOf("gloomy"), "angry" to listOf("cruel"), "selfish" to listOf("arrogant", "narcissistic"),
    )
    fun trait(key: String): Trait? = TRAIT_BY_KEY[key]

    private val ROLE_ASK: Map<CharRole, List<String>> = mapOf(
        CharRole.PROTAGONIST to listOf("protagonist", "main protagonist", "primary protagonist", "lead protagonist", "hero of the story", "hero of the show", "hero of the series",
            "hero of the movie", "hero of the film", "main hero", "lead", "star of the show", "star of the movie"),
        CharRole.MAIN to listOf("main character", "main characters", "major character", "lead character", "central character", "title character", "titular character",
            "important character", "main cast", "main role", "lead role", "star", "regular", "series regular"),
        CharRole.SIDE to listOf("side character", "side characters", "supporting character", "supporting characters", "minor character", "minor characters", "secondary character",
            "background character", "recurring character", "guest character", "side role", "supporting role", "minor role", "small role", "extra", "sidekick"),
        CharRole.ANTAGONIST to listOf("antagonist", "main antagonist", "primary antagonist", "main villain", "primary villain", "big bad", "final boss", "the enemy", "enemy", "nemesis", "arch enemy", "archenemy"),
        CharRole.VILLAIN to listOf("villain", "villains", "bad guy", "bad guys", "baddie", "a baddie", "evil guy", "on the bad side", "bad side", "bad person", "a bad person", "supervillain", "super villain"),
        CharRole.HERO to listOf("hero", "heroine", "good guy", "good guys", "on the good side", "good side", "superhero", "super hero", "a good guy"),
        CharRole.ANTIHERO to listOf("antihero", "anti hero", "morally gray", "morally grey", "morally ambiguous", "gray character", "grey character", "morally gray character", "morally grey character"),
    )

    /** Every phrase you might say, longest first, to what it asks. */
    private val PHRASES: List<Pair<String, Any>> = (TRAITS.flatMap { t -> t.ask.map { it to (t as Any) } } +
        ROLE_ASK.flatMap { (r, l) -> l.map { it to (r as Any) } })
        .distinctBy { it.first }.sortedByDescending { it.first.length }
    private val PHRASE_OF = PHRASES.toMap()
    private val PHRASE_ALT = PHRASES.joinToString("|") { it.first.replace(" ", "\\s+") } // letters and spaces only

    /** "really", "a", "considered to be"... between the name and the trait. */
    private const val MODS = "(?:(?:a|an|the|one of the|one|really|very|so|too|pretty|super|truly|that|actually|kind of|kinda|sort of|sorta|more of|more|less|always|ever|considered|considered to be|considered as|supposed to be|meant to be|written as|written to be|portrayed as|seen as|described as|known for being|known as|still|also|just|an actual|a real|the most|most|quite|somewhat|at all)\\s+)*"
    private const val WORK = "(?:\\s+(?:in|on|from|of)\\s+(?:the\\s+)?(?:(?:tv\\s+)?(?:show|series|movie|film|anime|manga|cartoon|game|book|novel|franchise)\\s+)?(.+?))?"
    private const val TAIL = "(?:\\s+(?:character|person|guy|girl|man|woman|dude|one|type|kind of character|type of character))?(?:\\s+at\\s+all)?"

    private val NOT_A_NAME = Regex("^(?:(?:it|this|that|these|those|he|she|they|him|her|them|i|me|you|we|us|there|here|today|tonight|tomorrow|yesterday|outside|inside)(?: .*)?|everything|everyone|anyone|anybody|someone|somebody|something|life|weather|the weather|the forecast|that true|this true|" +
        "(?:my|your|our|his|her|their|its|this|that|a|an|some|any)\\b.*|" +
        "the (?:phone|wifi|wi fi|signal|battery|internet|connection|tv|television|light|lights|lamp|room|house|car|game|match|movie|film|show|episode|season|series|song|album|music|playlist|food|water|coffee|market|stock|stocks|economy|team|offense|defense|app|update|sun|moon|wind|storm|rain|snow|traffic|plan|idea|test|exam|price|deal|sale|volume|brightness|screen|camera|computer|laptop|pc|console|server|network|news|air|sky|ocean|sea|city|town|road|beach)\\b.*)$")

    /** Reads a question about a character (or anyone with a story): "is walter white the protagonist", "is goku powerful", "describe sheldon's personality". */
    fun parse(raw: String): Request.Character? {
        var s = raw.lowercase(Locale.US).trim().replace(Regex("\\s+"), " ").replace('’', '\'')
        s = s.replace(Regex("^(?:hey |ok |okay )?(?:brick )?(?:so |and |but |also |um |uh )*"), "")
        val opinion = Regex("^(?:do you think|would you say|would you agree|i wonder|i'm wondering|i was wondering|i want to know|tell me|can you tell me|could you tell me|do you know|let me know|is it true|be honest|honestly|in your opinion|in your view)(?: that)?\\s+")
        val asked = opinion.containsMatchIn(s)
        s = s.replace(opinion, "")
        // "do you think the joker is insane" -> "is the joker insane" (only after a lead-in: "spotify is slow" is a complaint).
        if (asked) Regex("^(.+?) (is|was) ((?:$MODS)(?:$PHRASE_ALT)$TAIL$WORK)$").find(s)?.let { s = "${it.groupValues[2]} ${it.groupValues[1]} ${it.groupValues[3]}" }
        // "if walter white is smart" -> "is walter white smart".
        Regex("^(?:if|whether) (.+?) (is|was|are|were) (.+)$").find(s)?.let { s = "${it.groupValues[2]} ${it.groupValues[1]} ${it.groupValues[3]}" }
        // "would you call walter white smart", "do you consider vader a villain".
        Regex("^(?:would|do|did|does|could) (?:you|people|fans|most people) (?:call|consider|describe|see|think of|view) (.+?) (?:to be |as )?((?:a |an |the )?(?:$PHRASE_ALT)$TAIL$WORK)$").find(s)?.let {
            s = "is ${it.groupValues[1]} ${it.groupValues[2]}"
        }
        s = s.removeSuffix(" or not").removeSuffix(" right").trim()

        fun name(n: String): String? {
            val t = n.trim().removeSuffix("'s").trim()
            if (t.isEmpty() || NOT_A_NAME.matches(t) || t.split(' ').size > 6) return null
            return t
        }
        fun work(w: String?) = w?.trim()?.removeSuffix(" series")?.ifBlank { null }
        fun ask(n: String, want: CharacterWant, w: String?, what: Any?): Request.Character? {
            val nm = name(n) ?: return null
            return Request.Character(CharacterAsk(nm, want, work(w), (what as? Trait)?.key, what as? CharRole, raw.trim()))
        }

        // "is walter white a main character or a side character", "is zuko a good guy or a bad guy", "is light smart or dumb".
        Regex("^(?:is|was|are|were) (.+?) $MODS($PHRASE_ALT)$TAIL or $MODS($PHRASE_ALT)$TAIL$WORK$").find(s)?.let { m ->
            val a = PHRASE_OF[m.groupValues[2]]; val b = PHRASE_OF[m.groupValues[3]]
            return when {
                a is CharRole && b is CharRole -> ask(m.groupValues[1], CharacterWant.WHICH_ROLE, m.groupValues[4], null)
                a is Trait -> ask(m.groupValues[1], CharacterWant.TRAIT, m.groupValues[4], a)
                else -> null
            }
        }
        // "is walter white the protagonist of breaking bad", "is goku powerful", "was dwight schrute arrogant".
        Regex("^(?:is|was|are|were|isn't|wasn't) (.+?) $MODS($PHRASE_ALT)$TAIL$WORK$").find(s)?.let { m ->
            val what = PHRASE_OF[m.groupValues[2].replace(Regex("\\s+"), " ")] ?: return null
            return ask(m.groupValues[1], if (what is CharRole) CharacterWant.ROLE else CharacterWant.TRAIT, m.groupValues[3], what)
        }
        // "how smart is sheldon cooper", "how powerful is goku in dragon ball".
        Regex("^how ($PHRASE_ALT) (?:is|was|are|were) (.+?)$WORK$").find(s)?.let { m ->
            val what = PHRASE_OF[m.groupValues[1]] as? Trait ?: return null
            return ask(m.groupValues[2], CharacterWant.TRAIT, m.groupValues[3], what)
        }
        // "does goku have powers", "does superman have super strength".
        Regex("^(?:does|did) (.+?) (?:have|has) (?:any )?(?:super ?powers|powers|special powers|abilities|special abilities|super strength|magic|magical powers)$WORK$").find(s)?.let { m ->
            return ask(m.groupValues[1], CharacterWant.TRAIT, m.groupValues[2], trait("powerful"))
        }
        // "what kind of character is jesse pinkman", "what role does gus fring have in breaking bad".
        Regex("^what (?:kind|sort|type) of (?:character|role) (?:is|was) (.+?)$WORK$").find(s)?.let { m ->
            return ask(m.groupValues[1], CharacterWant.WHICH_ROLE, m.groupValues[2], null)
        }
        Regex("^(?:is|was) (.+?) (?:a |an |the )?(?:good|bad) (?:guy|person|character) or (?:a |an |the )?(?:good|bad) (?:guy|person|character)$WORK$").find(s)?.let { m ->
            return ask(m.groupValues[1], CharacterWant.WHICH_ROLE, m.groupValues[2], null)
        }
        // Personality: "describe sheldon's personality", "what is homer simpson like as a person", "what kind of person is walter white".
        // ("what is bryan cranston's character in breaking bad" asks who he plays: "character" only with "like".)
        val personality = "personality|personality traits|character traits|traits|attitude|demeanor|demeanour|temperament"
        (Regex("^(?:what(?:'s| is| was| are| were)? |describe |tell me about |explain |give me |list |summarize |sum up )?(?:the )?(.+?)(?:'s|s'| s) (?:$personality)(?: like)?$WORK$").find(s)
            ?: Regex("^what(?:'s| is| was) (?:the )?(.+?)(?:'s|s') character like$WORK$").find(s)
            ?: Regex("^(?:what(?:'s| is| was| are| were)? |describe |tell me about |explain |list )?(?:the )?(?:$personality) (?:of|for) (.+?)$WORK$").find(s)
            ?: Regex("^what (?:kind|sort|type) of (?:a )?(?:person|guy|girl|man|woman|dude|being|personality) (?:is|was) (.+?)$WORK$").find(s)
            ?: Regex("^(?:what(?:'s| is| was) (.+?) like as an? (?:character|person|guy|girl))$WORK$").find(s)
            ?: Regex("^(?:describe|characterize) (.+?)(?: as an? (?:character|person))$WORK$").find(s)
            ?: Regex("^how would you (?:describe|characterize) (.+?)(?: as an? (?:character|person))?$WORK$").find(s)
            ?: Regex("^(?:what )?(?:traits|qualities|characteristics) (?:does|did) (.+?) have$WORK$").find(s))?.let { m ->
            // "the character of the show" isn't a name; neither is "describe the weather".
            val n = m.groupValues[1]
            if (Regex("\\b(?:show|movie|film|series|book|game)$").containsMatchIn(n)) return null
            return ask(n, CharacterWant.PERSONALITY, m.groupValues.getOrNull(2), null)
        }
        return null
    }

    // ------------------------------------------------------------------ reading the sources

    /** A part of an article: its [heading], [text], and whether it's about the character's personality ([about]). */
    data class Section(val heading: String, val text: String, val weight: Int, val about: Boolean) {
        /** Split once: every trait reads the same sentences. */
        val sentences: List<String> by lazy { split(text) }
    }

    /** Where Brick read about the character. */
    data class Source(val title: String, val url: String, val site: String, val sections: List<Section>)

    private val SKIP = Regex("^(?:references|see also|external links|notes|further reading|bibliography|footnotes|citations|sources|merchandise|in other media|other media|video games|toys|works cited)$", RegexOption.IGNORE_CASE)
    private val PERSONAL = Regex("personality|characteri[sz]ation|^character|characteristics|traits|persona\\b|psychology|portrayal|description|^biography$|motivation|abilities|powers|skills", RegexOption.IGNORE_CASE)
    private val HEADING = Regex("^(=+)\\s*(.+?)\\s*\\1\\s*$", RegexOption.MULTILINE)

    /** An article's plain text cut into sections (Wikipedia's "== Heading ==" lines); references and the like are dropped. */
    fun sections(text: String): List<Section> {
        val out = ArrayList<Section>()
        val hits = HEADING.findAll(text).toList()
        val intro = text.substring(0, hits.firstOrNull()?.range?.first ?: text.length).trim()
        if (intro.isNotEmpty()) out += Section("", intro, 2, false)
        var parentPersonal = false
        var parentSkip = false
        hits.forEachIndexed { i, h ->
            val level = h.groupValues[1].length
            val head = h.groupValues[2]
            val body = text.substring(h.range.last + 1, hits.getOrNull(i + 1)?.range?.first ?: text.length).trim()
            if (level <= 2) { parentPersonal = PERSONAL.containsMatchIn(head); parentSkip = SKIP.matches(head) }
            if (parentSkip || SKIP.matches(head) || body.isEmpty()) return@forEachIndexed
            val personal = PERSONAL.containsMatchIn(head) || parentPersonal
            out += Section(head, body, if (personal) 3 else 1, personal)
        }
        return out
    }

    fun wikiText(title: String): String? {
        val pages = Http.getJsonCached("https://en.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&explaintext=1&exsectionformat=wiki&redirects=1&titles=${Http.enc(title)}")
            ?.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val p = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }.firstOrNull { !it.has("missing") } ?: return null
        return p.optString("extract").ifBlank { null }?.take(120_000)
    }

    /** The part of a "List of ... characters" page about [name]: its own section (and subsections). */
    fun listSection(text: String, name: String): List<Section> {
        val hits = HEADING.findAll(text).toList()
        val tokens = tokens(name)
        if (tokens.isEmpty()) return emptyList()
        fun norm(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ")
        val full = tokens.joinToString(" ")
        val pick = hits.indexOfFirst { norm(it.groupValues[2]).contains(full) }.takeIf { it >= 0 }
            ?: hits.indexOfFirst { h -> tokens.all { t -> norm(h.groupValues[2]).split(' ').any { it.startsWith(t) } } }.takeIf { it >= 0 }
            ?: tokens.reversed().firstNotNullOfOrNull { t -> hits.indexOfFirst { h -> norm(h.groupValues[2]).split(' ').any { it == t } }.takeIf { it >= 0 } }
            ?: return emptyList()
        val level = hits[pick].groupValues[1].length
        val end = (pick + 1 until hits.size).firstOrNull { hits[it].groupValues[1].length <= level }?.let { hits[it].range.first } ?: text.length
        val body = text.substring(hits[pick].range.last + 1, end).replace(HEADING, "").trim()
        return if (body.isEmpty()) emptyList() else listOf(Section(hits[pick].groupValues[2], body, 3, true))
    }

    /** Fandom wikis worth trying for a [work] ("breaking bad" -> breakingbad, "spongebob squarepants" -> spongebob). */
    fun fandomWikis(work: String): List<String> {
        val w = work.lowercase(Locale.US).substringBefore(" (").replace(Regex("[:\\-–].*$"), "").trim()
        val bare = w.removePrefix("the ").replace(Regex("[^a-z0-9]"), "")
        val withThe = w.replace(Regex("[^a-z0-9]"), "")
        val first = w.removePrefix("the ").split(' ').firstOrNull()?.replace(Regex("[^a-z0-9]"), "")?.takeIf { it.length >= 4 }
        return listOfNotNull(bare, withThe, first).filter { it.length >= 3 }.distinct().take(3)
    }

    /** Fandom's article on [name] in the wiki for [work]: its opening and its personality sections. */
    private fun fandom(name: String, work: String): Source? {
        for (w in fandomWikis(work)) {
            val base = "https://$w.fandom.com/api.php?format=json"
            val hits = Http.getJsonCached("$base&action=query&list=search&srlimit=5&srsearch=${Http.enc(name)}")
                ?.optJSONObject("query")?.optJSONArray("search") ?: continue
            val titles = (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.optString("title")?.ifBlank { null } }
            val want = tokens(name)
            // Their own page ("Patrick Star"), not one that only has the name in it ("The Patrick Star Show").
            fun fits(t: String) = want.all { k -> tokens(t).any { it.startsWith(k) } }
            val title = titles.firstOrNull { it.equals(name, true) } ?: titles.firstOrNull { fits(it) && tokens(it).size == want.size }
                ?: titles.firstOrNull { fits(it) && it.contains(" (").not() && tokens(it).size <= want.size + 1 } ?: continue
            val secs = Http.getJsonCached("$base&action=parse&prop=sections&redirects=1&page=${Http.enc(title)}")
                ?.optJSONObject("parse")?.optJSONArray("sections")
            val wanted = (0 until (secs?.length() ?: 0)).mapNotNull { secs?.optJSONObject(it) }
                .filter { PERSONAL.containsMatchIn(it.optString("line")) && !Regex("appearance", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("line")) }
                .take(2).map { it.optString("line") to it.optString("index") }
            val parts = (listOf("" to "0") + wanted).mapNotNull { (head, idx) ->
                val html = Http.getJsonCached("$base&action=parse&prop=text&redirects=1&disabletoc=1&section=$idx&page=${Http.enc(title)}")
                    ?.optJSONObject("parse")?.optJSONObject("text")?.optString("*") ?: return@mapNotNull null
                val text = stripHtml(html).take(20_000)
                if (text.isBlank()) null else Section(head, text, if (head.isEmpty()) 2 else 3, head.isNotEmpty())
            }
            if (parts.isNotEmpty()) return Source(title, "https://$w.fandom.com/wiki/${title.replace(' ', '_')}", "Fandom", parts)
        }
        return null
    }

    fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(aside|table|style|script|sup|figure|nav|figcaption)\\b.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</h\\d>|</div>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        .replace(Regex("\\[\\d+\\]"), "")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()

    // ------------------------------------------------------------------ what the text says

    private val SENT = Regex("(?<=[.!?])\\s+(?=[A-Z\"“'(])")
    private val NEGATION = setOf("not", "never", "no", "nor", "isn't", "wasn't", "aren't", "weren't", "doesn't", "didn't", "less", "hardly", "barely", "far", "lacks", "lacking", "lack", "without", "anything", "instead", "rather", "neither")
    private val PRONOUN_START = Regex("^(?:he|she|his|her|the character|this character|despite (?:his|her))\\b", RegexOption.IGNORE_CASE)
    private val STOP = setOf("the", "of", "and", "von", "van", "de", "da", "jr", "sr", "mr", "mrs", "ms", "dr", "character", "fictional")

    fun tokens(name: String): List<String> = name.lowercase(Locale.US).substringBefore(" (").replace(Regex("[^a-z0-9 ]"), " ")
        .split(' ').filter { it.length >= 3 && it !in STOP }

    /** A sentence that says something, and whether it's about the character. */
    data class Said(val sentence: String, val score: Int)

    private fun split(text: String): List<String> = text.split('\n').flatMap { p ->
        SENT.split(p.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim())
    }.map { it.trim() }.filter { it.length in 12..600 && it.last() in ".!?\"”')" }

    /** Whether the words just before [at] in [s] negate it ("is not arrogant", "far from stupid"). */
    fun negated(s: String, at: Int): Boolean {
        val before = s.substring(0, at).lowercase(Locale.US).split(Regex("[^a-z']+")).filter { it.isNotEmpty() }.takeLast(3)
        return before.any { it in NEGATION || it.endsWith("n't") }
    }

    private val WORD = Regex("[a-z0-9]+")

    /**
     * Where the character is last named before [at] in [s] (the end of their name, or of a pronoun opening the sentence),
     * or -1 when the words before [at] aren't about them.
     */
    private fun subjectEnd(s: String, at: Int, names: List<String>): Int {
        val before = s.substring(0, at).lowercase(Locale.US)
        val named = WORD.findAll(before).lastOrNull { w ->
            val t = w.value
            t.length >= 3 && names.any { n -> t == n || n.length >= 4 && t.length >= 4 && t.startsWith(n.take(4)) && n.startsWith(t.take(4)) }
        }
        if (named != null) return named.range.last + 1
        return PRONOUN_START.find(s)?.takeIf { it.range.last < at }?.let { it.range.last + 1 } ?: -1
    }

    // Words between the character and a trait that mean the trait is someone else's ("fights the arrogant Vegeta").
    private val OTHERS = Regex("\\b(?:fights?|fought|against|battles?|battled|defeats?|defeated|faces?|faced|opposes?|opposed|foes?|enemies|rivals?|unlike|compared|than|such as|including|alongside|befriends?|meets?|met|marries|married|loves?|hates?|kills?|killed|of (?:his|her|their))\\b", RegexOption.IGNORE_CASE)
    // "his intellectually gifted son": a trait of the person named after it.
    private val RELATIVE = Regex("^\\W*(?:\\w+\\W+){0,2}?(?:son|sons|daughter|daughters|brother|brothers|sister|sisters|father|mother|dad|mom|wife|husband|friend|friends|partner|boss|rival|colleague|roommate|neighbou?r|girlfriend|boyfriend|uncle|aunt|cousin|family|parents|children|kids|siblings?|nephew|niece|grandfather|grandmother|teacher|student|mentor|enemy|enemies|villains?|counterpart|twin|clone|lover|fiancée?)\\b", RegexOption.IGNORE_CASE)
    private val POSSESSIVE = Regex("\\b(?:his|her|their|\\w+'s)\\s+(?:\\w+\\s+)?$", RegexOption.IGNORE_CASE)
    // A role has to be said of them: "Walter White is ... the main protagonist", "serves as the villain".
    private val LINK = Regex("\\b(?:is|was|are|were|as|becomes|became|become|serves|served|considered|regarded|remains|one of)\\b", RegexOption.IGNORE_CASE)
    // ("is the best friend of SpongeBob, the protagonist": SpongeBob's role.)
    private val ROLE_BREAK = Regex("\\b(?:who|whom|which|that|with|from|of|and (?:his|her|their)|friend|partner|sidekick)\\b|,", RegexOption.IGNORE_CASE)

    /** A role word in [s] said of the subject (a linking verb close before it, nothing pointing at someone else between). */
    private fun roleIn(s: String, rx: Regex): MatchResult? = rx.findAll(s).firstOrNull { m ->
        val before = s.substring(0, m.range.first)
        val link = LINK.findAll(before).lastOrNull() ?: return@firstOrNull false
        val gap = before.substring(link.range.last + 1)
        WORD.findAll(gap.lowercase(Locale.US)).count() <= 7 && !ROLE_BREAK.containsMatchIn(gap) && !OTHERS.containsMatchIn(gap)
    }

    /** The sentences that show it ([find] picks the words) about the character (scored), and the ones that deny it. */
    fun evidence(sections: List<Section>, find: (String) -> MatchResult?, names: List<String>): Pair<List<Said>, List<Said>> {
        val yes = ArrayList<Said>(); val no = ArrayList<Said>()
        for (sec in sections) for (s in sec.sentences) {
            val m = find(s) ?: continue
            val end = subjectEnd(s, m.range.first, names)
            val about = end >= 0 && !OTHERS.containsMatchIn(s.substring(end, m.range.first))
            // A personality section (or the character's own entry in a list) is about them throughout.
            if (!about && !sec.about) continue
            if (!about && OTHERS.containsMatchIn(s.substring(0, m.range.first))) continue
            // "his gifted son": the son's.
            if (POSSESSIVE.containsMatchIn(s.substring(0, m.range.first)) && RELATIVE.containsMatchIn(s.substring(m.range.last + 1))) continue
            // Said straight out ("Patrick is dim-witted") counts most; a quote cut mid-way ("...he lumbers around.\" Fagerbakke said") least.
            val gap = if (end >= 0) s.substring(end, m.range.first) else ""
            val direct = about && LINK.containsMatchIn(gap) && WORD.findAll(gap.lowercase(Locale.US)).count() <= 8
            val quoted = s.count { it == '"' || it == '“' || it == '”' } % 2 == 1 || s.contains(" said") || s.startsWith("\"")
            val score = sec.weight + (if (about) 2 else 0) + (if (direct) 2 else 0) - (if (quoted) 3 else 0)
            if (score <= 0) continue
            if (negated(s, m.range.first)) no += Said(s, score) else yes += Said(s, score)
        }
        return yes.sortedByDescending { it.score } to no.sortedByDescending { it.score }
    }

    // A capitalized word mid-sentence is a name ("Goofy Goober's", "Joker"), not a trait.
    fun evidence(sections: List<Section>, rx: Regex, names: List<String>) =
        evidence(sections, { s -> rx.findAll(s).firstOrNull { m -> m.range.first == 0 || !s[m.range.first].isUpperCase() } }, names)

    /** The traits the text shows most, strongest first. */
    fun traitsIn(sections: List<Section>, names: List<String>): List<Pair<Trait, Int>> {
        val all = TRAITS.map { t -> t to evidence(sections, t.shows, names).first.sumOf { it.score } }.filter { it.second >= 3 }
        val score = all.associate { it.first.key to it.second }
        // Of a trait and its opposite (smart / dumb), only the one said more.
        return all.filter { (t, n) -> t.opposite?.let { o -> (score[o] ?: 0) <= n } ?: true }.sortedByDescending { it.second }
    }

    private val R_PROTAGONIST = Regex("\\b(?:(?:main|primary|central|titular|title|lead|eponymous) )?protagonists?\\b|\\b(?:title|titular|eponymous|lead|central) (?:character|role)\\b|\\bmain character\\b|\\bthe hero of\\b", RegexOption.IGNORE_CASE)
    private val R_ANTAGONIST = Regex("\\bantagonists?\\b|\\b(?:main|primary|chief|central|recurring|major) villain\\b|\\bsupervillain|\\barch-?(?:enemy|nemesis|villain|rival)|\\bnemesis\\b|\\bbig bad\\b|\\bvillain(?:ess|s)?\\b", RegexOption.IGNORE_CASE)
    private val R_ANTIHERO = Regex("\\banti-?hero(?:ine)?\\b|\\bmorally (?:gr[ae]y|ambiguous|complex)\\b|\\banti-?villain\\b", RegexOption.IGNORE_CASE)
    private val R_HERO = Regex("\\b(?:super)?hero(?:ine|es)?\\b|\\bheroic\\b", RegexOption.IGNORE_CASE)
    private val R_MAIN = Regex("\\b(?:one of the |a )?(?:main|central|principal|lead|leading|primary|major|starring) (?:characters?|cast|roles?|protagonists?)\\b|\\b(?:deuter|trit)agonist\\b|\\bprotagonist\\b|\\btitle character\\b|\\btitular character\\b", RegexOption.IGNORE_CASE)
    private val R_SIDE = Regex("\\b(?:supporting|recurring|minor|secondary|side|background|guest|peripheral) (?:characters?|roles?|cast)\\b|\\bsidekick\\b|\\bcameo\\b", RegexOption.IGNORE_CASE)

    /** The best sentence for each role the text gives the character (roles live in the opening and the role sections). */
    data class Roles(val protagonist: String?, val antagonist: String?, val antihero: String?, val hero: String?, val main: String?, val side: String?) {
        /** What they are, said, the strongest first. */
        fun said(): String? = when {
            antihero != null -> "an antihero"
            protagonist != null && antagonist != null -> "both a protagonist and an antagonist"
            protagonist != null -> "the protagonist"
            antagonist != null -> "an antagonist"
            main != null -> "a main character"
            side != null -> "a supporting character"
            hero != null -> "a hero"
            else -> null
        }
    }

    fun roles(sections: List<Section>, names: List<String>): Roles {
        // The opening says it best ("Walter White is ... the main protagonist"): roles further down are often someone else's.
        val near = sections.filter { it.heading.isEmpty() || it.about }.ifEmpty { sections.take(2) }
        fun best(rx: Regex) = evidence(near, { roleIn(it, rx) }, names).first.firstOrNull()?.sentence
        return Roles(best(R_PROTAGONIST), best(R_ANTAGONIST), best(R_ANTIHERO), best(R_HERO), best(R_MAIN), best(R_SIDE))
    }

    // ------------------------------------------------------------------ finding and answering

    private val CHARACTER_DESC = Regex("\\b(?:character|superhero|super-?villain|villain|protagonist|antagonist|fictional|mascot|personification)\\b", RegexOption.IGNORE_CASE)
    private val WORK_IN = Regex("(?:character|protagonist|antagonist|villain|superhero|hero|mascot)s? (?:in|from|of|on) (?:the )?(?:(?:american|british|japanese|canadian|australian|korean|animated|live-action|adult|television|tv|sitcom|comedy|drama|crime|sci-fi|science fiction|fantasy|horror|anime|manga|film|video game|comic book|media|book|novel|cartoon|web|streaming)\\s+)*(?:(?:series|franchise|sitcom|show|films?|novels?|books?|manga|anime|comics?|game|games|cartoon|saga|universe)\\s+)?(.+)$", RegexOption.IGNORE_CASE)

    /** The work named in a page's description ("fictional character in the TV series Breaking Bad" -> "Breaking Bad"). */
    fun workOf(description: String?): String? {
        val d = description ?: return null
        return WORK_IN.find(d)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.substringBefore(" and ")?.ifBlank { null }
    }

    private fun matchesName(asked: String, title: String): Boolean {
        val a = tokens(asked.removePrefix("the ")); val t = tokens(title)
        if (a.isEmpty()) return false
        return a.any { k -> t.any { it == k || it.startsWith(k) || k.startsWith(it) && it.length >= 4 } }
    }

    /** Whether [source] answers [ask] on its own (else Fandom is asked too). */
    private fun settles(ask: CharacterAsk, sections: List<Section>, names: List<String>): Boolean = when (ask.want) {
        CharacterWant.TRAIT -> {
            val t = ask.trait?.let { trait(it) }
            t != null && evidence(sections, t.shows, names).let { (y, n) -> y.isNotEmpty() || n.isNotEmpty() } ||
                t?.opposite?.let { trait(it) }?.let { evidence(sections, it.shows, names).first.isNotEmpty() } == true
        }
        CharacterWant.ROLE, CharacterWant.WHICH_ROLE -> roles(sections, names).said() != null
        CharacterWant.PERSONALITY -> traitsIn(sections, names).size >= 3
    }

    /**
     * Finds the character (Wikipedia's article, a "List of ... characters" section, or Fandom's wiki for the show) and answers
     * [ask] from what it says. Null when Brick can't place them: the question then goes to a web search.
     */
    suspend fun find(ask: CharacterAsk): LookupResult? = withContext(Dispatchers.IO) {
        val words = listOfNotNull(ask.work?.let { "$it character" }, "character")
        val p = Lookup.page(ask.name, emptySet(), words)?.takeIf { matchesName(ask.name, it.title) || ask.work != null }
        var work = ask.work
        var main: Source? = null
        var image: String? = null
        var description: String? = null
        var played: List<Pair<String, String>> = emptyList()
        if (p != null) {
            val desc = p.description.orEmpty()
            val firstLine = p.extract.take(400)
            val isCharacter = CHARACTER_DESC.containsMatchIn(desc) || Regex("\\bfictional character\\b|\\bcharacter (?:in|from)\\b", RegexOption.IGNORE_CASE).containsMatchIn(firstLine)
            if ((isCharacter || p.kind == LookupKind.PERSON) && matchesName(ask.name, p.title)) {
                val text = wikiText(p.title)
                if (text != null) main = Source(p.title, p.url, "Wikipedia", sections(text))
                image = p.image
                description = p.description
                p.qid?.let { Lookup.claims(it) }?.let { c ->
                    val f = Lookup.factsFor(c, listOf("P175", "P725", "P1441"))
                    played = listOfNotNull(Lookup.listSaid(f["P175"], 3)?.let { "ACTOR" to it }, Lookup.listSaid(f["P725"], 3)?.let { "VOICE" to it })
                    // Wikidata's "present in work" names the show plainly ("Breaking Bad", not "the AMC series Breaking Bad").
                    if (work == null) work = f["P1441"]?.firstOrNull()
                }
                work = work ?: workOf(desc) ?: workOf(Regex("^[^.]+").find(firstLine)?.value)
            } else if (p.kind in setOf(LookupKind.TV_SHOW, LookupKind.MOVIE, LookupKind.BOOK, LookupKind.GAME)) {
                work = work ?: p.title.substringBefore(" (")
            }
        }
        // No article of their own: their part of the show's character list.
        if (main == null && work != null) {
            LookupExtras.characterList(work)?.let { (listTitle, url) ->
                val sec = wikiText(listTitle)?.let { listSection(it, ask.name) }.orEmpty()
                if (sec.isNotEmpty()) main = Source(sec.first().heading, url, "Wikipedia", sec)
            }
        }
        val names = (tokens(ask.name) + tokens(main?.title ?: "")).distinct()
        // Fandom's wiki for the show when Wikipedia doesn't settle it (it has a Personality section for nearly everyone).
        var fan: Source? = null
        if (work != null && (main == null || !settles(ask, main!!.sections, names))) fan = fandom(main?.title?.substringBefore(" (") ?: ask.name, work!!)
        val first = main ?: fan ?: return@withContext null
        val all = (main?.sections.orEmpty() + fan?.sections.orEmpty())
        val name = first.title.substringBefore(" (").trim().ifBlank { ask.name }
        build(ask, name, work, all, (names + tokens(fan?.title ?: "")).distinct(), first, fan.takeIf { main != null }, image, description, played)
    }

    private fun build(ask: CharacterAsk, name: String, work: String?, sections: List<Section>, names: List<String>, first: Source, extra: Source?,
        image: String?, description: String?, played: List<Pair<String, String>>): LookupResult {
        val traits = traitsIn(sections, names)
        val roles = roles(sections, names)
        val opening = split(sections.firstOrNull()?.text ?: "").firstOrNull()
        fun b(s: String, max: Int = 170) = said(s, max)
        val traitList = traits.take(5).map { it.first.said }
        val described = Lookup.listSaid(traitList, 3)

        var points: List<String> = emptyList()
        val answer: String = when (ask.want) {
            CharacterWant.TRAIT -> {
                val t = trait(ask.trait ?: "") ?: TRAITS.first()
                val (yes, no) = evidence(sections, t.shows, names)
                val opp = t.opposite?.let { trait(it) }?.let { evidence(sections, it.shows, names).first }.orEmpty()
                val y = yes.sumOf { it.score }; val n = no.sumOf { it.score } + opp.sumOf { it.score }
                points = (yes + no + opp).map { it.sentence }.distinct().take(5)
                when {
                    // "How smart is sheldon" is answered with what's said, not a yes.
                    y > 0 && y >= n -> (if (ask.said.trim().lowercase(Locale.US).startsWith("how ")) "" else "Yes. ") + b(yes.first().sentence)
                    n > 0 -> "Not really. " + b((no.firstOrNull() ?: opp.first()).sentence)
                    traits.any { it.first.key in RELATED[t.key].orEmpty() } ->
                        "Sort of. $name is described as ${Lookup.listSaid(traits.map { it.first }.filter { it.key in RELATED[t.key].orEmpty() }.map { it.said }, 3)}."
                    described != null ->"Nothing I found calls $name ${t.said}. $name is described as $described."
                    else -> "Nothing I found calls $name ${t.said}."
                }
            }
            CharacterWant.ROLE -> {
                points = listOfNotNull(roles.protagonist, roles.antagonist, roles.antihero, roles.main, roles.side).distinct().take(4)
                roleAnswer(ask.role ?: CharRole.MAIN, roles, name, opening, traits.map { it.first.key })
            }
            CharacterWant.WHICH_ROLE -> {
                points = listOfNotNull(roles.protagonist, roles.antagonist, roles.antihero, roles.main, roles.side).distinct().take(4)
                val r = roles.said()
                val why = listOfNotNull(roles.antihero, roles.protagonist, roles.antagonist, roles.main, roles.side, roles.hero).firstOrNull()
                if (r != null) "$name is $r. " + (why?.let { b(it, 150) } ?: "") else "I couldn't tell what part $name plays." + (opening?.let { " " + b(it, 150) } ?: "")
            }
            CharacterWant.PERSONALITY -> {
                // The sentence that best shows their top trait (said of them), not a plot line that happens to use the word.
                // Short, plain sentences read best; a long one cut down can end mid-thought.
                val best = traits.take(3).flatMap { (t, _) -> evidence(sections, t.shows, names).first.take(3) }
                    .sortedWith(compareByDescending<Said> { it.sentence.length <= 180 }.thenByDescending { it.score }).firstOrNull()?.sentence
                points = sections.filter { it.about }.flatMap { split(it.text) }.filter { s -> traits.any { it.first.shows.containsMatchIn(s) } }.take(5)
                    .ifEmpty { sections.flatMap { split(it.text) }.filter { s -> traits.any { it.first.shows.containsMatchIn(s) } }.take(5) }
                when {
                    described != null && best != null -> "$name is described as $described. " + b(best, 150)
                    described != null -> "$name is described as $described."
                    best != null -> b(best)
                    else -> opening?.let { b(it) } ?: "I couldn't find much about $name's personality."
                }
            }
        }

        val rows = buildList {
            work?.let { add("IN" to it) }
            addAll(played)
            roles.said()?.let { add("ROLE" to it.replaceFirstChar { c -> c.uppercase() }) }
            if (traitList.isNotEmpty()) add("TRAITS" to traitList.joinToString(", "))
        }
        val summary = opening?.let { b(it, 220) } ?: answer
        val links = listOfNotNull(extra?.let { it.site to it.url })
        return LookupResult(name, description, null, summary, image, first.url, rows, answer,
            sources = listOfNotNull(first.site, extra?.site, if (played.isNotEmpty()) "Wikidata" else null).distinct(),
            list = points.map { b(it, 260) }, listTitle = if (points.isEmpty()) null else "WHAT THEY SAY",
            links = links, heading = "CHARACTER")
    }

    private fun roleAnswer(role: CharRole, r: Roles, name: String, opening: String?, traits: List<String>): String {
        fun b(s: String) = said(s, 160)
        val bad = r.antagonist ?: r.antihero
        val good = r.protagonist ?: r.hero
        val unknown = "I couldn't tell whether $name is ${role.said}." + (r.said()?.let { " $name is described as $it." } ?: opening?.let { " " + b(it) } ?: "")
        return when (role) {
            CharRole.PROTAGONIST -> when {
                r.protagonist != null && r.antagonist != null -> "Yes and no. $name is described as both. " + b(r.protagonist)
                r.protagonist != null -> "Yes. " + b(r.protagonist)
                r.antihero != null -> "Sort of. " + b(r.antihero)
                r.antagonist != null -> "No. " + b(r.antagonist)
                r.side != null -> "No. " + b(r.side)
                else -> unknown
            }
            CharRole.MAIN -> when {
                r.protagonist != null -> "Yes. " + b(r.protagonist)
                r.main != null -> "Yes. " + b(r.main)
                r.hero != null && r.side == null -> "Yes. $name is described as a hero."
                r.side != null -> "No. " + b(r.side)
                r.antagonist != null && Regex("\\b(?:main|primary|central|chief) (?:antagonist|villain)\\b", RegexOption.IGNORE_CASE).containsMatchIn(r.antagonist) -> "Yes. " + b(r.antagonist)
                else -> unknown
            }
            CharRole.SIDE -> when {
                r.side != null && r.main == null -> "Yes. " + b(r.side)
                r.protagonist != null -> "No. " + b(r.protagonist)
                r.main != null -> "No. " + b(r.main)
                r.side != null -> "Yes. " + b(r.side)
                r.hero != null -> "No. $name is described as a hero."
                else -> unknown
            }
            CharRole.ANTAGONIST, CharRole.VILLAIN -> when {
                r.antagonist != null && r.protagonist != null -> "Yes and no. $name is described as both. " + b(r.antagonist)
                r.antagonist != null -> "Yes. " + b(r.antagonist)
                r.antihero != null -> "Sort of. " + b(r.antihero)
                good != null -> "No. " + b(good)
                "evil" in traits || "cruel" in traits -> "Probably. $name is described as ${if ("evil" in traits) "evil" else "cruel"}."
                else -> unknown
            }
            CharRole.HERO -> when {
                r.antihero != null -> "Sort of. " + b(r.antihero)
                good != null && r.antagonist == null -> "Yes. " + b(good)
                r.antagonist != null && good != null -> "Yes and no. $name is described as both. " + b(good)
                bad != null -> "No. " + b(bad)
                else -> unknown
            }
            CharRole.ANTIHERO -> when {
                r.antihero != null -> "Yes. " + b(r.antihero)
                r.protagonist != null && r.antagonist != null -> "Sort of. $name is described as both a protagonist and an antagonist. " + b(r.protagonist)
                r.said() != null -> "Not exactly. $name is described as ${r.said()}."
                else -> unknown
            }
        }
    }
}

/** [Sports.brief], but a cut never ends on "that", "the", "of"...: it backs up to the last full phrase. */
internal fun said(s: String, max: Int): String {
    var t = Sports.brief(s, max).trimEnd('.')
    val dangling = Regex("\\s+(?:that|the|a|an|of|and|or|but|to|in|on|at|for|with|as|by|from|his|her|their|its|is|was|are|were|who|which|while|whose|when|where|than|into|onto|about|through|throughout|during|after|before|over|under|between|among|toward|towards|notes|says|said|writes|wrote|adds|added)$", RegexOption.IGNORE_CASE)
    while (dangling.containsMatchIn(t)) t = t.replace(dangling, "")
    return "$t."
}
