package de.lightrixx.punish.model;

public sealed interface ParseResult
        permits ParseResult.Geltzeit, ParseResult.Permanent, ParseResult.Ungueltig {

    record Geltzeit(long millis) implements ParseResult {

        public Geltzeit {
            if (millis <= 0) {
                throw new IllegalArgumentException(
                        "Die Geltzeit muss positiv sein, war aber: " + millis);
            }
        }
    }

    record Permanent() implements ParseResult {
    }

    record Ungueltig() implements ParseResult {
    }
}
