# Migration guides

Use the guide that matches the version you are adopting:

- [`0.1.4` to `0.1.5`: Trading, Market Data, and Broker SSE](docs/content/sdk/sse-migration.md)

The SSE guide includes a conservative scanner/codemod:

```bash
python3 scripts/migrate_sse_0_1_4_to_0_1_5.py path/to/your/src
```

The default mode only reports findings. Review its output, then add `--write` to apply the narrow
`BrokerSseSubscription.eventSource().cancel()` to `BrokerSseSubscription.close()` rewrite. The tool
requires one explicit, unshadowed `BrokerSseSubscription` parameter or local-variable binder in the
call's lexical scope and a bare identifier receiver. Fields, qualified, chained, inferred,
lambda-bound, out-of-scope, same-named local/nested types, or otherwise uncertain cases are reported
as `SSE001` for manual review. Control-flow header declarations are also report-only because an
unbraced statement's scope cannot be established safely by this text scanner. Same-named type
parameters and files containing Java Unicode escapes are report-only as well. The type may use the
exact import or its fully qualified class name. Rewrites preserve the source file's existing line
endings. Missing paths and explicit non-Java inputs fail closed. The tool does not attempt to
rewrite behavioral callback or threading changes.
Diagnostic `SSE006` reports generated SSE method invocations and method references that should move
to handwritten streaming clients. It does not report a uniquely bound imported or fully qualified
`BrokerEventsSseClient.getAccountActivityEventAsync` receiver when used directly. A
`this.receiver` call is suppressed only when the scanner can prove that the current class declares
that handwritten-client field; inherited, other-qualified, and otherwise ambiguous receivers
remain findings. Unqualified generated-method calls are reported conservatively, including calls
from indirect and anonymous generated API subclasses. Every generated SSE use in a file is
reported.

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
- Broker and Trading `OptionContract` payload validation now requires `ppind`.
- Broker and Trading `CommonFixedIncomeInterestActivityV2` payload validation now requires
  `interest_type`.

Applications that deserialize stored or mocked payloads must add these required fields before
upgrading. Generated `validateJsonElement` methods reject payloads that omit them even when the
application does not read the new properties.

The release compatibility check has member-specific reviewed exclusions for only these inherited
symbol changes. Required-field validation changes are behavioral and therefore remain migration
review items rather than japicmp exclusions. PR and frozen-snapshot CI continue to fail on every
other source or binary incompatibility.
