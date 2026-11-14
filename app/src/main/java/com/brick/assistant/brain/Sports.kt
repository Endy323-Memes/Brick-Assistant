package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A league Brick follows through ESPN's public scoreboards. [split]: its season spans two years (NBA 2025-26). */
enum class League(val label: String, val sport: String, val slug: String, val split: Boolean, val words: String, val query: String = "") {
    NFL("NFL", "football", "nfl", false, "nfl|pro football"),
    NBA("NBA", "basketball", "nba", true, "nba|pro basketball"),
    MLB("MLB", "baseball", "mlb", false, "mlb|major league baseball|baseball"),
    NHL("NHL", "hockey", "nhl", true, "nhl|hockey"),
    WNBA("WNBA", "basketball", "wnba", false, "wnba|women'?s basketball"),
    MLS("MLS", "soccer", "usa.1", false, "mls|major league soccer"),
    EPL("Premier League", "soccer", "eng.1", true, "premier league|epl|english premier league|the prem"),
    NCAAF("College football", "football", "college-football", false, "college football|ncaa football|cfb|ncaaf", "groups=80"),
    NCAAM("College basketball", "basketball", "mens-college-basketball", true, "college basketball|ncaa basketball|ncaa men'?s basketball|march madness|cbb|ncaab", "groups=50"),
    ;

    val base get() = "https://site.api.espn.com/apis/site/v2/sports/$sport/$slug"
    /** Team names go with "the" ("the Knicks"); college and soccer clubs don't ("Alabama", "Arsenal"). */
    val article get() = this in setOf(NFL, NBA, MLB, NHL, WNBA)

    companion object {
        /** The leagues named in [s] ("nba scores"), with the bare sport words ("football" is the NFL, "soccer" MLS and the Premier League). */
        fun said(s: String): List<League> {
            val named = entries.filter { Patterns.get("\\b(?:${it.words})\\b").containsMatchIn(s) }
            if (named.isNotEmpty()) return if (NCAAF in named || NCAAM in named) named.filter { it == NCAAF || it == NCAAM } else named
            return when {
                Patterns.get("\\bfootball\\b").containsMatchIn(s) -> listOf(NFL)
                Patterns.get("\\bbasketball\\b").containsMatchIn(s) -> listOf(NBA)
                Patterns.get("\\b(?:soccer|futbol)\\b").containsMatchIn(s) -> listOf(MLS, EPL)
                else -> emptyList()
            }
        }

        fun bySlug(slug: String?): League? = entries.firstOrNull { it.slug.equals(slug, true) }
    }
}

/** A team as people say it, from Brick's own list (so questions are understood offline); ESPN's lists fill in the rest. */
data class TeamName(val league: League, val full: String, val nick: String, val aliases: List<String>)

enum class SportsWant { SCORE, RESULT, CHAMPION, INJURIES, NEWS, NEXT, RECORD }

/**
 * A sports question: [teams] as said ("eagles", "the cowboys", "alabama"), [league] when named, a [date] ("last night")
 * or [year], a championship [event] ("super bowl"), or a [player] ("joel embiid").
 */
data class SportsAsk(
    val want: SportsWant,
    val teams: List<String> = emptyList(),
    val league: League? = null,
    val date: LocalDate? = null,
    val year: Int? = null,
    val event: String? = null,
    val player: String? = null,
    /** A Super Bowl number ("57", "lvii"). */
    val number: String? = null,
    /** About a game ("the knicks game"), not just the team: injuries cover both sides. */
    val aboutGame: Boolean = false,
)

/** A team ESPN knows. */
data class TeamRef(val league: League, val id: String, val name: String, val short: String, val abbr: String, val location: String, val logo: String?)

data class Side(val id: String, val name: String, val short: String, val abbr: String, val score: String?, val winner: Boolean?, val record: String?, val home: Boolean)

data class Game(
    val id: String, val league: League, val time: ZonedDateTime?, val state: String, val detail: String, val completed: Boolean,
    val home: Side, val away: Side, val venue: String?, val tv: String?, val link: String?, val note: String?,
) {
    val sides get() = listOf(away, home)
    fun has(t: TeamRef) = sides.any { it.id == t.id }
    fun side(t: TeamRef) = sides.first { it.id == t.id }
    fun other(t: TeamRef) = sides.first { it.id != t.id }
}

data class Injury(val player: String, val team: String, val teamId: String, val status: String, val type: String?, val note: String?, val position: String?)

data class Schedule(val team: TeamRef, val record: String?, val standing: String?, val games: List<Game>)

/** One championship: how it's said, and the Wikipedia page for a year (the year it was played). */
class Championship(val name: String, said: String, val pages: (Int) -> List<String>) {
    val said = Regex("\\b(?:$said)\\b")
}

object Sports {
    // ------------------------------------------------------------------ teams people name

    private fun teams(league: League, text: String): List<TeamName> = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val parts = line.split("|")
        TeamName(league, parts[0].trim(), parts[1].trim(), parts.getOrNull(2)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty())
    }

    val TEAMS: List<TeamName> by lazy {
        teams(League.NFL, """
            Arizona Cardinals|cardinals|az cardinals
            Atlanta Falcons|falcons
            Baltimore Ravens|ravens
            Buffalo Bills|bills
            Carolina Panthers|panthers
            Chicago Bears|bears|da bears
            Cincinnati Bengals|bengals
            Cleveland Browns|browns
            Dallas Cowboys|cowboys|boys
            Denver Broncos|broncos
            Detroit Lions|lions
            Green Bay Packers|packers|pack
            Houston Texans|texans
            Indianapolis Colts|colts
            Jacksonville Jaguars|jaguars|jags
            Kansas City Chiefs|chiefs|kc chiefs
            Las Vegas Raiders|raiders
            Los Angeles Chargers|chargers|la chargers
            Los Angeles Rams|rams|la rams
            Miami Dolphins|dolphins|fins
            Minnesota Vikings|vikings|vikes
            New England Patriots|patriots|pats
            New Orleans Saints|saints
            New York Giants|giants|ny giants
            New York Jets|jets|ny jets
            Philadelphia Eagles|eagles|birds
            Pittsburgh Steelers|steelers
            San Francisco 49ers|49ers|niners,forty niners
            Seattle Seahawks|seahawks
            Tampa Bay Buccaneers|buccaneers|bucs
            Tennessee Titans|titans
            Washington Commanders|commanders
        """) + teams(League.NBA, """
            Atlanta Hawks|hawks
            Boston Celtics|celtics|c's
            Brooklyn Nets|nets
            Charlotte Hornets|hornets
            Chicago Bulls|bulls
            Cleveland Cavaliers|cavaliers|cavs
            Dallas Mavericks|mavericks|mavs
            Denver Nuggets|nuggets
            Detroit Pistons|pistons
            Golden State Warriors|warriors|dubs
            Houston Rockets|rockets
            Indiana Pacers|pacers
            LA Clippers|clippers|clips,los angeles clippers
            Los Angeles Lakers|lakers|la lakers
            Memphis Grizzlies|grizzlies|grizz
            Miami Heat|heat
            Milwaukee Bucks|bucks
            Minnesota Timberwolves|timberwolves|wolves,t wolves,twolves
            New Orleans Pelicans|pelicans|pels
            New York Knicks|knicks|knickerbockers
            Oklahoma City Thunder|thunder|okc
            Orlando Magic|magic
            Philadelphia 76ers|76ers|sixers,seventy sixers
            Phoenix Suns|suns
            Portland Trail Blazers|trail blazers|blazers
            Sacramento Kings|kings
            San Antonio Spurs|spurs
            Toronto Raptors|raptors
            Utah Jazz|jazz
            Washington Wizards|wizards
        """) + teams(League.MLB, """
            Arizona Diamondbacks|diamondbacks|d backs,dbacks
            Athletics|athletics|a's,oakland athletics,oakland a's
            Atlanta Braves|braves
            Baltimore Orioles|orioles|o's
            Boston Red Sox|red sox|bosox
            Chicago Cubs|cubs|cubbies
            Chicago White Sox|white sox|chisox
            Cincinnati Reds|reds
            Cleveland Guardians|guardians
            Colorado Rockies|rockies
            Detroit Tigers|tigers
            Houston Astros|astros
            Kansas City Royals|royals
            Los Angeles Angels|angels
            Los Angeles Dodgers|dodgers
            Miami Marlins|marlins
            Milwaukee Brewers|brewers
            Minnesota Twins|twins
            New York Mets|mets
            New York Yankees|yankees|yanks
            Philadelphia Phillies|phillies|phils
            Pittsburgh Pirates|pirates|bucs
            San Diego Padres|padres|pads
            San Francisco Giants|giants|sf giants
            Seattle Mariners|mariners
            St. Louis Cardinals|cardinals|cards,stl cardinals,st louis cardinals
            Tampa Bay Rays|rays
            Texas Rangers|rangers
            Toronto Blue Jays|blue jays|jays
            Washington Nationals|nationals|nats
        """) + teams(League.NHL, """
            Anaheim Ducks|ducks
            Boston Bruins|bruins|b's
            Buffalo Sabres|sabres
            Calgary Flames|flames
            Carolina Hurricanes|hurricanes|canes
            Chicago Blackhawks|blackhawks|hawks
            Colorado Avalanche|avalanche|avs
            Columbus Blue Jackets|blue jackets|jackets
            Dallas Stars|stars
            Detroit Red Wings|red wings|wings
            Edmonton Oilers|oilers
            Florida Panthers|panthers
            Los Angeles Kings|kings|la kings
            Minnesota Wild|wild
            Montreal Canadiens|canadiens|habs
            Nashville Predators|predators|preds
            New Jersey Devils|devils
            New York Islanders|islanders|isles
            New York Rangers|rangers|ny rangers
            Ottawa Senators|senators|sens
            Philadelphia Flyers|flyers
            Pittsburgh Penguins|penguins|pens
            San Jose Sharks|sharks
            Seattle Kraken|kraken
            St. Louis Blues|blues
            Tampa Bay Lightning|lightning|bolts
            Toronto Maple Leafs|maple leafs|leafs
            Utah Mammoth|mammoth|utah hockey club
            Vancouver Canucks|canucks
            Vegas Golden Knights|golden knights|knights
            Washington Capitals|capitals|caps
            Winnipeg Jets|jets|winnipeg jets
        """) + teams(League.WNBA, """
            Atlanta Dream|dream
            Chicago Sky|sky
            Connecticut Sun|sun
            Dallas Wings|wings
            Golden State Valkyries|valkyries
            Indiana Fever|fever
            Las Vegas Aces|aces
            Los Angeles Sparks|sparks
            Minnesota Lynx|lynx
            New York Liberty|liberty
            Phoenix Mercury|mercury
            Seattle Storm|storm
            Washington Mystics|mystics
            Toronto Tempo|tempo
            Portland Fire|fire
        """) + teams(League.MLS, """
            Atlanta United|atlanta united|atl utd
            Austin FC|austin fc
            Charlotte FC|charlotte fc
            Chicago Fire|chicago fire
            FC Cincinnati|fc cincinnati
            Colorado Rapids|rapids
            Columbus Crew|crew
            D.C. United|dc united
            FC Dallas|fc dallas
            Houston Dynamo|dynamo
            Inter Miami|inter miami|miami cf
            LA Galaxy|galaxy
            LAFC|lafc|los angeles fc
            Minnesota United|minnesota united|loons
            CF Montreal|cf montreal
            Nashville SC|nashville sc
            New England Revolution|revolution|revs
            New York City FC|nycfc|new york city fc
            New York Red Bulls|red bulls
            Orlando City|orlando city
            Philadelphia Union|union
            Portland Timbers|timbers
            Real Salt Lake|real salt lake|rsl
            San Diego FC|san diego fc
            San Jose Earthquakes|earthquakes|quakes
            Seattle Sounders|sounders
            Sporting Kansas City|sporting kc|sporting kansas city
            St. Louis City SC|st louis city|stl city
            Toronto FC|toronto fc|tfc
            Vancouver Whitecaps|whitecaps
        """) + teams(League.EPL, """
            Arsenal|arsenal|gunners
            Aston Villa|aston villa|villa
            AFC Bournemouth|bournemouth
            Brentford|brentford
            Brighton & Hove Albion|brighton
            Burnley|burnley
            Chelsea|chelsea
            Crystal Palace|crystal palace
            Everton|everton|toffees
            Fulham|fulham
            Leeds United|leeds|leeds united
            Liverpool|liverpool
            Manchester City|man city|manchester city
            Manchester United|man united|man u,man utd,manchester united
            Newcastle United|newcastle|newcastle united
            Nottingham Forest|nottingham forest|forest
            Sunderland|sunderland
            Tottenham Hotspur|tottenham|tottenham hotspur
            West Ham United|west ham|hammers
            Wolverhampton Wanderers|wolverhampton
        """)
    }

    private fun norm(s: String) = s.lowercase(Locale.US).replace("&", "and").replace(Patterns.get("\\bsaint\\b"), "st").replace(Patterns.get("[^a-z0-9 ]+"), "")
        .replace(Patterns.get("\\s+"), " ").trim()

    /** Every way a team is said, longest first ("new york knicks" before "knicks"). */
    private val NAMES: List<Pair<String, TeamName>> by lazy {
        TEAMS.flatMap { t -> (listOf(t.full, t.nick) + t.aliases).map { norm(it) to t } }.distinct().sortedByDescending { it.first.length }
    }
    private val NAME_RX: Regex by lazy { Regex("\\b(?:" + NAMES.map { Regex.escape(it.first) }.distinct().joinToString("|") + ")\\b") }

    /** The teams Brick's own list knows in [s], as said, in order. */
    fun teamsIn(s: String): List<String> = NAME_RX.findAll(norm(s)).map { it.value }.distinct().toList()

    /** Brick's own entries for a team as said ("giants": the NFL's and MLB's). */
    fun known(said: String): List<TeamName> = norm(said).removePrefix("the ").let { n -> NAMES.filter { it.first == n }.map { it.second }.distinct() }

    // ------------------------------------------------------------------ understanding the question

    private val LEAD = Regex(
        "^(?:(?:hey |ok |okay )?brick[, ]+)?(?:(?:can you |could you |would you |please |do you know |i want to know |i'd like to know |i wanna know |tell me |" +
            "let me know |quick question |check |look up |lookup |search (?:for )?|google |find out |any idea |yo |so |hey )+)?",
    )
    private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
    private val DAYS = DayOfWeek.entries.joinToString("|") { it.name.lowercase(Locale.US) }

    val CHAMPIONSHIPS: List<Championship> = listOf(
        Championship("Super Bowl", "super ?bowl", { y -> listOf("Super Bowl $y") }),
        Championship("World Series", "world series", { y -> listOf("$y World Series") }),
        Championship("NBA Finals", "nba finals?|nba championship|nba title|nba champs?|nba champions(?:hip)?", { y -> listOf("$y NBA Finals") }),
        Championship("Stanley Cup", "stanley cup(?: finals?)?|nhl championship|nhl title|nhl champions(?:hip)?", { y -> listOf("$y Stanley Cup Final") }),
        Championship("WNBA Finals", "wnba finals?|wnba championship|wnba title|wnba champions(?:hip)?", { y -> listOf("$y WNBA Finals") }),
        Championship("MLS Cup", "mls cup|mls championship|mls title", { y -> listOf("MLS Cup $y") }),
        Championship("Women's World Cup", "women'?s world cup", { y -> listOf("$y FIFA Women's World Cup final") }),
        Championship("World Cup", "(?:fifa |soccer |football )?world cup", { y -> listOf("$y FIFA World Cup final") }),
        Championship("Champions League", "champions league|ucl", { y -> listOf("$y UEFA Champions League final") }),
        Championship("Euros", "euros?|euro cup|european championships?", { y -> listOf("UEFA Euro $y final") }),
        Championship("Copa America", "copa america", { y -> listOf("$y Copa América final") }),
        Championship("Premier League", "premier league|epl|the prem", { y -> listOf("${y - 1}–${(y % 100).toString().padStart(2, '0')} Premier League") }),
        Championship("March Madness", "march madness|ncaa (?:men'?s )?(?:basketball )?(?:tournament|championship)|final four|college basketball (?:championship|title)", { y -> listOf("$y NCAA Division I men's basketball championship game") }),
        Championship("College Football Playoff", "college football (?:playoff|national championship|championship|title)|cfp(?: championship)?|national championship", { y -> listOf("$y College Football Playoff National Championship") }),
        Championship("Daytona 500", "daytona(?: 500)?", { y -> listOf("$y Daytona 500") }),
        Championship("Indy 500", "indy(?: 500)?|indianapolis 500", { y -> listOf("$y Indianapolis 500") }),
        Championship("Kentucky Derby", "kentucky derby", { y -> listOf("$y Kentucky Derby") }),
        Championship("Masters", "the masters|masters tournament|masters golf", { y -> listOf("$y Masters Tournament") }),
        Championship("Tour de France", "tour de france", { y -> listOf("$y Tour de France") }),
        Championship("Ryder Cup", "ryder cup", { y -> listOf("$y Ryder Cup") }),
        Championship("Wimbledon", "wimbledon", { y -> listOf("$y Wimbledon Championships – Men's singles", "$y Wimbledon Championships – Women's singles") }),
        Championship("US Open", "us open(?: tennis)?|u s open", { y -> listOf("$y US Open – Men's singles", "$y US Open – Women's singles") }),
        Championship("French Open", "french open|roland garros", { y -> listOf("$y French Open – Men's singles", "$y French Open – Women's singles") }),
        Championship("Australian Open", "australian open|aussie open", { y -> listOf("$y Australian Open – Men's singles", "$y Australian Open – Women's singles") }),
        Championship("Olympics", "(?:summer |winter )?olympics|olympic games", { y -> listOf("$y ${if (y % 4 == 2) "Winter" else "Summer"} Olympics medal table") }),
    )

    private val EVERYDAY = setOf("stars", "storm", "heat", "sky", "sun", "magic", "thunder", "wild", "fire", "fever", "dream", "liberty", "union", "jazz",
        "lightning", "mercury", "crew", "galaxy", "revolution", "forest", "sparks", "tempo", "wings", "blues", "kings", "rays", "aces", "twins",
        "angels", "pack", "birds", "boys", "bolts", "caps", "bills", "saints", "knights", "mammoth", "villa")

    private val SPORT_WORDS ="score|scores|game|games|match|matches|playing|play|played|win|won|beat|lose|lost|winning|losing|injur|hurt|news|record|standings|schedule|season|playoffs?|vs|versus|against|" +
        "pull (?:it|that|one) off|pulled (?:it|that|one) off|the w\\b|the dub\\b|a dub\\b|the l\\b|a w\\b|an l\\b|blow ?out|blew (?:it|them)|choke|choked"

    /** A sports question, or null. Teams count only with sports wording, so "pay the bills" stays out. */
    fun parse(raw: String, now: LocalDate = LocalDate.now()): Request.Sports? {
        var s = raw.lowercase(Locale.US).replace('’', '\'').replace(Patterns.get("[?!.,]"), " ").replace(Patterns.get("\\s+"), " ").trim()
        s = Colloquial.spoken(s).replace(LEAD, "").trim()
        if (s.isEmpty()) return null
        val leagues = League.said(s)
        val league = leagues.singleOrNull()

        // When: a year, "last year", "last night", "on sunday", "november 5 2023".
        var year = Patterns.get("\\b((?:19|20)\\d{2})\\b").find(s)?.groupValues?.get(1)?.toInt()
        if (year == null && Patterns.get("\\b(?:last year|last season)\\b").containsMatchIn(s)) year = now.year - 1
        if (year == null && Patterns.get("\\b(?:this year|this season)\\b").containsMatchIn(s)) year = now.year
        var date: LocalDate? = when {
            Patterns.get("\\b(?:day before yesterday)\\b").containsMatchIn(s) -> now.minusDays(2)
            Patterns.get("\\b(?:last night|yesterday|yesterday's|last nights?)\\b").containsMatchIn(s) -> now.minusDays(1)
            Patterns.get("\\b(?:tonight|today|today's|tonight's|this (?:afternoon|evening|morning)|right now|currently|live)\\b").containsMatchIn(s) -> now
            Patterns.get("\\btomorrow\\b").containsMatchIn(s) -> now.plusDays(1)
            else -> null
        }
        Patterns.get("\\b(?:on |last |this past )?($DAYS)\\b").find(s)?.let { m ->
            val d = DayOfWeek.valueOf(m.groupValues[1].uppercase(Locale.US))
            if (date == null) date = if (Patterns.get("\\b(?:next|this coming|upcoming)\\s+$").containsMatchIn(s.substring(0, m.range.first))) now.with(TemporalAdjusters.next(d))
                else now.with(TemporalAdjusters.previousOrSame(d))
        }
        Patterns.get("\\b($MONTHS) (\\d{1,2})(?:st|nd|rd|th)?(?: (\\d{4}))?\\b").find(s)?.let { m ->
            val month = monthOf(m.groupValues[1])
            val y = m.groupValues[3].toIntOrNull() ?: year ?: now.year
            runCatching { LocalDate.of(y, month, m.groupValues[2].toInt()) }.getOrNull()?.let { d ->
                date = if (m.groupValues[3].isEmpty() && year == null && d.isAfter(now.plusDays(7))) d.minusYears(1) else d
            }
        }

        // Championships: "who won the super bowl in 1998", "2010 world cup winner", "who are the nba champions".
        val asksWinner = Patterns.get("\\b(?:who won|who wins|who win|winners?|won the|champions?|champs|who took (?:home )?|who claimed|who lifted|who got|who is the (?:current |reigning )?|who are the (?:current |reigning )?|who's the (?:current |reigning )?|who were the|who was the|who came out on top|medal table|most (?:gold )?medals)\\b").containsMatchIn(s)
        val didWin = Patterns.get("^(?:did|have|has) .+ (?:win|won|ever win|ever won|take home|lift)\\b").containsMatchIn(s)
        if ((asksWinner || didWin) && !Patterns.get("\\b(?:game|match)\\b").containsMatchIn(s)) {
            CHAMPIONSHIPS.firstOrNull { it.said.containsMatchIn(s) }?.let { c ->
                // "who won super bowl 57" / "super bowl lvii".
                val num = if (c.name == "Super Bowl") Patterns.get("\\bsuper ?bowl ([ivxlc]+|\\d{1,2})\\b").find(s)?.groupValues?.get(1) else null
                return Request.Sports(SportsAsk(SportsWant.CHAMPION, teams = teamsIn(s), league = league, year = year, event = c.name, number = num))
            }
        }

        // "5 bucks" is money, not Milwaukee.
        val teams = teamsIn(s.replace(Patterns.get("\\b(?:\\d+|a few|few|couple|some|ten|twenty|fifty) bucks\\b"), " ")).toMutableList()
        // A team Brick's list doesn't have (college teams, other clubs): "the alabama game", "did auburn win".
        if (teams.isEmpty()) {
            (Patterns.get("^(?:what(?:'s| is| was)|how(?:'s| is| was)|who(?:'s| is)? (?:won|winning)) (?:the )?(?:score (?:of|in|for) )?(?:the )?([a-z0-9' ]{3,30}?) (?:game|match)(?: (?:score|going|today|tonight|last night|yesterday))?$").find(s)
                ?: Patterns.get("^did (?:the )?([a-z0-9' ]{3,30}?) (?:win|lose|beat|cover)\\b").find(s)
                ?: Patterns.get("^who won (?:the )?([a-z0-9' ]{3,30}?) (?:game|match)\\b").find(s))?.let { m ->
                val t = m.groupValues[1].trim().replace(Patterns.get("^(?:big|last|next|first|this|that|video|board|card|ball|football|basketball|baseball|hockey|soccer|super bowl|championship|playoff|home|away)\\b ?"), "").trim()
                if (t.isNotEmpty() && !Patterns.get("^(?:my|your|our|their|his|her|a|an|it|this|that|next|last|video|board|card|word|guessing|waiting|blame|long|end|zelda|mario|pokemon|minecraft|fortnite)\\b|" +
                        "\\b(?:newest|latest|new|best|worst|good|bad|top|upcoming|cheap|cheapest|free|old|favou?rite|classic|recent|popular|hardest|easiest|scariest|first|sequel|remake|switch|xbox|playstation|ps[45]|pc|steam|nintendo|mobile)\\b").containsMatchIn(t)) teams += t
            }
        }
        // "eagles vs cowboys", "eagles and cowboys game": both teams come from the list above or the forms here.

        // Players' health: "is joel embiid playing tonight", "embiid injury update", "how is embiid's knee", "who's hurt on the lakers".
        val injuryWords = Patterns.get("\\b(?:injur\\w*|hurt|injury report|ruled out|questionable|day to day|day-to-day|sidelined|health|healthy|condition|conditions|out for the season|on the il|injured list|ir\\b|status report)\\b")
        val bodyPart = "knee|ankle|hamstring|foot|back|shoulder|wrist|hand|hip|groin|calf|elbow|concussion|achilles|acl|thumb|finger|toe|neck|ribs?|oblique|quad|leg|arm|head|illness"
        if (teams.isEmpty() && league == null) {
            val playerForms = listOf(
                "^is (.+?) (?:playing|suiting up|active|available|going to play|gonna play|expected to play|healthy|out|injured|hurt|okay|ok)(?: (?:tonight|today|tomorrow|this week|this weekend|on \\w+day|against .+|in the .+ game|in game \\d))+$",
                "^is (.+?) (?:injured|hurt|out|healthy|still (?:injured|hurt|out)|back|back from injury)$",
                "^(?:what(?:'s| is) the )?(?:injury (?:update|status|report|news) (?:on|for) )(.+)$",
                "^(.+?)(?:'s|s')? (?:injury|injury update|injury status|injury report|injury news|health update|status update)$",
                "^how(?:'s| is) (.+?)(?:'s|s')? (?:$bodyPart|injury|health|recovery|rehab)(?: doing| looking| coming along)?$",
                "^what(?:'s| is) wrong with (.+?)(?:'s|s')?(?: $bodyPart)?$",
                "^what happened to (.+?)(?:'s|s')? (?:$bodyPart)$",
                "^when (?:is|will) (.+?) (?:be )?(?:back|return|returning|healthy|coming back)(?: from (?:injury|the il|his injury|her injury|their injury))?$",
            )
            for (p in playerForms) {
                val m = Patterns.get(p).find(s) ?: continue
                val name = m.groupValues[1].trim().removePrefix("the ").trim()
                if (name.split(' ').size in 1..4 && !Patterns.get("^(?:it|this|that|the (?:song|music|game|movie|show)|music|anything|something|someone|anyone|spotify|youtube|netflix|there|my|your|he|she|they|you|i|we|the weather|the store|the power|wifi|my \\w+)\\b").containsMatchIn(name)) {
                    return Request.Sports(SportsAsk(SportsWant.INJURIES, player = name, date = date))
                }
            }
        }

        val hasSportsWords = Patterns.get("\\b(?:$SPORT_WORDS)").containsMatchIn(s)
        val noTeams = teams.isEmpty()
        // Nothing to go on: no team, league or sports wording about games.
        if (noTeams && leagues.isEmpty() && !Patterns.get("\\b(?:scores|live games|(?:any|what|which) games (?:are )?(?:on|being played|going on|playing)(?: (?:tonight|today|right now|now|tv))?$|games (?:on (?:tonight|today|right now|now|tv)|on$|today|tonight|right now|going on)$|sports scores|current games|who's playing (?:tonight|today|right now|now)|who is playing (?:tonight|today|right now|now))\\b").containsMatchIn(s)) return null
        // Team names that are everyday words ("stars", "storm", "heat") need real sports wording: "can i see stars tonight" is the sky.
        val everyday = teams.isNotEmpty() && teams.all { it in EVERYDAY }
        if (everyday && !hasSportsWords) return null
        if (!noTeams && !hasSportsWords && !Patterns.get("\\b(?:doing|going|up|ahead|news|latest|what's (?:new|happening|going on)|update|updates|next|tonight|today|how did|did|last night|yesterday|this week|this weekend|on \\w+day|how good|this year|this season|last year|last season)\\b").containsMatchIn(s)) return null

        val aboutGame = Patterns.get("\\b(?:game|match|matchup|tonight|today)\\b").containsMatchIn(s)
        fun ask(w: SportsWant) = Request.Sports(SportsAsk(w, teams, league, date, year, aboutGame = aboutGame))
        return when {
            injuryWords.containsMatchIn(s) || Patterns.get("\\bwho(?:'s| is| are)? (?:out|missing|sitting|not playing)\\b|\\bwho(?:'s| is) (?:hurt|injured)\\b").containsMatchIn(s) ->
                if (noTeams && leagues.isEmpty()) null else ask(SportsWant.INJURIES)
            Patterns.get("\\b(?:news|headlines|latest on|latest with|latest news|what's (?:new|happening|going on) with|what is (?:new|happening|going on) with|updates? (?:on|about|for)|rumou?rs|trade talk|trades?)\\b").containsMatchIn(s) ->
                if (noTeams && leagues.isEmpty()) null else ask(SportsWant.NEWS)
            Patterns.get("\\b(?:record|standings?|win loss|w l|what place|in first place|how good (?:are|is)|where do .+ (?:rank|stand)|rank(?:ed|ing)?|seed)\\b").containsMatchIn(s) && !noTeams -> ask(SportsWant.RECORD)
            Patterns.get("\\b(?:when (?:do|does|is|are|will)|when's|next|play next|playing next|next match|who do .+ play|who does .+ play|who are .+ playing|who is .+ playing|what time (?:is|are|do|does)|schedule|is there a .+ game|are .+ playing (?:tonight|today|tomorrow|this week|on)|do .+ play (?:tonight|today|tomorrow|this week|on)|(?:is|are) .+ (?:on|playing) (?:tonight|today|tomorrow)|upcoming)\\b").containsMatchIn(s) && !noTeams -> ask(SportsWant.NEXT)
            Patterns.get("\\b(?:who won|did .+ (?:win|lose|beat|cover|get beat|pull (?:it|that|one) off|get the (?:w|win|dub)|get a (?:w|win|dub)|take the (?:l|loss)|take an l|blow it|choke)|how did .+ (?:do|play|fare|look)|final score|what was the score|what's the final|result|results|beat|outcome)\\b").containsMatchIn(s) && !noTeams ->
                if (date == null && year == null && Patterns.get("\\bwho won\\b").containsMatchIn(s) && teams.size == 1 && !Patterns.get("\\b(?:game|match)\\b").containsMatchIn(s)) ask(SportsWant.RESULT) else ask(SportsWant.RESULT)
            !noTeams && (year != null || (date != null && date!!.isBefore(now))) -> ask(SportsWant.RESULT)
            else -> ask(SportsWant.SCORE)
        }
    }

    private fun monthOf(m: String): Int = when (m.take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6; "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; else -> 12
    }

    // ------------------------------------------------------------------ ESPN

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss][.SSS]X")
    private fun time(s: String?): ZonedDateTime? = s?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it, TIME).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull() }

    private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    /** ESPN's teams for [league] (kept for days; college lists are long). */
    suspend fun teams(league: League): List<TeamRef> = withContext(Dispatchers.IO) {
        val r = Http.getJsonCached("${league.base}/teams?limit=1000") ?: return@withContext emptyList()
        arr(r.optJSONArray("sports")).flatMap { sp -> arr(sp.optJSONArray("leagues")) }.flatMap { l -> arr(l.optJSONArray("teams")) }.mapNotNull { w ->
            val t = w.optJSONObject("team") ?: return@mapNotNull null
            TeamRef(league, t.optString("id"), t.optString("displayName"), t.optString("shortDisplayName").ifBlank { t.optString("name") },
                t.optString("abbreviation"), t.optString("location"), arr(t.optJSONArray("logos")).firstOrNull()?.optString("href"))
        }
    }

    /** How well an ESPN team fits what was said: 3 a full name, 2 a nickname or alias, 1 the city or school. */
    private fun fit(t: TeamRef, said: String, entries: List<TeamName>): Int {
        val n = norm(said).removePrefix("the ")
        val names = listOf(t.name, t.short, t.abbr).map { norm(it) }
        if (entries.any { e -> norm(e.full) == norm(t.name) || (e.league == t.league && norm(e.nick) == norm(t.short)) }) return 3
        if (n == norm(t.name) || n in names) return 3
        if (norm(t.name).endsWith(" $n") || norm("${t.location} ${t.short}") == n) return 2
        if (n == norm(t.location)) return 1
        return 0
    }

    /** The ESPN teams [said] can mean, best first: from Brick's list's leagues, or every league ([hint] narrows them). */
    suspend fun resolve(said: String, hint: League?): List<TeamRef> = coroutineScope {
        val entries = known(said).filter { hint == null || it.league == hint }
        val leagues = entries.map { it.league }.distinct().ifEmpty { hint?.let { listOf(it) } ?: League.entries.toList() }
        leagues.map { l -> async { teams(l).map { it to fit(it, said, entries) }.filter { it.second > 0 } } }.awaitAll().flatten()
            .sortedWith(compareByDescending<Pair<TeamRef, Int>> { it.second }.thenBy { it.first.league.ordinal }).map { it.first }
            .distinctBy { it.league to it.id }
    }

    private fun side(c: JSONObject): Side? {
        val t = c.optJSONObject("team") ?: return null
        val score = when (val sc = c.opt("score")) {
            is JSONObject -> sc.optString("displayValue").ifBlank { null }
            is String -> sc.ifBlank { null }
            else -> null
        }
        val record = (arr(c.optJSONArray("records")).firstOrNull() ?: arr(c.optJSONArray("record")).firstOrNull())?.let { it.optString("summary").ifBlank { it.optString("displayValue") } }?.ifBlank { null }
        return Side(t.optString("id"), t.optString("displayName"), t.optString("shortDisplayName").ifBlank { t.optString("name").ifBlank { t.optString("displayName") } },
            t.optString("abbreviation"), score, if (c.has("winner")) c.optBoolean("winner") else null, record, c.optString("homeAway") == "home")
    }

    fun parseEvent(e: JSONObject, league: League): Game? {
        val comp = arr(e.optJSONArray("competitions")).firstOrNull() ?: return null
        val sides = arr(comp.optJSONArray("competitors")).mapNotNull { side(it) }
        val home = sides.firstOrNull { it.home } ?: sides.getOrNull(0) ?: return null
        val away = sides.firstOrNull { !it.home && it !== home } ?: sides.getOrNull(1) ?: return null
        val st = (comp.optJSONObject("status") ?: e.optJSONObject("status"))?.optJSONObject("type")
        val link = arr(e.optJSONArray("links")).firstOrNull { l -> l.optJSONArray("rel")?.toString()?.contains("summary") == true || l.optJSONArray("rel")?.toString()?.contains("desktop") == true }?.optString("href")
        val tv = arr(comp.optJSONArray("broadcasts")).firstOrNull()?.let { b -> b.optJSONArray("names")?.optString(0) ?: b.optJSONObject("media")?.optString("shortName") }?.ifBlank { null }
        return Game(e.optString("id"), league, time(e.optString("date")), st?.optString("state").orEmpty(), st?.optString("shortDetail").orEmpty().ifBlank { st?.optString("detail").orEmpty() },
            st?.optBoolean("completed") == true, home, away, comp.optJSONObject("venue")?.optString("fullName")?.ifBlank { null }, tv, link,
            arr(comp.optJSONArray("notes")).firstOrNull()?.optString("headline")?.ifBlank { null })
    }

    /** A day's games ([date] null: ESPN's current day or week). Live, so never from the cache unless the day is past. */
    suspend fun scoreboard(league: League, date: LocalDate? = null): List<Game> = withContext(Dispatchers.IO) {
        val q = listOfNotNull(date?.let { "dates=" + it.format(DateTimeFormatter.BASIC_ISO_DATE) }, league.query.ifEmpty { null }, "limit=300").joinToString("&")
        val url = "${league.base}/scoreboard?$q"
        val r = (if (date != null && date.isBefore(LocalDate.now().minusDays(1))) Http.getJsonCached(url) else Http.getJson(url)) ?: return@withContext emptyList()
        arr(r.optJSONArray("events")).mapNotNull { parseEvent(it, league) }
    }

    /** A team's season ([season] null: this one; [type] 2 regular season, 3 playoffs). */
    suspend fun schedule(t: TeamRef, season: Int? = null, type: Int? = null): Schedule = withContext(Dispatchers.IO) {
        val q = listOfNotNull(season?.let { "season=$it" }, type?.let { "seasontype=$it" }).joinToString("&")
        val url = "${t.league.base}/teams/${t.id}/schedule" + if (q.isNotEmpty()) "?$q" else ""
        val past = season != null && season < LocalDate.now().year - (if (t.league.split) 0 else 1)
        val r = (if (past) Http.getJsonCached(url) else Http.getJson(url)) ?: return@withContext Schedule(t, null, null, emptyList())
        val team = r.optJSONObject("team")
        Schedule(t, team?.optString("recordSummary")?.ifBlank { null }, team?.optString("standingSummary")?.ifBlank { null },
            arr(r.optJSONArray("events")).mapNotNull { parseEvent(it, t.league) })
    }

    /** The league's injury report. */
    suspend fun injuries(league: League): List<Injury> = withContext(Dispatchers.IO) {
        val r = Http.getJson("${league.base}/injuries") ?: return@withContext emptyList()
        arr(r.optJSONArray("injuries")).flatMap { team ->
            val name = team.optString("displayName")
            val id = team.optString("id")
            arr(team.optJSONArray("injuries")).mapNotNull { i ->
                val a = i.optJSONObject("athlete") ?: return@mapNotNull null
                Injury(a.optString("displayName"), name, id, i.optString("status").ifBlank { i.optJSONObject("type")?.optString("description").orEmpty() },
                    i.optJSONObject("details")?.optString("type")?.ifBlank { null }, i.optString("shortComment").ifBlank { null },
                    a.optJSONObject("position")?.optString("abbreviation")?.ifBlank { null })
            }
        }
    }

    data class Player(val name: String, val league: League?, val team: String?)

    /** A player by name, with their league and team (ESPN's search). */
    suspend fun player(name: String): Player? = withContext(Dispatchers.IO) {
        val r = Http.getJsonCached("https://site.web.api.espn.com/apis/common/v3/search?query=${Http.enc(name)}&limit=5&type=player") ?: return@withContext null
        val items = arr(r.optJSONArray("items")).filter { it.optString("type") == "player" || it.has("league") }
        val hit = items.firstOrNull { League.bySlug(it.optString("league")) != null } ?: items.firstOrNull() ?: return@withContext null
        Player(hit.optString("displayName"), League.bySlug(hit.optString("league")),
            arr(hit.optJSONArray("teamRelationships")).firstOrNull()?.optString("displayName")?.ifBlank { null })
    }

    /** ESPN's stories about [team] (or the whole league). */
    suspend fun news(league: League, team: TeamRef?): List<Headline> = withContext(Dispatchers.IO) {
        val r = Http.getJsonCached("${league.base}/news?limit=30" + (team?.let { "&team=${it.id}" } ?: "")) ?: return@withContext emptyList()
        arr(r.optJSONArray("articles")).filter { a ->
            team == null || arr(a.optJSONArray("categories")).any { c -> c.optString("type") == "team" && (c.optString("teamId") == team.id || c.optJSONObject("team")?.optString("id") == team.id) }
        }.mapNotNull { a ->
            val link = a.optJSONObject("links")?.optJSONObject("web")?.optString("href")?.ifBlank { null } ?: return@mapNotNull null
            Headline(a.optString("headline"), "ESPN", link, time(a.optString("published")))
        }
    }

    data class GameExtras(val injuries: List<Injury>, val leaders: List<String>, val news: List<Headline>)

    /** A game's injury list, top performers and stories. */
    suspend fun extras(g: Game): GameExtras = withContext(Dispatchers.IO) {
        val r = Http.getJson("${g.league.base}/summary?event=${g.id}") ?: return@withContext GameExtras(emptyList(), emptyList(), emptyList())
        val inj = arr(r.optJSONArray("injuries")).flatMap { t ->
            val team = t.optJSONObject("team")
            arr(t.optJSONArray("injuries")).mapNotNull { i ->
                val a = i.optJSONObject("athlete") ?: return@mapNotNull null
                Injury(a.optString("displayName"), team?.optString("displayName").orEmpty(), team?.optString("id").orEmpty(),
                    i.optString("status"), i.optJSONObject("details")?.optString("type")?.ifBlank { null }, null, a.optJSONObject("position")?.optString("abbreviation"))
            }
        }
        val leaders = arr(r.optJSONArray("leaders")).flatMap { t ->
            val abbr = t.optJSONObject("team")?.optString("abbreviation").orEmpty()
            arr(t.optJSONArray("leaders")).take(1).mapNotNull { cat ->
                val l = arr(cat.optJSONArray("leaders")).firstOrNull() ?: return@mapNotNull null
                "$abbr ${l.optJSONObject("athlete")?.optString("displayName")}: ${l.optString("displayValue")} (${cat.optString("displayName").lowercase(Locale.US)})"
            }
        }
        val news = arr(r.optJSONObject("news")?.optJSONArray("articles")).mapNotNull { a ->
            val link = a.optJSONObject("links")?.optJSONObject("web")?.optString("href")?.ifBlank { null } ?: return@mapNotNull null
            Headline(a.optString("headline"), "ESPN", link, time(a.optString("published")))
        }
        GameExtras(inj, leaders, news)
    }

    // ------------------------------------------------------------------ championships (Wikipedia)

    data class Champ(val title: String, val answer: String, val summary: String, val url: String, val year: Int)

    private val SENT = Regex("(?<=[.!?])\\s+(?=[A-Z\"“])")
    private val WON = Regex("\\b(?:defeated|defeating|beat|beating|upset|won|wins|clinched|claimed|captured|crowned|triumphed|edged|topped|retained|successfully defended|swept|prevailed)\\b", RegexOption.IGNORE_CASE)
    private val FUTURE = Regex("\\b(?:will be|is scheduled|is an upcoming|will take place|is set to|are scheduled|will determine|is planned)\\b", RegexOption.IGNORE_CASE)

    /** A Wikipedia page's whole intro, as plain text. */
    suspend fun intro(page: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val pages = Http.getJsonCached("https://en.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&exintro=1&explaintext=1&redirects=1&titles=${Http.enc(page)}")
            ?.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext null
        val p = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it) }.firstOrNull { !it.has("missing") } ?: return@withContext null
        val text = p.optString("extract").replace(Regex("\\s+"), " ").trim().ifBlank { return@withContext null }
        p.optString("title") to text
    }

    /** The sentence that says who won ("The Broncos upset the heavily favored Packers by the score of 31–24."). */
    fun winnerSentence(text: String): String? {
        val sentences = SENT.split(text).map { it.replace(Regex("\\s*\\([^)]*\\)"), "").trim() }
        return sentences.firstOrNull { WON.containsMatchIn(it) && !Regex("\\bdefending champions?\\b(?!.*\\b(?:defeated|beat|won)\\b)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }

    /** Who won [c] in [year] (the latest one when null); a Super Bowl can be asked by [number]. */
    suspend fun champion(c: Championship, year: Int?, number: String?): Champ? = withContext(Dispatchers.IO) {
        val now = LocalDate.now().year
        val years = year?.let { listOf(it) } ?: listOf(now, now - 1, now - 2)
        for (y in years) {
            val titles = if (number != null) listOf("Super Bowl ${roman(number)}") else c.pages(y)
            val found = titles.mapNotNull { t -> pageFor(t)?.let { p -> intro(p) } }
            if (found.isEmpty()) continue
            val text = found.joinToString(" ") { it.second }
            // Not played yet ("The 2027 World Series will be..."): the one before, unless a year was asked for.
            if (year == null && number == null && (FUTURE.containsMatchIn(SENT.split(found.first().second).take(2).joinToString(" ")) || winnerSentence(found.first().second) == null)) continue
            val answers = found.mapNotNull { (title, intro) -> winnerSentence(intro)?.let { w -> if (found.size > 1) "${title.substringAfter("– ").replace("singles", "singles:")} $w" else w } }
            val answer = answers.joinToString(" ").ifBlank { null } ?: continue
            val summary = SENT.split(found.first().second).take(2).joinToString(" ").replace(Regex("\\s*\\([^)]*\\)"), "")
            val title = found.first().first
            return@withContext Champ(if (found.size > 1) title.substringBefore(" –") else title, answer, summary,
                "https://en.m.wikipedia.org/wiki/${Http.enc(title.replace(' ', '_')).replace("+", "_")}", y)
        }
        null
    }

    /** The Wikipedia page a title or search leads to (exact titles first). */
    private suspend fun pageFor(term: String): String? {
        val r = Http.getJsonCached("https://en.wikipedia.org/w/api.php?action=query&format=json&list=search&srlimit=3&srsearch=${Http.enc(term)}")
        val hits = arr(r?.optJSONObject("query")?.optJSONArray("search")).map { it.optString("title") }
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(term)?.value
        return hits.firstOrNull { it.equals(term, true) } ?: hits.firstOrNull { h -> year == null || h.contains(year) || h.startsWith("Super Bowl") }
    }

    fun roman(n: String): String {
        val v = n.toIntOrNull() ?: return n.uppercase(Locale.US)
        if (v == 50) return "50"
        val map = listOf(50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var x = v; val sb = StringBuilder()
        for ((k, r) in map) while (x >= k) { sb.append(r); x -= k }
        return sb.toString()
    }

    // ------------------------------------------------------------------ saying it

    /**
     * A sentence cut down for saying aloud: side clauses go (", ending the longest drought...", ", which..."),
     * then anything past [max] characters, at a comma or word.
     */
    fun brief(s: String, max: Int = 170): String {
        var t = s.trim().replace(Regex("\\s*\\([^)]*\\)"), "")
        t = t.replace(Regex(",? (?:ending|which|marking|becoming|making it|making them|and became|while|in front of|surpassing|breaking|setting|earning|the first (?:time|team)|despite)\\b[^.]*(?=\\.?$)", RegexOption.IGNORE_CASE), "")
        t = t.trimEnd('.', ' ', ',')
        if (t.length > max) {
            val cut = t.lastIndexOf(", ", max).takeIf { it > max / 2 } ?: t.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
            t = t.substring(0, cut).trimEnd(',', ' ', ';', ':')
            // Never end on "...and their" or "...of the".
            val dangling = Regex("\\s+(?:and|or|but|the|a|an|of|to|in|on|at|for|with|by|from|as|its|their|his|her|our|your|which|that|who|is|are|was|were|before|after|until|while|when|then|into|than)$", RegexOption.IGNORE_CASE)
            while (dangling.containsMatchIn(t)) t = t.replace(dangling, "").trimEnd(',', ' ', ';', ':')
        }
        return "$t."
    }

    /** "the Knicks" / "Arsenal". */
    fun who(s: Side, league: League, cap: Boolean = false): String {
        val n = if (league.article) "the ${s.short}" else s.short
        return if (cap) n.replaceFirstChar { it.uppercase() } else n
    }

    /** "8:21 - 3rd" -> "with 8:21 left in the 3rd"; "Top 5th" -> "in the top of the 5th"; "Halftime" -> "at halftime". */
    fun spokenStatus(detail: String): String {
        val d = detail.trim()
        Regex("^(\\d+:\\d+) - (\\d+(?:st|nd|rd|th)|OT|\\dOT)$").find(d)?.let { return "with ${it.groupValues[1]} left in the ${it.groupValues[2].replace("OT", "overtime")}" }
        Regex("^(Top|Bot|Bottom|Mid|Middle|End) (\\d+(?:st|nd|rd|th))$", RegexOption.IGNORE_CASE).find(d)?.let {
            val half = when (it.groupValues[1].lowercase(Locale.US)) { "top" -> "top"; "bot", "bottom" -> "bottom"; "mid", "middle" -> "middle"; else -> "end" }
            return "in the $half of the ${it.groupValues[2]}"
        }
        if (Regex("^half", RegexOption.IGNORE_CASE).containsMatchIn(d)) return "at halftime"
        Regex("^End of (\\d+(?:st|nd|rd|th))", RegexOption.IGNORE_CASE).find(d)?.let { return "at the end of the ${it.groupValues[1]}" }
        Regex("^(\\d+)'$").find(d)?.let { return "in the ${it.groupValues[1]}th minute" }
        return "(${d.lowercase(Locale.US)})"
    }

    /** "tonight at 7:30 PM", "Sunday at 1:00 PM", "on November 5, 2023". */
    fun spokenWhen(t: ZonedDateTime?, now: ZonedDateTime = ZonedDateTime.now()): String {
        if (t == null) return ""
        val clock = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).replace(":00 ", " ")
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        return when {
            days == 0L -> if (t.hour >= 17) "tonight at $clock" else "today at $clock"
            days == 1L -> "tomorrow at $clock"
            days == -1L -> "yesterday"
            days in 2..6 -> "${t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} at $clock"
            days in -6..-2 -> "on ${t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}"
            // This year's dates go without the year.
            t.year == now.year -> "on " + t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)) + if (days > 0) " at $clock" else ""
            else -> "on " + t.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
        }
    }

    /** One game in a sentence: live, final or coming up. */
    fun line(g: Game, focus: TeamRef? = null, now: ZonedDateTime = ZonedDateTime.now()): String {
        val l = g.league
        val a = g.away; val h = g.home
        val first = focus?.let { g.side(it) } ?: a
        val second = if (first === a) h else a
        fun sc(s: Side) = s.score?.substringBefore('.') ?: "0"
        return when {
            g.state == "in" -> {
                val (lead, trail) = if ((sc(a).toIntOrNull() ?: 0) >= (sc(h).toIntOrNull() ?: 0)) a to h else h to a
                if (sc(a) == sc(h)) "${who(first, l, true)} and ${who(second, l)} are tied at ${sc(a)}, ${spokenStatus(g.detail)}."
                else "${who(lead, l, true)} ${if (l.article) "lead" else "leads"} ${who(trail, l)} ${sc(lead)} to ${sc(trail)}, ${spokenStatus(g.detail)}."
            }
            g.completed || g.state == "post" -> {
                val win = g.sides.firstOrNull { it.winner == true } ?: g.sides.maxByOrNull { sc(it).toIntOrNull() ?: 0 }!!
                val lose = if (win === a) h else a
                val whenSaid = spokenWhen(g.time, now)
                if (sc(a) == sc(h)) "${who(first, l, true)} and ${who(second, l)} tied ${sc(a)} to ${sc(h)} $whenSaid."
                else "${who(win, l, true)} beat ${who(lose, l)} ${sc(win)} to ${sc(lose)} $whenSaid" +
                    (if (g.detail.contains("OT", true) || g.detail.contains("/", true)) " (${g.detail.substringAfter("Final").trim(' ', '/').lowercase(Locale.US).ifBlank { "overtime" }})" else "") + "."
            }
            g.detail.contains("postponed", true) || g.detail.contains("canceled", true) || g.detail.contains("delayed", true) ->
                "${who(first, l, true)} and ${who(second, l)}: ${g.detail.lowercase(Locale.US)}."
            else -> "${who(first, l, true)} ${if (l.article || first.short.endsWith("s")) "play" else "plays"} ${if (first.home) "" else "at "}${who(second, l)} ${spokenWhen(g.time, now)}" +
                (g.tv?.let { " on $it" } ?: "") + "."
        }.replace(Regex("\\s+"), " ").replace(" .", ".")
    }

    /** A game on one line for the card: "NY 49  IND 58  Halftime". */
    fun row(g: Game): String {
        val score = if (g.state == "pre") "" else " ${g.away.score ?: "-"}"
        val score2 = if (g.state == "pre") "" else " ${g.home.score ?: "-"}"
        val status = if (g.state == "pre") g.time?.format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)) ?: g.detail else g.detail
        return "${g.away.abbr}$score @ ${g.home.abbr}$score2  $status"
    }
}
