package de.lightrixx.punish.update;

import de.lightrixx.punish.util.BuildNumber;
import de.lightrixx.punish.util.PluginVersion;

import java.util.Objects;
import java.util.Optional;

public final class UpdateVergleich {

    private UpdateVergleich() {
    }

    public static boolean neuerVerfuegbar(String aktuell, String entfernt) {
        String a = normiere(aktuell);
        String b = normiere(entfernt);
        if (a.isEmpty() || b.isEmpty() || a.equalsIgnoreCase(b)) {
            return false;
        }
        Optional<PluginVersion> pa = PluginVersion.tryParse(a);
        Optional<PluginVersion> pb = PluginVersion.tryParse(b);
        if (pa.isPresent() && pb.isPresent()) {
            return vergleiche(pa.get(), pb.get()) < 0;
        }
        // Parse-Fehler: kein Update empfehlen, sondern nur informieren.
        return false;
    }

    static int vergleiche(PluginVersion aktuell, PluginVersion entfernt) {
        Objects.requireNonNull(aktuell, "aktuell");
        Objects.requireNonNull(entfernt, "entfernt");
        int cmp = Integer.compare(aktuell.major(), entfernt.major());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(aktuell.minor(), entfernt.minor());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(patchAus(aktuell.basis()), patchAus(entfernt.basis()));
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(aktuell.kanalRang(), entfernt.kanalRang());
        if (cmp != 0) {
            return cmp;
        }
        BuildNumber ba = aktuell.build();
        BuildNumber bb = entfernt.build();
        if (ba == null && bb == null) {
            return 0;
        }
        if (ba == null) {
            return -1;
        }
        if (bb == null) {
            return 1;
        }
        // Beta-Buchstabe (z. B. EA26c) ist aelter als derselbe Build ohne
        // Buchstabe (Freigabe, z. B. EA26). Altschreibweisen mit a/b/c/d
        // bleiben lesbar: EA25c -> EA25b -> EA25a ist weiterhin Fortschritt.
        cmp = Integer.compare(ba.nummer(), bb.nummer());
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(stufenRang(bb), stufenRang(ba));
    }

    private static int stufenRang(BuildNumber build) {
        BuildNumber.Stabilitaet stufe = build.stabilitaet();
        return stufe == null ? -1 : stufe.ordinal();
    }

    private static int patchAus(String basis) {
        try {
            String ohneV = basis.startsWith("v") || basis.startsWith("V") ? basis.substring(1) : basis;
            String kern = ohneV.split("-", 2)[0];
            String[] teile = kern.split("\\.");
            if (teile.length >= 3) {
                return Integer.parseInt(teile[2]);
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }

    private static String normiere(String wert) {
        return wert == null ? "" : wert.trim();
    }
}
