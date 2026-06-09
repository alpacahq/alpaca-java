package markets.alpaca.client.rest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlpacaApiResponseTest {

  @Test
  void responseIsARecordThatDefensivelyCopiesHeaders() {
    var headerValues = new ArrayList<>(List.of("200"));
    var headers = new LinkedHashMap<String, List<String>>();
    headers.put("X-RateLimit-Limit", headerValues);

    var response = new AlpacaApiResponse<>("body", 200, headers);
    headerValues.add("mutated");

    assertEquals("body", response.body());
    assertEquals(200, response.statusCode());
    assertEquals(List.of("200"), response.headers().get("X-RateLimit-Limit"));
    assertThrows(UnsupportedOperationException.class, () -> response.headers().put("x", List.of()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> response.headers().get("X-RateLimit-Limit").add("x"));
  }
}
