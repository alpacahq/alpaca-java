import markets.alpaca.client.broker.sse.BrokerSseSubscription;

class SafeMigration {
  void close(BrokerSseSubscription subscription) {
    subscription.close();
  }
}
