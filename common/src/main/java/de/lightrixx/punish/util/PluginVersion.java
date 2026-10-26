package de.lightrixx.punish.util;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class PluginVersion {

    private static final Pattern BASIS_MUSTER =
            Pattern.compile("^[vV]?\\d+\\.\\d+(\\.\\d+)?(-alpha|-beta|-rc)?(-SNAPSHOT)?$",
                    Pattern.CASE_INSENSITIVE);

    private final String basis;
    private final BuildNumber build;

    private PluginVersion(String basis, BuildNumber build) {
        this.basis = basis;
        this.build = build;
    }

    public static PluginVersion parse(String eingabe) {
        if (eingabe == null) {
            throw new IllegalArgumentException("Version darf nicht null sein.");
        }
        String getrimmt = eingabe.trim();
        if (getrimmt.isEmpty()) {
            throw new IllegalArgumentException("Version darf nicht leer sein.");
        }
        int plus = getrimmt.indexOf('+');

        if (plus >= 0 && getrimmt.indexOf('+', plus + 1) >= 0) {
            throw new IllegalArgumentException("Ungueltige Version '" + eingabe + "' (nur ein '+' erlaubt).");
        }
        String basisTeil = plus >= 0 ? getrimmt.substring(0, plus).trim() : getrimmt;
        String buildTeil = plus >= 0 ? getrimmt.substring(plus + 1).trim() : "";
        if (!BASIS_MUSTER.matcher(basisTeil).matches()) {
            throw new IllegalArgumentException(
                    "Ungueltige Basis '" + basisTeil + "' (erwartet z. B. v4.0, v4.0-RC oder v4.0-beta).");
        }
        if (basisTeil.startsWith("v") || basisTeil.startsWith("V")) {
            basisTeil = "v" + basisTeil.substring(1);
        } else {
            basisTeil = "v" + basisTeil;
        }
        if (plus >= 0) {
            if (buildTeil.isEmpty()) {
                throw new IllegalArgumentException("Leere Buildnummer nach '+' in '" + eingabe + "'.");
            }
            BuildNumber build = BuildNumber.parse(buildTeil);
            int major = majorAus(basisTeil);
            int minor = minorAus(basisTeil);
            String erwartet = BuildNumber.trainFuer(major, minor);
            if (!build.train().equals(erwartet)) {
                throw new IllegalArgumentException("Build '" + build + "' passt nicht zu Basis '" + basisTeil
                        + "' (Train " + build.train() + " = Version " + build.trainMajor() + "." + build.trainMinor()
                        + ", erwartet " + erwartet + " fuer " + major + "." + minor + ").");
            }
            return new PluginVersion(basisTeil, build);
        }
        return new PluginVersion(basisTeil, null);
    }

    private static int[] nummernAus(String basis) {
        String ohneV = (basis.startsWith("v") || basis.startsWith("V")) ? basis.substring(1) : basis;
        String kern = ohneV.split("-", 2)[0];
        String[] teile = kern.split("\\.");
        return new int[]{Integer.parseInt(teile[0]), Integer.parseInt(teile[1])};
    }

    private static int majorAus(String basis) {
        return nummernAus(basis)[0];
    }

    private static int minorAus(String basis) {
        return nummernAus(basis)[1];
    }

    public static PluginVersion parsen(String eingabe) {
        return parse(eingabe);
    }

    public static Optional<PluginVersion> tryParse(String eingabe) {
        try {
            return Optional.of(parse(eingabe));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean istGueltig(String eingabe) {
        return tryParse(eingabe).isPresent();
    }

    public static String leseAktuell() {
        try {
            String ausManifest = PluginVersion.class.getPackage().getImplementationVersion();
            if (ausManifest != null && !ausManifest.isBlank() && istGueltig(ausManifest)) {
                return parse(ausManifest.trim()).voll();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PluginVersion.class).debug("Manifest-Version konnte nicht gelesen werden.", e);
        }
        return "unbekannt";
    }

    public String basis() {
        return basis;
    }

    public int major() {
        return majorAus(basis);
    }

    public int minor() {
        return minorAus(basis);
    }

    public String erwarteterTrain() {
        return BuildNumber.trainFuer(major(), minor());
    }

    public BuildNumber build() {
        return build;
    }

    public boolean hatBuild() {
        return build != null;
    }

    public boolean istBeta() {
        String klein = basis.toLowerCase(Locale.ROOT);
        return klein.endsWith("-alpha") || klein.endsWith("-beta");
    }

    public boolean istReleaseCandidate() {
        return basis.toLowerCase(Locale.ROOT).endsWith("-rc");
    }

    public boolean istSnapshot() {
        return basis.toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT");
    }

    public int kanalRang() {
        if (istBeta() || istSnapshot()) {
            return 0;
        }
        if (istReleaseCandidate()) {
            return 1;
        }
        return 2;
    }

    public String voll() {
        return build == null ? basis : basis + "+" + build;
    }

    public PluginVersion mitBuild(BuildNumber neu) {
        Objects.requireNonNull(neu, "neu");
        return new PluginVersion(basis, neu);
    }

    public String logZeile() {
        if (build == null || build.stabilitaet() == null) {
            return voll();
        }
        return voll() + " (" + build.stabilitaet().beschreibung() + ")";
    }

    @Override
    public String toString() {
        return voll();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PluginVersion andere)) {
            return false;
        }
        return basis.equals(andere.basis) && Objects.equals(build, andere.build);
    }

    @Override
    public int hashCode() {
        return Objects.hash(basis, build);
    }
}
