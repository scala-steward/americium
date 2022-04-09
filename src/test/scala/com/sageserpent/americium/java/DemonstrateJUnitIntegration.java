package com.sageserpent.americium.java;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;

import static java.lang.Math.abs;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


public class DemonstrateJUnitIntegration {
    @BeforeEach
    void beforeEach() {
        System.out.println("Before each...");
    }

    @AfterEach
    void afterEach() {
        System.out.println("...after each.");
    }

    private static final TrialsApi api = Trials.api();

    private static final Trials<Long> longs = api.longs();

    private static final Trials<String> strings =
            api.choose("Fred", "Harold", "Ethel");

    private static final Trials<String> first =
            api.integers(1, 10)
               .flatMap(size -> api
                       .characters('a', 'z', 'a')
                       .collectionsOfSize(size, Builder::stringBuilder));

    private static final Trials<String> second =
            api.integers(0, 10)
               .flatMap(size -> api
                       .characters('0', '9', '0')
                       .collectionsOfSize(size, Builder::stringBuilder));

    @TrialsTest(trials = "longs", casesLimit = 100)
    void testWithALong(Long longCase) {
        final boolean assumption = 0 != longCase % 2;

        assumeTrue(assumption);

        final boolean guardPrecondition = 5 != abs(longCase % 10);

        Trials.whenever(guardPrecondition, () -> {
            assertTrue(assumption);
            assertTrue(guardPrecondition);
        });
    }

    @TrialsTest(trials = {"longs", "strings"}, casesLimit = 100)
    void testWithALongAndAString(Long longCase, String stringCase) {
        final boolean guardPrecondition =
                5 != abs(longCase % 10) && stringCase.contains("e");

        Trials.whenever(guardPrecondition, () -> {
            assertTrue(guardPrecondition);
        });
    }

    @Disabled
    // This now detects the 'failing' test case correctly - but it is still a
    // test failure. Need to rethink what this test should look like....
    @TrialsTest(trials = {"first", "second"}, casesLimit = 50)
    void copiedFromJqwik(String first, String second) {
        final String concatenation = first + second;
        assertThat("Strings aren't allowed to be of length 4" +
                   " or 5 characters" + " in this test.",
                   4 > concatenation.length() ||
                   5 < concatenation.length());
    }
}
