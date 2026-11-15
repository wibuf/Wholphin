package com.github.damontecres.wholphin.ui.main

import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.MyRequests
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Fork (GooseFlix #1636): the My requests row's status badge and header text */
class MyRequestsRowTest {
    private fun item(
        type: SeerrItemType = SeerrItemType.MOVIE,
        availability: SeerrAvailability = SeerrAvailability.PENDING,
        releaseDate: LocalDate? = null,
    ) = DiscoverItem(603, type, "The Matrix", null, null, availability, releaseDate, null, null, null, null)

    @Test
    fun `Media Adder states map to the card badge`() {
        assertEquals(SeerrAvailability.AVAILABLE, MyRequests.availabilityFor("available"))
        assertEquals(SeerrAvailability.PROCESSING, MyRequests.availabilityFor("downloading"))
        assertEquals(SeerrAvailability.PROCESSING, MyRequests.availabilityFor("queued"))
        assertEquals(SeerrAvailability.PENDING, MyRequests.availabilityFor("searching"))
        assertEquals(SeerrAvailability.PENDING, MyRequests.availabilityFor("unknown"))
    }

    @Test
    fun `header uses Media Adder's label and progress`() {
        assertEquals("Movie · Downloading 42%", requestFacts(item(), "Downloading", 42.7))
        assertEquals("Show · Finding a copy", requestFacts(item(type = SeerrItemType.TV), "Finding a copy", null))
    }

    @Test
    fun `percentage isn't doubled when the label has it`() {
        assertEquals("Movie · Downloading (42%)", requestFacts(item(), "Downloading (42%)", 42.0))
    }

    @Test
    fun `without a label it falls back to the availability`() {
        assertEquals(
            "1999 · Movie · Downloading",
            requestFacts(item(availability = SeerrAvailability.PROCESSING, releaseDate = LocalDate.of(1999, 3, 31))),
        )
        assertEquals("Movie · Requested", requestFacts(item()))
    }
}
