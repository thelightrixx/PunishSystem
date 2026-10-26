package de.lightrixx.punish.chatfilter;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

public final class TextNormalisierer {

    private TextNormalisierer() {
    }

    public static String normalisiere(String roh) {
        Objects.requireNonNull(roh, "Der Text darf nicht null sein.");
        String zerlegt = Normalizer.normalize(roh, Normalizer.Form.NFKD).replace("ß", "ss");
        StringBuilder sb = new StringBuilder(zerlegt.length());
        boolean leerDavor = true;
        for (int i = 0; i < zerlegt.length(); i++) {
            char c = zerlegt.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            char klein = Character.toLowerCase(c);
            char abgebildet = bildeLeetAb(klein);
            if (abgebildet == 0 || abgebildet == ' ') {
                if (!leerDavor) {
                    sb.append(' ');
                    leerDavor = true;
                }
                continue;
            }
            sb.append(abgebildet);
            leerDavor = false;
        }
        return sb.toString().strip().toLowerCase(Locale.ROOT);
    }

    public static String ohneAbstaende(String normalisiert) {
        Objects.requireNonNull(normalisiert, "Der Text darf nicht null sein.");
        return normalisiert.replace(" ", "");
    }

    private static char bildeLeetAb(char c) {
        return switch (c) {
            case '0' -> 'o';
            case '1', '!', '|', 'i' -> 'i';
            case '2' -> 'z';
            case '3' -> 'e';
            case '4' -> 'a';
            case '5' -> 's';
            case '6', '9' -> 'g';
            case '7' -> 't';
            case '8' -> 'b';
            case '@' -> 'a';
            case '$', '€', '§' -> 's';
            case '£' -> 'e';
            case '¥' -> 'y';
            case '+' -> 't';
            case '(' -> 'c';
            default -> {
                if (Character.isLetterOrDigit(c)) {
                    yield c;
                }
                if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                    yield ' ';
                }
                yield 0;
            }
        };
    }
}
