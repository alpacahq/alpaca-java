# Migration guides

Use the guide that matches the version you are adopting:

- [`0.1.4` to `0.1.5`: Trading, Market Data, and Broker SSE](docs/content/sdk/sse-migration.md)

The SSE guide includes a conservative scanner/codemod:

```bash
python3 scripts/migrate_sse_0_1_4_to_0_1_5.py path/to/your/src
```

The default mode only reports findings. Review its output, then add `--write` to apply the narrow
`BrokerSseSubscription.eventSource().cancel()` to `BrokerSseSubscription.close()` rewrite. The tool
requires one explicit, unshadowed `BrokerSseSubscription` binder and a bare identifier receiver;
qualified, chained, inferred, same-named local/nested types, or otherwise uncertain cases are
reported as `SSE001` for manual review. The type may use the exact import or its fully qualified
class name. Missing paths and explicit non-Java inputs fail closed. The tool does not attempt to
rewrite behavioral callback or threading changes.

`BrokerSseSubscription` also implements `AlpacaSseSubscription` in `0.1.5`. This is additive and
requires no source migration; it allows domain-neutral lifecycle code to accept existing Broker
subscription values directly.

## Generated REST corrections in 0.1.5

OpenAPI corrections adopted before the compatibility gate was introduced changed these generated
symbols from the published `0.1.4` artifact:

- Broker `EventsApi.suscribeToAccountStatusSSE*` is now
  `EventsApi.subscribeToAccountStatusSSE*`.
- Broker `IraApi.listIRAExcessContritbutions*` is now
  `IraApi.listIRAExcessContributions*`.
- Broker `Asset.easyToBorrow` and Trading `Assets.easyToBorrow` are replaced by the typed
  `borrowStatus` property.
- Trading `PositionClosedReponse` is corrected to `PositionClosedResponse`; consequently,
  `PositionsApi.deleteAllOpenPositions` returns `List<PositionClosedResponse>`.

The release compatibility check has member-specific reviewed exclusions for only these inherited
changes. It continues to fail on every other source or binary incompatibility.
