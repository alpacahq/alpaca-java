---
id: getting-started
title: Getting Started
---

## Requirements

- Java 17+
- Alpaca API credentials for calls that reach Alpaca services
- Maven or Gradle in your application project

## About

`alpaca-java-client` provides Java clients for Alpaca's Trading, Market Data, Broker, WebSocket,
and Broker Events SSE APIs. Use it when you want to build trading applications, read historical or
live market data, or build broker-backed investing experiences from a Java application.

The SDK includes a top-level `AlpacaClient` facade for common workflows, factory methods for
pre-configured generated REST clients, and handwritten streaming clients for live data.

You can learn about the API products Alpaca offers at [alpaca.markets](https://alpaca.markets/).

## Usage

Alpaca’s APIs allow you to do everything from building algorithmic trading strategies to building a full brokerage experience for your own end users.
Here are some things you can do with alpaca-java-client.

* Market Data API: Access live and historical market data for 5000+ stocks, 20+ crypto, and options.
* Trading API: Trade stock, crypto, and options with lightning fast execution speeds.
* Broker API & Connect: Build investment apps - from robo-advisors to brokerages.

## Installation

The library artifact coordinates are:

```text
markets.alpaca:alpaca-java-client:VERSION
```

Replace `VERSION` with the release you want to use.

### Gradle

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/alpacahq/alpaca-java-client")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("markets.alpaca:alpaca-java-client:VERSION")
}
```

### Maven

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/alpacahq/alpaca-java-client</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>markets.alpaca</groupId>
    <artifactId>alpaca-java-client</artifactId>
    <version>VERSION</version>
  </dependency>
</dependencies>
```

If your Maven build needs credentials for GitHub Packages, add a matching server entry to your
Maven settings:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Repository `./gradlew` commands are only needed when you are contributing to this SDK itself. An
application that consumes the SDK should import the artifact through its build tool.

## API keys

Trading and Market Data calls use Alpaca trading API keys. You can create paper-trading API keys
from the Alpaca dashboard. Broker API calls use Broker credentials, which should be kept separate
when your application uses both APIs.

### Environment variables

The built-in credential helpers read from environment variables:

```bash
export APCA_TRADING_KEY_ID=PK...
export APCA_TRADING_SECRET_KEY=...
```

```bash
export APCA_BROKER_KEY_ID=...
export APCA_BROKER_SECRET_KEY=...
```

Then create credentials from those variables:

```java
var tradingCredentials = AlpacaCredentials.fromTradingApiEnvironmentVariables();
var brokerCredentials = AlpacaCredentials.fromBrokerApiEnvironmentVariables();
```

### Property file

If your application stores local development settings in a property file, load it with Java's
`Properties` API and pass the values into `AlpacaCredentials`.

```properties
# alpaca.properties
tradingApiKeyId=PK...
tradingApiSecretKey=...
brokerApiKeyId=...
brokerApiSecretKey=...
```

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import markets.alpaca.client.AlpacaCredentials;

var properties = new Properties();
try (var input = Files.newInputStream(Path.of("alpaca.properties"))) {
    properties.load(input);
}

var tradingCredentials = new AlpacaCredentials(
    properties.getProperty("tradingApiKeyId"),
    properties.getProperty("tradingApiSecretKey"));

var brokerCredentials = new AlpacaCredentials(
    properties.getProperty("brokerApiKeyId"),
    properties.getProperty("brokerApiSecretKey"));
```

Do not commit files that contain real API keys.

### Manual values

For applications that already receive secrets from a vault, configuration service, or framework,
construct credentials directly:

```java
var credentials = new AlpacaCredentials(apiKeyId, apiSecretKey);
```

You can use different credentials per API when building the client:

```java
var client = AlpacaClient.builder(tradingCredentials)
    .dataCredentials(dataCredentials)
    .brokerCredentials(brokerCredentials)
    .build();
```

## Create a client

```java
import markets.alpaca.client.AlpacaClient;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.TradingApiEnvironment;

var credentials = AlpacaCredentials.fromTradingApiEnvironmentVariables();

var client = AlpacaClient.builder(credentials)
    .tradingEnvironment(TradingApiEnvironment.PAPER)
    .build();
```

`AlpacaClient.builder(credentials)` uses the same credential pair for Trading, Market Data, and
Broker clients unless you override an API explicitly with `dataCredentials(...)`,
`tradingCredentials(...)`, or `brokerCredentials(...)`.

## First calls

Common Trading workflows are exposed through typed helpers:

```java
import markets.alpaca.client.trading.ListOrdersRequest;

var openOrders = client.orders().list(ListOrdersRequest.builder()
    .status(ListOrdersRequest.Status.OPEN)
    .symbols("AAPL")
    .limit(50)
    .build());
```

Common Market Data workflows use the same client facade:

```java
import markets.alpaca.client.data.StockTradesRequest;
import markets.alpaca.client.openapi.data.model.StockHistoricalFeed;

var trades = client.stocks().tradesForSymbol(StockTradesRequest.builder()
    .symbols("AAPL")
    .feed(StockHistoricalFeed.IEX)
    .limit(10)
    .build());
```

## Choosing clients

- Use `AlpacaClient` for common Trading and Market Data workflows.
- Use `AlpacaClientFactory` when you need a specific generated REST client, WebSocket stream, or
  Broker Events SSE client.
- Use `client.newTradingClient()`, `client.newDataClient()`, or `client.newBrokerClient()` as an
  escape hatch for generated REST endpoints that do not yet have handwritten helpers.

Next, see the focused guides for [Trading](./sdk/trading), [Market Data](./sdk/market-data),
[Broker](./sdk/broker), and [Streaming & Events](./sdk/streaming).
