package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhobiasTest {
    private val all = Phobias.ALL.map { it.key }.toSet()
    private fun keys(s: String) = Phobias.hits(s, all).map { it.key }.toSet()

    @Test fun catchesTheSubject() {
        for ((s, want) in listOf(
            "do spiders bite" to "arachnophobia",
            "how big do tarantulas get" to "arachnophobia",
            "is a black widow deadly" to "arachnophobia",
            "CLASS  Arachnid" to "arachnophobia",
            "how do i get rid of ticks" to "arachnophobia",
            "how do i get rid of cockroaches" to "entomophobia",
            "what do fruit flies eat" to "entomophobia",
            "are rattlesnakes venomous" to "ophidiophobia",
            "how big is a burmese python" to "ophidiophobia",
            "do sharks attack humans" to "galeophobia",
            "what lives at the bottom of the mariana trench" to "thalassophobia",
            "what is a lotus seed pod" to "trypophobia",
            "how long do rats live" to "musophobia",
            "why do pigeons bob their heads" to "ornithophobia",
            "how many pit bulls are there" to "cynophobia",
            "tell me about pennywise" to "coulrophobia",
            "how much blood is in the human body" to "hemophobia",
            "do flu shots hurt" to "trypanophobia",
            "what causes throwing up" to "emetophobia",
        )) assertTrue("$s → $want, got ${keys(s)}", want in keys(s))
    }

    @Test fun leavesOtherThingsAlone() {
        for (s in listOf(
            "who plays spider-man", "spiderman no way home", "what is ant-man about", "how do i change my mouse sensitivity",
            "best hot dog in chicago", "what is python", "is the app a bug", "tell me about cobra kai", "who is al gore",
            "how tall is the space needle", "what is my blood pressure supposed to be", "time flies", "what is a tickets price",
            "how deep can you dive", "what's the weather",
        )) assertEquals(s, emptySet<String>(), keys(s))
    }

    @Test fun onlyFiltersThatAreOn() {
        assertEquals(emptyList<Phobias.Phobia>(), Phobias.hits("do spiders bite", setOf("galeophobia")))
        assertEquals(listOf("arachnophobia"), Phobias.hits("do spiders bite", setOf("arachnophobia", "galeophobia")).map { it.key })
        assertEquals(emptyList<Phobias.Phobia>(), Phobias.hits("do spiders bite", emptySet()))
    }
}
