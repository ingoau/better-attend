package au.ingo.betterattend.ui.people

import org.junit.Assert.assertEquals
import org.junit.Test

class SlackLinksTest {
    @Test fun appLinkOpensADmInHackClubsSlack() {
        assertEquals("slack://user?team=T0266FRGM&id=U09K59BPM2M", SlackLinks.app(" U09K59BPM2M "))
    }

    @Test fun webLinkIsTheirProfile() {
        assertEquals("https://hackclub.slack.com/team/U09K59BPM2M", SlackLinks.web("U09K59BPM2M"))
    }

    @Test fun oddCharactersAreEncoded() {
        assertEquals("slack://user?team=T0266FRGM&id=U1%26x%3D2", SlackLinks.app("U1&x=2"))
    }
}
