package de.lightrixx.punish.chatfilter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class VocabLizenzGuardTest {

    @Test
    void vocabTxtLiegtNichtImRepo() {
        assertNull(BertWordPieceTokenizerTest.class.getResourceAsStream("vocab.txt"),
                "vocab.txt (CC-BY-NC-SA-4.0) darf nicht als Test-Ressource im MIT-Repo liegen; "
                        + "Referenz-Vokabular per PUNISH_CHATFILTER_VOCAB nachladen.");
    }
}
