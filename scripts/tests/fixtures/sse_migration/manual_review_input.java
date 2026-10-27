class ManualReview {
  void close() {
    var subscription = createSubscription();
    subscription.eventSource().cancel();
  }
}
