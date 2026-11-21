package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeduplicationTest {
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test void hashesOrderedKeysAndCanonicalJsonIncludingSingletonWildcards() throws Exception {
        var dedup = Deduplication.compile(json.readTree("""
                {"keys":["payload.items[*].n","payload.missing","payload.data","meta.source"]}
                """));
        var event = json.readTree("""
                {"meta":{"source":"orders"},"payload":{"items":[{"n":1.00},{}],
                  "data":{"z":1e3,"id":"keep","a":[false,null]}}}
                """);
        assertEquals(hash("R", "[1]", "null", "{\"a\":[false,null],\"id\":\"keep\",\"z\":1000}", "\"orders\""),
                dedup.fingerprint("R", event));
        assertNotEquals(dedup.fingerprint("R", event), dedup.fingerprint("S", event));
        assertEquals(hash("R", "null", "null", "null", "null"), dedup.fingerprint("R", json.readTree("{}")));
    }

    @Test void defaultJsonFingerprintRemovesVolatileFieldsRecursively() throws Exception {
        var event = json.readTree("""
                {"payload":{"ID":1,"items":[{"TRACE_ID":"x","amount":0.0100}],"paid":true,"valid":"yes"}}
                """);
        assertEquals(hash("R", "{\"items\":[{\"amount\":0.01}],\"paid\":true,\"valid\":\"yes\"}"),
                Deduplication.DEFAULT.fingerprint("R", event));
    }

    @Test void defaultTextFingerprintNormalizesDigitRuns() throws Exception {
        var event = json.readTree("""
                {"meta":{"contentType":"text"},"payload":{"message":"Timeout 3021ms for order 88","level":"low"}}
                """);
        assertEquals(hash("R", "Timeout #ms for order #"), Deduplication.DEFAULT.fingerprint("R", event));
    }

    @Test void validatesWindowAndKeysDuringCompilation() throws Exception {
        assertEquals(60, Deduplication.compile(json.readTree("{}")).windowSeconds());
        for (String invalid : new String[]{"{\"keys\":42}", "{\"keys\":[\"payload.x\",\"payload.x\"]}",
                "{\"windowSec\":0}", "{\"windowSec\":86401}", "{\"windowSec\":1.5}"}) {
            assertThrows(InvalidRuleDefinitionException.class, () -> Deduplication.compile(json.readTree(invalid)));
        }
    }

    private static String hash(String... parts) throws Exception {
        byte[] bytes = String.join("\u001f", parts).getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
