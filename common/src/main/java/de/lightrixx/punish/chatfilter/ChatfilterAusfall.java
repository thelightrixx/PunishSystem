package de.lightrixx.punish.chatfilter;

public final class ChatfilterAusfall extends RuntimeException {

    public ChatfilterAusfall(String nachricht) {
        super(nachricht);
    }

    public ChatfilterAusfall(String nachricht, Throwable ursache) {
        super(nachricht, ursache);
    }
}
