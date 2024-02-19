package study.paymentintegrationserver.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class EncodeUtilsTest {

    @Test
    void testEncodeBase64() {
        // Given
        String inputString = "Hello, World!";
        String expectedEncodedString = Base64.getEncoder().encodeToString(inputString.getBytes());

        // When
        String encodedString = EncodeUtils.encodeBase64(inputString);

        // Then
        assertThat(encodedString).isEqualTo(expectedEncodedString);
    }
}
