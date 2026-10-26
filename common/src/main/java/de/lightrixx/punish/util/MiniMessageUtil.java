package de.lightrixx.punish.util;

import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MiniMessageUtil {

    private static final char LEGACY_PREFIX = '\u00A7';
    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .preProcessor(MiniMessageUtil::entschaerfeLegacyCodes)
            .build();
    private static final Pattern PLATZHALTER = Pattern.compile("%([A-Za-z0-9_-]+)%");

    private MiniMessageUtil() {
    }

    private static String entschaerfeLegacyCodes(String eingabe) {
        if (eingabe == null || eingabe.isEmpty() || eingabe.indexOf(LEGACY_PREFIX) < 0) {
            return eingabe;
        }
        StringBuilder sb = new StringBuilder(eingabe.length());
        for (int i = 0; i < eingabe.length(); i++) {
            char c = eingabe.charAt(i);
            if (c == LEGACY_PREFIX && i + 1 < eingabe.length() && istLegacyZeichen(eingabe.charAt(i + 1))) {
                sb.append('&');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean istLegacyZeichen(char c) {
        char klein = Character.toLowerCase(c);
        return (klein >= '0' && klein <= '9') || (klein >= 'a' && klein <= 'f')
                || (klein >= 'k' && klein <= 'o') || klein == 'r' || klein == 'x';
    }

    public static MiniMessage miniMessage() {
        return MINI_MESSAGE;
    }

    public static String ersetzen(String vorlage, Map<String, String> platzhalter) {
        return ersetzen(vorlage, platzhalter, Set.of());
    }

    public static String ersetzen(String vorlage, Map<String, String> platzhalter,
                                  Set<String> rohSchluessel) {
        if (vorlage == null || vorlage.isEmpty()) {
            return "";
        }
        if (platzhalter == null || platzhalter.isEmpty()) {
            return vorlage;
        }
        Set<String> roh = rohSchluessel == null ? Set.of() : rohSchluessel;
        Matcher matcher = PLATZHALTER.matcher(vorlage);
        StringBuilder ergebnis = new StringBuilder();
        while (matcher.find()) {
            String schluessel = matcher.group(1);
            String wert = platzhalter.get(schluessel);
            if (wert == null) {
                matcher.appendReplacement(ergebnis, Matcher.quoteReplacement(matcher.group()));
            } else if (roh.contains(schluessel)) {
                matcher.appendReplacement(ergebnis, Matcher.quoteReplacement(wert));
            } else {
                String sichererWert = MINI_MESSAGE.escapeTags(wert);
                matcher.appendReplacement(ergebnis, Matcher.quoteReplacement(sichererWert));
            }
        }
        matcher.appendTail(ergebnis);
        return ergebnis.toString();
    }
}
