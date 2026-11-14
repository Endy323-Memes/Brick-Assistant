package com.brick.history.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreTopicTest {
    @Test fun mainSubjectIsTheMostAskedAbout() {
        // "who is taylor swift", then "how old is taylor swift"
        assertEquals(listOf("taylor", "swift", "old"), Store.topicOf("taylor swift old taylor swift"))
        assertEquals(listOf("seattle"), Store.topicOf("~weather seattle seattle"))
        assertEquals(emptyList<String>(), Store.topicOf("~math ~math"))
    }
}
