package com.brick.assistant.brain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Answers a [SportsAsk]: what Brick says, and the card. Null when it couldn't work out the team or player. */
object SportsDesk {
    private val us = Locale.US

    class Answer(val line: String, val readout: Readout?)

    /** The leagues to check for scores when none is named, most popular first. */
    private val MAIN = listOf(League.NFL, League.NBA, League.MLB, League.NHL, League.WNBA, League.MLS, League.EPL)

    suspend fun answer(ask: SportsAsk, open: (String) -> Unit): Answer? = when (ask.want) {
        SportsWant.CHAMPION -> champion(ask, open)
        SportsWant.INJURIES -> if (ask.player != null) player(ask, open) else teamInjuries(ask, open)
        SportsWant.SCORE -> if (ask.teams.isEmpty()) scores(ask, open) else teamScore(ask, open)
        SportsWant.RESULT -> result(ask, open)
        SportsWant.NEWS -> news(ask, open)
        SportsWant.NEXT -> next(ask, open)
        SportsWant.RECORD -> record(ask, open)
    }

    /** What to search the web for when Brick can't answer. */
    fun fallbackQuery(ask: SportsAsk): String = listOfNotNull(
        ask.player, ask.teams.joinToString(" ").ifBlank { null }, ask.event, ask.year?.toString(),
        when (ask.want) { SportsWant.INJURIES -> "injury"; SportsWant.NEWS -> "news"; SportsWant.NEXT -> "next game"; SportsWant.RECORD -> "record"; else -> "score" },
    ).joinToString(" ")

    // ------------------------------------------------------------------ teams

    /** The team asked about, and the opponent when one is named, in the same league. */
    private suspend fun pair(ask: SportsAsk, date: LocalDate? = ask.date): Pair<TeamRef, TeamRef?>? = coroutineScope {
        val options = ask.teams.take(2).map { t -> async { Sports.resolve(t, ask.league) } }.awaitAll()
        val first = options.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@coroutineScope null
        val second = options.getOrNull(1).orEmpty()
        // Two teams: the league they share ("giants vs eagles" is football).
        if (second.isNotEmpty()) {
            for (a in first) second.firstOrNull { it.league == a.league && it.id != a.id }?.let { return@coroutineScope a to it }
        }
        val leagues = first.map { it.league }.distinct()
        if (leagues.size == 1 || ask.year != null) return@coroutineScope first.first() to null
        // One name, several leagues ("giants", "rangers"): the one playing on that day, or in season now.
        val day = date ?: LocalDate.now()
        val boards = leagues.map { l -> async { l to Sports.scoreboard(l, day) } }.awaitAll()
        for ((l, games) in boards) first.firstOrNull { t -> t.league == l && games.any { it.has(t) } }?.let { return@coroutineScope it to null }
        val active = boards.filter { it.second.isNotEmpty() }.map { it.first }
        (first.firstOrNull { it.league in active } ?: first.first()) to null
    }

    private fun name(t: TeamRef, cap: Boolean = false): String {
        val n = if (t.league.article) "the ${t.short}" else t.short
        return if (cap) n.replaceFirstChar { it.uppercase() } else n
    }

    private fun gameCard(title: String, games: List<Game>, extra: List<String> = emptyList(), open: (String) -> Unit, link: String? = null, image: String? = null): Readout {
        val rows = ArrayList<String>()
        for (g in games) {
            val l = g.league
            fun line(s: Side) = Box.kv(s.abbr.ifBlank { s.short.take(6) }, "") + (if (g.state == "pre") "" else (s.score ?: "-").padEnd(4)) +
                (if (s.winner == true) "<" else " ") + " " + s.short + (s.record?.let { " ($it)" } ?: "")
            rows += line(g.away)
            rows += line(g.home)
            rows += Box.kv("STATUS", if (g.state == "pre") g.time?.format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", us)) ?: g.detail else g.detail)
            if (g.state != "pre") g.time?.let { rows += Box.kv("DATE", it.format(DateTimeFormatter.ofPattern("EEE MMM d, yyyy", us))) }
            g.venue?.let { rows += Box.wrap(it, first = Box.kv("AT", ""), indent = 7, max = 2) }
            g.tv?.let { rows += Box.kv("TV", it) }
            g.note?.let { rows += Box.wrap(it, first = Box.kv("NOTE", ""), indent = 7, max = 2) }
            rows += Box.kv("LEAGUE", l.label)
            rows += ""
        }
        if (extra.isNotEmpty()) rows += extra else if (rows.lastOrNull() == "") rows.removeAt(rows.size - 1)
        rows += ""
        rows += Box.kv("SOURCE", "ESPN")
        val controls = listOfNotNull((link ?: games.firstOrNull()?.link)?.let { u -> Control.Button("Open on ESPN") { open(u) } })
        return Readout(Box.build(title, rows), image = image, controls = controls)
    }

    /** Injuries and top performers for one game, for the card. */
    private suspend fun gameExtras(g: Game): List<String> {
        val x = Sports.extras(g)
        val rows = ArrayList<String>()
        if (x.leaders.isNotEmpty()) {
            rows += "LEADERS"
            x.leaders.take(4).forEach { rows += Box.wrap(it, "- ", indent = 2, max = 2) }
            rows += ""
        }
        if (x.injuries.isNotEmpty()) {
            rows += "INJURIES"
            x.injuries.groupBy { it.team }.forEach { (team, list) ->
                rows += Box.wrap(team, max = 1)
                list.take(6).forEach { rows += Box.wrap("${it.player}${it.position?.let { p -> " $p" } ?: ""} - ${it.status}${it.type?.let { t -> " ($t)" } ?: ""}", "- ", indent = 2, max = 2) }
            }
            rows += ""
        }
        if (x.news.isNotEmpty()) {
            rows += "NEWS"
            x.news.take(3).forEach { rows += Box.wrap(it.title, "- ", indent = 2, max = 2) }
            rows += ""
        }
        if (rows.lastOrNull() == "") rows.removeAt(rows.size - 1)
        return rows
    }

    // ------------------------------------------------------------------ scores

    /** "nba scores", "what games are on": every game today in the named leagues (or the main ones in season). */
    private suspend fun scores(ask: SportsAsk, open: (String) -> Unit): Answer = coroutineScope {
        val leagues = ask.league?.let { listOf(it) } ?: League.said(fallbackQuery(ask)).ifEmpty { MAIN }
        val boards = leagues.map { l -> async { l to Sports.scoreboard(l, ask.date) } }.awaitAll().filter { it.second.isNotEmpty() }
        val all = boards.flatMap { it.second }
        if (all.isEmpty()) return@coroutineScope Answer("I don't see any ${ask.league?.label?.let { "$it " } ?: ""}games${if (ask.date == null) " today" else " ${Sports.spokenWhen(ask.date.atStartOfDay(java.time.ZoneId.systemDefault()))}"}.", null)
        val live = all.filter { it.state == "in" }
        val done = all.filter { it.state == "post" }
        val later = all.filter { it.state == "pre" }
        val sayFrom = live.ifEmpty { done.ifEmpty { later } }
        val head = when {
            live.isNotEmpty() -> "${live.size} game${if (live.size == 1) " is" else "s are"} on now."
            done.isNotEmpty() && later.isEmpty() -> "Here are the finals."
            done.isEmpty() -> "Nothing's on yet. ${later.size} game${if (later.size == 1) "" else "s"} coming up."
            else -> "${done.size} final${if (done.size == 1) "" else "s"} so far, ${later.size} still to play."
        }
        // One game said; the card has them all.
        val line = head + " " + sayFrom.take(1).joinToString(" ") { Sports.line(it) }
        val rows = ArrayList<String>()
        for ((l, games) in boards) {
            rows += l.label.uppercase(us)
            (games.filter { it.state == "in" } + games.filter { it.state == "post" } + games.filter { it.state == "pre" }).take(16).forEach { rows += Box.wrap(Sports.row(it), "  ", max = 2) }
            rows += ""
        }
        rows += Box.kv("SOURCE", "ESPN")
        val title = (ask.league?.label?.uppercase(us)?.let { "$it SCORES" } ?: "SCORES")
        val controls = boards.take(3).map { (l, _) -> Control.Button("${l.label} on ESPN") { open("https://www.espn.com/${espnPath(l)}/scoreboard") } }
        Answer(line, Readout(Box.build(title, rows), controls = controls))
    }

    private fun espnPath(l: League) = when (l) {
        League.NCAAF -> "college-football"; League.NCAAM -> "mens-college-basketball"; League.MLS, League.EPL -> "soccer"; else -> l.slug
    }

    /** "what's the knicks score", "how are the eagles doing": today's game, else the last and next. */
    private suspend fun teamScore(ask: SportsAsk, open: (String) -> Unit): Answer? {
        val (t, o) = pair(ask) ?: return null
        val today = Sports.scoreboard(t.league, ask.date).filter { it.has(t) && (o == null || it.has(o)) }
        val g = today.firstOrNull { it.state == "in" } ?: today.firstOrNull { it.state == "post" } ?: today.firstOrNull()
        if (g != null && (g.state != "pre" || ask.date != null)) {
            return Answer(Sports.line(g, t), gameCard("${t.short.uppercase(us)} GAME", listOf(g), gameExtras(g), open, image = t.logo))
        }
        // No game on: the last one and the next one.
        val sch = Sports.schedule(t)
        val last = sch.games.lastOrNull { it.completed && (o == null || it.has(o)) }
        val next = (listOfNotNull(g) + sch.games.filter { !it.completed && it.state != "post" && (o == null || it.has(o)) }).minByOrNull { it.time ?: ZonedDateTime.now().plusYears(1) }
        // Not playing now: the last game, and the next one only when there's no last.
        val parts = listOfNotNull(
            last?.let { "Last time out, " + Sports.line(it, t).replaceFirstChar { c -> c.lowercase() } },
            next?.takeIf { last == null || g != null }?.let { "Next, " + Sports.line(it, t).replaceFirstChar { c -> c.lowercase() } },
        )
        if (last == null && next == null) return Answer("I couldn't find any games for ${name(t)}.", null)
        return Answer(parts.joinToString(" "), gameCard(t.name.uppercase(us), listOfNotNull(last, next), listOfNotNull(sch.record?.let { Box.kv("RECORD", it + (sch.standing?.let { s -> ", $s" } ?: "")) }), open, image = t.logo))
    }

    // ------------------------------------------------------------------ results

    /** "who won the eagles cowboys game in 2023", "did the yankees win last night", "how did the knicks do on sunday". */
    private suspend fun result(ask: SportsAsk, open: (String) -> Unit): Answer? {
        val (t, o) = pair(ask) ?: return null
        val games: List<Game> = when {
            ask.date != null -> Sports.scoreboard(t.league, ask.date).filter { it.has(t) && (o == null || it.has(o)) }
            ask.year != null -> seasonGames(t, ask.year).filter { o == null || it.has(o) }
            else -> {
                // The latest one (against that opponent, looking back a season when needed).
                val now = Sports.schedule(t).games.filter { it.completed && (o == null || it.has(o)) }
                now.takeLast(1).ifEmpty {
                    val last = LocalDate.now().year - if (t.league.split) 0 else 1
                    seasonGames(t, last).filter { o == null || it.has(o) }.takeLast(1)
                }
            }
        }.filter { it.completed || it.state != "pre" }
        val vs = o?.let { " against ${name(it)}" } ?: ""
        if (games.isEmpty()) {
            val whenSaid = ask.date?.let { " " + Sports.spokenWhen(it.atTime(12, 0).atZone(java.time.ZoneId.systemDefault())) } ?: ask.year?.let { " in $it" } ?: ""
            return Answer("I couldn't find a game for ${name(t)}$vs$whenSaid.", null)
        }
        val said = when {
            games.size == 1 -> Sports.line(games[0], t)
            o != null -> {
                val wins = games.count { g -> g.side(t).winner == true }
                "${name(t, true)} went $wins and ${games.size - wins} against ${name(o)}${ask.year?.let { " in $it" } ?: ""}. Last time, " +
                    Sports.line(games.last(), t).replaceFirstChar { it.lowercase() }
            }
            else -> {
                val wins = games.count { g -> g.side(t).winner == true }
                val losses = games.count { g -> g.other(t).winner == true }
                "${name(t, true)} went $wins and $losses${ask.year?.let { " in $it" } ?: ""}. The last one: ${Sports.line(games.last(), t)}"
            }
        }
        val shown = if (o == null && games.size > 6) games.takeLast(6) else games.takeLast(8)
        val title = if (o != null) "${t.abbr} VS ${o.abbr}${ask.year?.let { " $it" } ?: ""}" else "${t.short.uppercase(us)}${ask.year?.let { " $it" } ?: ""}"
        val extra = if (shown.size == 1) gameExtras(shown[0]) else emptyList()
        return Answer(said, gameCard(title, shown, extra, open, image = t.logo))
    }

    /** Every game [t] played in calendar [year] (split seasons span two of ESPN's seasons), playoffs included. */
    private suspend fun seasonGames(t: TeamRef, year: Int): List<Game> = coroutineScope {
        val seasons = if (t.league.split) listOf(year, year + 1) else listOf(year)
        val all = seasons.flatMap { s -> listOf(2, 3).map { type -> async { Sports.schedule(t, s, type).games } } }.awaitAll().flatten()
            .distinctBy { it.id }.sortedBy { it.time }
        if (t.league.split) all.filter { it.time?.year == year } else all
    }

    // ------------------------------------------------------------------ what's next, the record

    private suspend fun next(ask: SportsAsk, open: (String) -> Unit): Answer? {
        val (t, o) = pair(ask) ?: return null
        var games = Sports.schedule(t).games.filter { !it.completed && it.state != "post" && (o == null || it.has(o)) }
        if (games.isEmpty()) games = Sports.schedule(t, type = 3).games.filter { !it.completed && it.state != "post" && (o == null || it.has(o)) }
        val g = games.minByOrNull { it.time ?: ZonedDateTime.now().plusYears(1) }
            ?: return Answer("I don't see another game on ${name(t)}'s schedule${o?.let { " against ${name(it)}" } ?: ""}.", null)
        val line = if (g.state == "in") "${name(t, true)} ${if (t.league.article) "are" else "is"} playing now. " + Sports.line(g, t) else Sports.line(g, t)
        return Answer(line, gameCard("NEXT: ${t.short.uppercase(us)}", games.sortedBy { it.time }.take(4), emptyList(), open, image = t.logo))
    }

    private suspend fun record(ask: SportsAsk, open: (String) -> Unit): Answer? {
        val (t, _) = pair(ask) ?: return null
        val sch = if (ask.year != null) Sports.schedule(t, if (t.league.split) ask.year + 1 else ask.year) else Sports.schedule(t)
        val rec = sch.record ?: return Answer("I couldn't find ${name(t)}'s record.", null)
        val (w, rest) = rec.split("-", limit = 2).let { it[0] to it.getOrNull(1) }
        val spoken = "${name(t, true)} ${if (t.league.article) "are" else "is"} $w and ${rest?.replace("-", " and ")}${ask.year?.let { " for $it" } ?: ""}" +
            (sch.standing?.let { ", ${it.replaceFirstChar { c -> c.lowercase() }}" } ?: "") + "."
        val recent = sch.games.filter { it.completed }.takeLast(3)
        return Answer(spoken, gameCard(t.name.uppercase(us), recent, listOf(Box.kv("RECORD", rec)) + (sch.standing?.let { Box.wrap(it, first = Box.kv("PLACE", ""), indent = 7, max = 2) } ?: emptyList()), open, image = t.logo))
    }

    // ------------------------------------------------------------------ injuries

    private fun statusSaid(i: Injury) = i.status.lowercase(us).replace("day-to-day", "day to day")

    /** "who's hurt on the lakers", "injury report for the knicks game", "conditions of the players in the eagles game". */
    private suspend fun teamInjuries(ask: SportsAsk, open: (String) -> Unit): Answer? = coroutineScope {
        if (ask.teams.isEmpty()) {
            val l = ask.league ?: return@coroutineScope null
            val all = Sports.injuries(l)
            val rows = all.groupBy { it.team }.flatMap { (team, list) -> listOf(team.uppercase(us)) + list.take(4).map { "- ${it.player} - ${it.status}" } + "" }
            return@coroutineScope Answer("The ${l.label} injury report has ${all.size} players on it.", Readout(Box.build("${l.label.uppercase(us)} INJURIES", rows.take(60) + Box.kv("SOURCE", "ESPN"))))
        }
        val (t, o) = pair(ask) ?: return@coroutineScope null
        // "the knicks game": both teams in today's (or the next) game.
        val game = if (o == null && ask.aboutGame) Sports.scoreboard(t.league).firstOrNull { it.has(t) } else null
        val teams = listOfNotNull(t, o) + listOfNotNull(game?.let { g -> g.other(t).let { s -> TeamRef(t.league, s.id, s.name, s.short, s.abbr, "", null) } })
        val report = Sports.injuries(t.league)
        val byTeam = teams.distinctBy { it.id }.map { tm -> tm to report.filter { it.teamId == tm.id || it.team.equals(tm.name, true) } }
        val said = byTeam.joinToString(" ") { (tm, list) ->
            if (list.isEmpty()) "${name(tm, true)} have nobody on the injury report."
            else {
                val out = list.filter { it.status.contains("out", true) || it.status.contains("injured reserve", true) || it.status.equals("IR", true) }
                val doubt = list.filter { it !in out }
                listOfNotNull(
                    out.takeIf { it.isNotEmpty() }?.let { o2 -> "${name(tm, true)} ${if (o2.size == 1) "have one player" else "have ${o2.size} players"} out: " + o2.take(2).joinToString(", ") { it.player } + (if (o2.size > 2) ", and more" else "") + "." },
                    doubt.takeIf { it.isNotEmpty() && out.isEmpty() }?.let { d -> "${name(tm, true)}: " + d.take(1).joinToString(", ") { "${it.player} is ${statusSaid(it)}" } + (if (d.size > 1) ", and ${d.size - 1} more" else "") + "." },
                ).joinToString(" ")
            }
        }
        val rows = ArrayList<String>()
        for ((tm, list) in byTeam) {
            rows += tm.name.uppercase(us)
            if (list.isEmpty()) rows += "  Nobody listed"
            list.take(10).forEach { i ->
                rows += Box.wrap("${i.player}${i.position?.let { " $it" } ?: ""} - ${i.status}${i.type?.let { " ($it)" } ?: ""}", "- ", indent = 2, max = 2)
                i.note?.let { n -> rows += Box.wrap(n, "  ", indent = 2, max = 3) }
            }
            rows += ""
        }
        rows += Box.kv("SOURCE", "ESPN")
        val controls = listOfNotNull(game?.link?.let { u -> Control.Button("Open game") { open(u) } }) +
            Control.Button("${t.league.label} injuries") { open("https://www.espn.com/${espnPath(t.league)}/injuries") }
        Answer(said, Readout(Box.build("INJURY REPORT", rows), image = t.logo, controls = controls))
    }

    /** "is joel embiid playing tonight", "how is embiid's knee". */
    private suspend fun player(ask: SportsAsk, open: (String) -> Unit): Answer? {
        val p = Sports.player(ask.player ?: return null) ?: return null
        val l = p.league ?: return Answer("I found ${p.name}, but I can only check injury reports for the NFL, NBA, MLB, NHL, WNBA, MLS, the Premier League and college football and basketball.", null)
        val report = Sports.injuries(l)
        val hit = report.firstOrNull { it.player.equals(p.name, true) }
        val rows = ArrayList<String>()
        rows += Box.wrap(p.name, max = 1)
        p.team?.let { rows += Box.kv("TEAM", it) }
        rows += Box.kv("LEAGUE", l.label)
        val said = if (hit != null) {
            rows += Box.kv("STATUS", hit.status)
            hit.type?.let { rows += Box.kv("INJURY", it) }
            hit.note?.let { rows += ""; rows += Box.wrap(it, max = 6) }
            "${p.name} is listed as ${statusSaid(hit)}${hit.type?.let { " with a ${it.lowercase(us)} injury" } ?: ""}."
        } else {
            rows += Box.kv("STATUS", "Not on the injury report")
            "${p.name} isn't on the ${l.label} injury report."
        }
        // Their team's next game, when it's soon.
        val team = p.team?.let { Sports.resolve(it, l).firstOrNull() }
        val next = team?.let { tm -> Sports.scoreboard(l).firstOrNull { it.has(tm) } ?: Sports.schedule(tm).games.firstOrNull { !it.completed && it.state != "post" } }
        // Said only when it's today or tomorrow; the card always shows it.
        val nextSaid = next?.takeIf { it.time != null && it.time.toLocalDate() <= LocalDate.now().plusDays(1) }?.let { " " + Sports.line(it, team) } ?: ""
        next?.let { rows += ""; rows += Box.wrap(Sports.row(it), first = Box.kv("NEXT", ""), indent = 7, max = 2) }
        rows += ""
        rows += Box.kv("SOURCE", "ESPN")
        return Answer(said + nextSaid, Readout(Box.build("PLAYER STATUS", rows), image = team?.logo,
            controls = listOf(Control.Button("${l.label} injuries") { open("https://www.espn.com/${espnPath(l)}/injuries") })))
    }

    // ------------------------------------------------------------------ news

    private suspend fun news(ask: SportsAsk, open: (String) -> Unit): Answer? {
        val pair = if (ask.teams.isNotEmpty()) pair(ask) else null
        val t = pair?.first
        val l = t?.league ?: ask.league ?: return null
        var items = Sports.news(l, t)
        // Few team stories on ESPN: Google News fills in.
        if (t != null && items.size < 3) items = (items + News.fetch(NewsTopic.TOP, "\"${t.name}\"")).distinctBy { it.title }
        if (items.isEmpty()) return Answer("I couldn't find any news on ${t?.let { name(it) } ?: l.label}.", null)
        val now = ZonedDateTime.now()
        val who = t?.let { name(it) } ?: l.label
        val line = Sports.brief(items.first().title, 110)
        val rows = items.take(8).flatMapIndexed { i, h ->
            Box.wrap(h.title, "${i + 1}. ", 3) + listOfNotNull(listOfNotNull(h.source, News.ago(h.published, now)).joinToString(" · ").ifEmpty { null }?.let { "   $it" }) + ""
        }.dropLast(1)
        val controls = items.take(3).mapIndexed { i, h -> Control.Button("Open story ${i + 1}") { open(h.link) } }
        return Answer(line, Readout(Box.build("NEWS: ${(t?.name ?: l.label).uppercase(us)}", rows), image = t?.logo, controls = controls))
    }

    // ------------------------------------------------------------------ championships

    private suspend fun champion(ask: SportsAsk, open: (String) -> Unit): Answer? {
        val c = Sports.CHAMPIONSHIPS.firstOrNull { it.name == ask.event } ?: return null
        val r = Sports.champion(c, ask.year, ask.number) ?: return Answer("I couldn't find who won the ${c.name}${ask.year?.let { " in $it" } ?: ""}.", null)
        // "did the eagles win the super bowl in 2018": yes or no first.
        val team = ask.teams.firstOrNull()?.let { said -> Sports.known(said).firstOrNull() }
        val line = if (team != null) {
            val won = Regex("\\b${Regex.escape(team.nick)}\\b[^.]*\\b(?:defeated|beat|won|upset|clinched|captured|swept|edged)\\b|\\b(?:defeated|beat|upset|edged)\\b[^.]*\\bthe \\w+ \\w*\\b", RegexOption.IGNORE_CASE)
            val nickFirst = Regex("\\b${Regex.escape(team.nick)}\\b", RegexOption.IGNORE_CASE).find(r.answer)
            val verb = Regex("\\b(?:defeated|beat|won|upset|clinched|captured|swept|edged)\\b", RegexOption.IGNORE_CASE).find(r.answer)
            val yes = nickFirst != null && verb != null && nickFirst.range.first < verb.range.first && won.containsMatchIn(r.answer)
            (if (yes) "Yes. " else "No. ") + Sports.brief(r.answer, 120)
        } else Sports.brief(r.answer, if (r.answer.contains("singles:")) 200 else 120)
        val rows = ArrayList<String>()
        rows += Box.wrap(r.title, max = 2)
        rows += ""
        rows += Box.wrap(r.answer, "> ", indent = 2, max = 8)
        if (r.summary.isNotBlank() && r.summary != r.answer) { rows += ""; rows += Box.wrap(r.summary, max = 6) }
        rows += ""
        rows += Box.kv("SOURCE", "Wikipedia")
        return Answer(line, Readout(Box.build(c.name.uppercase(us), rows), controls = listOf(Control.Button("Wikipedia") { open(r.url) })))
    }
}
