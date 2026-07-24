package com.portal.commons.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2's accept rule ([VoskPhraseVerifier.containsPhrase]) — the whole declared phrase must decode,
 * intact, but contamination around it is tolerated.
 *
 * The balance here is load-bearing in both directions and was set by measurement, not taste
 * (`hey-jarvis/PHASE_B.md` §1d): too loose and ordinary conversation about "jarvis" fires the wake; too
 * strict and the cascade gives back the far-field recall it exists to buy.
 */
class VoskPhraseVerifierTest {

    private fun match(text: String, phrase: String = "hey jarvis") = VoskPhraseVerifier.containsPhrase(text, phrase)

    // ---- the phrase must be there in full ------------------------------------------------------------

    @Test fun acceptsTheCleanPhrase() {
        assertTrue(match("hey jarvis"))
    }

    @Test fun rejectsABareKeyword() {
        // The regression this rule exists for: a live conversation decoded `jarvis [unk]` and the old
        // keyword-only check fired on it. The wake phrase is "hey jarvis" — a bare "jarvis" is not a wake.
        assertFalse(match("jarvis"))
        assertFalse(match("jarvis [unk]"))
        assertFalse(match("[unk] jarvis [unk]"))
    }

    @Test fun rejectsALoneLead() {
        assertFalse(match("hey"))
        assertFalse(match("[unk] hey [unk]"))
    }

    @Test fun rejectsAnEmptyOrSilentDecode() {
        assertFalse(match(""))
        assertFalse(match("   "))
        assertFalse(match("[unk] [unk]"))
    }

    @Test fun requiresTheWordsAdjacentAndInOrder() {
        assertFalse(match("jarvis hey"))
        assertFalse(match("hey [unk] jarvis"))
    }

    // ---- but contamination around it is fine — this is NOT WakeMatcher's strict gate -----------------

    @Test fun toleratesUnkOnEitherSide() {
        // Stage 1 has already established the phrase was spoken; demanding an uncontaminated decode
        // measured strictly worse on MIC positives and removed no additional false accepts.
        assertTrue(match("[unk] hey jarvis"))
        assertTrue(match("hey jarvis [unk]"))
        assertTrue(match("[unk] hey jarvis [unk]"))
    }

    @Test fun toleratesSurroundingSpeech() {
        assertTrue(match("[unk] [unk] hey jarvis [unk]"))
    }

    @Test fun isWholeWordNotSubstring() {
        // "jarvis" must be a token — a longer word containing it is not a match.
        assertFalse(match("hey jarvisson"))
        assertFalse(match("heyjarvis"))
    }

    @Test fun toleratesIrregularWhitespace() {
        assertTrue(match("  hey   jarvis  "))
    }

    // ---- other phrases, including a lead-less one ----------------------------------------------------

    @Test fun worksForAnArbitraryPluginPhrase() {
        assertTrue(match("hi bob", phrase = "hi bob"))
        assertFalse(match("bob", phrase = "hi bob"))
    }

    @Test fun aLeadlessPhraseReducesToTheKeywordCheck() {
        // A wake word with no declared lead has phrase == keyword; there is no lead to require.
        assertTrue(match("computer", phrase = "computer"))
        assertTrue(match("[unk] computer [unk]", phrase = "computer"))
        assertFalse(match("[unk]", phrase = "computer"))
    }

    @Test fun doesNotConfuseOneWakePhraseForAnother() {
        // Cross-phrase confirmation would let a "jarvis" candidate fire on a decode of "hey alexa".
        assertFalse(match("hey alexa", phrase = "hey jarvis"))
        assertFalse(match("hey jarvis", phrase = "hey alexa"))
    }

    @Test fun emptyPhraseNeverMatches() {
        assertFalse(match("hey jarvis", phrase = ""))
    }
}
