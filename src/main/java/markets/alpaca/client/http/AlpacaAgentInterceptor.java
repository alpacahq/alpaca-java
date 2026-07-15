package markets.alpaca.client.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import markets.alpaca.client.AlpacaClient;
import okhttp3.Interceptor;
import okhttp3.Response;

/** Adds Alpaca Java SDK identification to outbound HTTP requests. */
final class AlpacaAgentInterceptor implements Interceptor {

  private static final String VERSION_RESOURCE = "/markets/alpaca/client/version.properties";
  private static final String VERSION = loadVersion();
  static final String USER_AGENT = "APCA-JAVA/" + VERSION + " Java/" + Runtime.version();
  static final AlpacaAgentInterceptor INSTANCE = new AlpacaAgentInterceptor();

  private AlpacaAgentInterceptor() {}

  @Override
  public Response intercept(Chain chain) throws IOException {
    var request = chain.request().newBuilder().header("User-Agent", USER_AGENT).build();
    return chain.proceed(request);
  }

  private static String loadVersion() {
    try (InputStream stream = AlpacaAgentInterceptor.class.getResourceAsStream(VERSION_RESOURCE)) {
      if (stream != null) {
        var properties = new Properties();
        properties.load(stream);
        String version = properties.getProperty("version");
        if (version != null && !version.isBlank() && !version.contains("${")) {
          return version;
        }
      }
    } catch (IOException ignored) {
      // Fall through to standard JAR metadata.
    }

    String implementationVersion = AlpacaClient.class.getPackage().getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank()
        ? "unknown"
        : implementationVersion;
  }
}
