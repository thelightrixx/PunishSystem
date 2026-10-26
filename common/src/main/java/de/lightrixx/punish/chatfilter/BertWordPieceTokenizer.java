package de.lightrixx.punish.chatfilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BertWordPieceTokenizer {

    private static final int MAX_WORTZEICHEN = 100;

    private final Map<String, Integer> wortschatz;
    private final int unbekanntId;
    private final int startId;
    private final int endeId;
    private final int maxLaenge;

    public BertWordPieceTokenizer(Map<String, Integer> wortschatz, int maxLaenge) {
        Objects.requireNonNull(wortschatz, "Der Wortschatz darf nicht null sein.");
        if (wortschatz.isEmpty()) {
            throw new IllegalArgumentException("Der Wortschatz darf nicht leer sein.");
        }
        if (maxLaenge < 2) {
            throw new IllegalArgumentException("Die Maximallänge muss mindestens 2 sein.");
        }
        Integer unbekannt = wortschatz.get("[UNK]");
        Integer start = wortschatz.get("[CLS]");
        Integer ende = wortschatz.get("[SEP]");
        if (unbekannt == null || start == null || ende == null) {
            throw new IllegalArgumentException("Dem Wortschatz fehlen [UNK], [CLS] oder [SEP].");
        }
        this.wortschatz = Map.copyOf(wortschatz);
        this.unbekanntId = unbekannt;
        this.startId = start;
        this.endeId = ende;
        this.maxLaenge = maxLaenge;
    }

    public static BertWordPieceTokenizer ausZeilen(List<String> zeilen, int maxLaenge) {
        Objects.requireNonNull(zeilen, "Die Zeilen dürfen nicht null sein.");
        Map<String, Integer> wortschatz = new HashMap<>(zeilen.size() * 2);
        for (int i = 0; i < zeilen.size(); i++) {
            String token = zeilen.get(i);
            if (token != null) {
                wortschatz.putIfAbsent(token, i);
            }
        }
        return new BertWordPieceTokenizer(wortschatz, maxLaenge);
    }

    public int[] kodiere(String text) {
        Objects.requireNonNull(text, "Der Text darf nicht null sein.");
        List<Integer> ids = new ArrayList<>(maxLaenge);
        ids.add(startId);
        int budget = maxLaenge - 2;
        for (String wort : basisTokenisiere(text)) {
            if (ids.size() - 1 >= budget) {
                break;
            }
            if (wort.length() > MAX_WORTZEICHEN) {
                ids.add(unbekanntId);
                continue;
            }
            wordPieceAnhaengen(wort, ids, budget);
        }
        ids.add(endeId);
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    private void wordPieceAnhaengen(String wort, List<Integer> ids, int budget) {
        int start = 0;
        List<Integer> stuecke = new ArrayList<>();
        while (start < wort.length()) {
            int ende = wort.length();
            Integer gefunden = null;
            while (start < ende) {
                String kandidat = (start == 0 ? "" : "##") + wort.substring(start, ende);
                Integer id = wortschatz.get(kandidat);
                if (id != null) {
                    gefunden = id;
                    break;
                }
                ende--;
            }
            if (gefunden == null) {
                ids.add(unbekanntId);
                return;
            }
            stuecke.add(gefunden);
            start = ende;
        }
        for (int id : stuecke) {
            if (ids.size() - 1 >= budget) {
                return;
            }
            ids.add(id);
        }
    }

    static List<String> basisTokenisiere(String text) {
        StringBuilder mitAbstand = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (istCjk(c)) {
                mitAbstand.append(' ').append(c).append(' ');
            } else {
                mitAbstand.append(c);
            }
        }
        List<String> woerter = new ArrayList<>();
        for (String teil : mitAbstand.toString().split("\\s+")) {
            if (teil.isEmpty()) {
                continue;
            }
            woerter.addAll(anSatzzeichenTeilen(teil));
        }
        return woerter;
    }

    private static List<String> anSatzzeichenTeilen(String teil) {
        List<String> ergebnis = new ArrayList<>();
        StringBuilder aktuell = new StringBuilder();
        for (int i = 0; i < teil.length(); i++) {
            char c = teil.charAt(i);
            if (istSatzzeichen(c)) {
                if (!aktuell.isEmpty()) {
                    ergebnis.add(aktuell.toString());
                    aktuell.setLength(0);
                }
                ergebnis.add(String.valueOf(c));
            } else {
                aktuell.append(c);
            }
        }
        if (!aktuell.isEmpty()) {
            ergebnis.add(aktuell.toString());
        }
        return ergebnis;
    }

    private static boolean istSatzzeichen(char c) {
        if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64)
                || (c >= 91 && c <= 96) || (c >= 123 && c <= 126)) {
            return true;
        }
        return Character.getType(c) == Character.CONNECTOR_PUNCTUATION
                || Character.getType(c) == Character.DASH_PUNCTUATION
                || Character.getType(c) == Character.START_PUNCTUATION
                || Character.getType(c) == Character.END_PUNCTUATION
                || Character.getType(c) == Character.INITIAL_QUOTE_PUNCTUATION
                || Character.getType(c) == Character.FINAL_QUOTE_PUNCTUATION
                || Character.getType(c) == Character.OTHER_PUNCTUATION;
    }

    private static boolean istCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF);
    }
}
