package io.floci.az.core.arm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ArmTagsTest {

    private static String fold(String json) {
        return new String(ArmTags.foldCase(json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    void namesThatDifferOnlyByCaseKeepTheLastSpellingAndValue() {
        assertEquals("{\"location\":\"eastus\",\"tags\":{\"other\":\"x\",\"tofu-estate\":\"b\"}}",
                fold("{\"location\":\"eastus\",\"tags\":{\"Tofu-Estate\":\"a\",\"other\":\"x\",\"tofu-estate\":\"b\"}}"));
    }

    @Test
    void tagsApiPropertiesAreFoldedToo() {
        assertEquals("{\"operation\":\"Merge\",\"properties\":{\"tags\":{\"ENV\":\"prod\"}}}",
                fold("{\"operation\":\"Merge\",\"properties\":{\"tags\":{\"env\":\"dev\",\"ENV\":\"prod\"}}}"));
    }

    @Test
    void aBodyWithDistinctNamesIsReturnedAsSent() {
        byte[] body = "{ \"tags\" : { \"a\" : \"1\", \"b\" : \"2\" } }".getBytes(StandardCharsets.UTF_8);
        assertSame(body, ArmTags.foldCase(body));
    }

    @Test
    void aBodyThatIsNotJsonIsReturnedAsSent() {
        byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
        assertSame(body, ArmTags.foldCase(body));
    }
}
