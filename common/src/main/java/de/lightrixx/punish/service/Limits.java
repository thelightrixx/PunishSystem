package de.lightrixx.punish.service;

public final class Limits {

    private Limits() {
    }

    public static final String PERMANENT_ANZEIGE = "Permanent";
    public static final int BAN_ALL_MAX = 5000;
    public static final int MUTE_ALL_MAX = 5000;
    public static final int TIMEOUT_ALL_MAX = 5000;
    public static final int SYNC_CHUNK_GROESSE = 200;
    public static final int GUI_SEITEN_GROESSE = 45;
    public static final int MAX_GRUND_LAENGE = 256;
    public static final int MAX_NOTIZEN_PRO_SPIELER = 200;
    public static final int MAX_REPORTS_GESAMT = 10000;
    public static final long MAX_IP_LAENGE = 64L;
    public static final long IO_SHUTDOWN_SEKUNDEN = 15L;
}
