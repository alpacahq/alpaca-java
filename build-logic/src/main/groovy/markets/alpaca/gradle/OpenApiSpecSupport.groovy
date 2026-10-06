package markets.alpaca.gradle

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

final class OpenApiSpecSupport {
    private static final List<String> OPERATION_METHODS =
        ['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'].asImmutable()

    /** Keywords whose value is a single nested schema. */
    private static final List<String> NESTED_SCHEMA_KEYS =
        ['items', 'additionalProperties', 'not', 'contains', 'propertyNames'].asImmutable()

    /** Keywords that combine sibling subschemas into one effective schema. */
    private static final List<String> COMPOSITION_KEYS = ['allOf', 'oneOf', 'anyOf'].asImmutable()

    /** Keywords whose value is a list of nested schemas. */
    private static final List<String> NESTED_SCHEMA_LIST_KEYS = ['prefixItems'].asImmutable()

    /** Keywords whose value maps names to nested schemas. */
    private static final List<String> NESTED_SCHEMA_MAP_KEYS =
        ['properties', 'patternProperties'].asImmutable()

    private static final String SCHEMA_REF_PREFIX = '#/components/schemas/'

    private static final String SPEC_USER_AGENT = 'alpaca-java-openapi-adopt/1.0'
    private static final String SPEC_ACCEPT = 'application/json, application/yaml, text/yaml, */*'

    private OpenApiSpecSupport() {}

    static String dumpYaml(Object tree) {
        def options = new DumperOptions()
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
        options.setIndent(2)
        options.setIndicatorIndent(2)
        options.setIndentWithIndicator(true)
        options.setWidth(200)
        new Yaml(options).dump(tree)
    }

    static boolean isUrl(String source) {
        source?.startsWith('http://') || source?.startsWith('https://')
    }

    static Object loadSpec(String source) {
        if (isUrl(source)) {
            return openSpecStream(source, 15_000, 30_000)
                .withCloseable { stream -> new Yaml().load(stream) }
        }
        new File(source).withInputStream { stream -> new Yaml().load(stream) }
    }

    /** Downloads an OpenAPI document verbatim, failing on a non-2xx status or empty body. */
    static void downloadSpec(String url, File destination) {
        def body = openSpecStream(url, 60_000, 60_000).withCloseable { it.readAllBytes() }
        if (body.length == 0) {
            throw new IllegalStateException(
                "Failed to download OpenAPI spec from ${url}: empty response body")
        }
        destination.parentFile.mkdirs()
        destination.bytes = body
    }

    private static InputStream openSpecStream(String url, int connectTimeout, int readTimeout) {
        def connection = new URI(url).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty('User-Agent', SPEC_USER_AGENT)
        connection.setRequestProperty('Accept', SPEC_ACCEPT)
        connection.connectTimeout = connectTimeout
        connection.readTimeout = readTimeout
        def status = connection.responseCode
        if (status < 200 || status >= 300) {
            connection.disconnect()
            throw new IllegalStateException(
                "Failed to download OpenAPI spec from ${url} (HTTP ${status})")
        }
        connection.inputStream
    }

    static void removeDiscriminatorEnums(Map spec) {
        def schemas = spec?.components?.schemas
        if (!(schemas instanceof Map)) return

        schemas.each { schemaName, schema ->
            if (!(schema instanceof Map)) return
            def allOf = schema.allOf
            if (!(allOf instanceof List)) return

            def discriminatorPropertyNames = new LinkedHashSet<String>()
            def topLevelProperty = schema?.discriminator?.propertyName as String
            if (topLevelProperty) discriminatorPropertyNames << topLevelProperty
            allOf.each { part ->
                if (part instanceof Map) {
                    def propertyName = part?.discriminator?.propertyName as String
                    if (propertyName) discriminatorPropertyNames << propertyName
                }
            }
            if (!discriminatorPropertyNames) return

            allOf.each { part ->
                if (!(part instanceof Map)) return
                discriminatorPropertyNames.each { propertyName ->
                    def property = part?.properties?.get(propertyName)
                    if (!(property instanceof Map)) return
                    property.clear()
                    property.type = 'string'
                }
            }
        }
    }

    static void removeInternalSchemaMarkers(Map spec) {
        def schemas = spec?.components?.schemas
        if (!(schemas instanceof Map)) return
        schemas.each { name, schema ->
            if (schema instanceof Map) schema.remove('x-internal')
        }
    }

    static void removeEmptyKeyProperties(Map spec) {
        def schemas = spec?.components?.schemas
        if (!(schemas instanceof Map)) return

        schemas.each { schemaName, schema ->
            if (schema instanceof Map) {
                schema.properties?.remove('')
                ['allOf', 'oneOf', 'anyOf'].each { keyword ->
                    schema[keyword]?.each { part ->
                        if (part instanceof Map) part.properties?.remove('')
                    }
                }
            }
        }
    }

    /**
     * Calls {@code visitor} once for every schema object reachable from the document.
     *
     * <p>Traversal is schema aware rather than a blind deep walk, so a property literally named
     * {@code items} or {@code not} is never mistaken for a schema keyword.</p>
     */
    static void eachSchema(Map spec, Closure visitor) {
        traverseSchemas(spec) { schema, composed -> visitor.call(schema) }
    }

    /**
     * Calls {@code visitor} for every schema that stands on its own, skipping the subschemas of
     * {@code allOf}, {@code anyOf}, and {@code oneOf}, whose meaning comes from their siblings.
     */
    static void eachStandaloneSchema(Map spec, Closure visitor) {
        traverseSchemas(spec) { schema, composed -> if (!composed) visitor.call(schema) }
    }

    /**
     * Rewrites null-only schemas so the generator can render them.
     *
     * <p>OpenAPI 3.1 lets a schema declare {@code type: 'null'}, which upstream uses for
     * properties that are always null. OpenAPI Generator maps that to a {@code ModelNull} class
     * it never emits, so the generated sources do not compile. Java has no null-only type, so the
     * closest equivalent is a nullable free-form value.</p>
     *
     * <p>The type becomes {@code object} rather than being dropped entirely because the generator
     * documents a free-form object but emits an untyped schema as a bare, undocumented
     * {@code Object} — losing the property description that explains why the value is always
     * null. Both spellings generate the same Java type. Any {@code items} left over from the
     * previous array declaration is dropped as it no longer applies.</p>
     *
     * <p>Only standalone schemas are rewritten. A null-only subschema inside {@code anyOf} or
     * {@code oneOf} is the idiomatic 3.1 spelling of "nullable", which the generator already
     * collapses into its nullable sibling; rewriting it would instead produce a two-member union
     * and an extra model class.</p>
     */
    static void relaxNullOnlySchemas(Map spec) {
        eachStandaloneSchema(spec) { schema ->
            if (schema['type'] != 'null') return
            schema['type'] = 'object'
            schema.remove('items')
        }
    }

    /**
     * Restores the {@code type: array} that a schema with {@code items} implies.
     *
     * <p>Under OpenAPI 3.0 an absent {@code type} alongside {@code items} was read as an array.
     * Under 3.1 an absent {@code type} means "any type", so such a schema generates as
     * {@code Object} and the item model is lost from the signature.</p>
     */
    static void inferArrayTypeFromItems(Map spec) {
        eachSchema(spec) { schema ->
            if (!schema.containsKey('items') || schema['type'] != null || schema['$ref']) return
            if (COMPOSITION_KEYS.any { schema[it] != null }) return
            schema['type'] = 'array'
        }
    }

    /**
     * Collapses a {@code oneOf} of string enums into one string enum.
     *
     * <p>Upstream models "an existing status, or an empty string" as a union of two string enums.
     * OpenAPI 3.0 parsing merged those into a single enum; under 3.1 the generator emits an
     * {@code AbstractOpenApiSchema} wrapper instead, which is far clumsier to consume for what is
     * still just a closed set of strings.</p>
     */
    static void flattenStringEnumUnions(Map spec) {
        def schemas = spec?.components?.schemas
        eachSchema(spec) { schema ->
            def members = schema['oneOf']
            if (!(members instanceof List) || members.isEmpty() || schema['discriminator']) return

            def values = new LinkedHashSet()
            for (member in members) {
                def resolved = resolveSchemaRef(schemas, member)
                if (!(resolved instanceof Map)
                    || resolved['type'] != 'string'
                    || !(resolved['enum'] instanceof List)) {
                    return
                }
                values.addAll(resolved['enum'] as List)
            }

            schema.remove('oneOf')
            schema['type'] = 'string'
            schema['enum'] = new ArrayList(values)
        }
    }

    /**
     * Inlines path-item parameters into every operation, ahead of the operation's own parameters.
     *
     * <p>Operation parameters that redeclare a path-item parameter (same {@code name} and
     * {@code in}) are dropped, since OpenAPI forbids duplicating a parameter within an operation.
     * Without this the generator emits such a parameter twice (as {@code accountId} and
     * {@code accountId2}) and, for operations whose own parameters are also required, appends the
     * path-item parameters last and reorders the generated method arguments.</p>
     */
    static void inlinePathLevelParameters(Map spec) {
        def paths = spec?.paths
        if (!(paths instanceof Map)) return

        paths.each { path, item ->
            def shared = item instanceof Map ? item['parameters'] : null
            if (!(shared instanceof List) || shared.isEmpty()) return

            def sharedKeys = shared
                .collect { parameterKey(spec, it) }
                .findAll { it != null } as Set
            OPERATION_METHODS.each { method ->
                def operation = item[method]
                if (!(operation instanceof Map)) return

                // Deep copies keep each operation's list independent so the YAML dump does not
                // collapse the repeated parameters into anchors and aliases.
                def merged = shared.collect { deepCopy(it) }
                def own = operation['parameters']
                if (own instanceof List) {
                    own.each { parameter ->
                        def key = parameterKey(spec, parameter)
                        if (key == null || !sharedKeys.contains(key)) merged << parameter
                    }
                }
                operation['parameters'] = merged
            }
            item.remove('parameters')
        }
    }

    static void removeActivityV2DetailTrdRequired(Map spec) {
        def schema = spec?.components?.schemas?.get('ActivityV2DetailTRD')
        if (schema instanceof Map) schema.remove('required')
    }

    static void requireDistinctAccountActivityTypes(Map spec) {
        def schemas = spec?.components?.schemas
        def activityTypes = schemas?.get('ActivityType')?.enum
        if (!(schemas instanceof Map) || !(activityTypes instanceof List)) return

        constrainActivityType(schemas.get('TradingActivities'), ['FILL'])
        constrainActivityType(
            schemas.get('NonTradeActivities'),
            activityTypes.findAll { it != 'FILL' })
    }

    /** Corrects the Broker NTA endpoint, which is SSE despite its upstream JSON media type. */
    static void normalizeBrokerNtaSseMediaType(Map spec) {
        def response = spec?.paths?.get('/v1/events/nta')?.get?.responses?.get('200')
        def content = response?.content
        if (!(content instanceof Map)) return
        def json = content.remove('application/json')
        if (json != null && !content.containsKey('text/event-stream')) {
            content['text/event-stream'] = json
        }
    }

    /**
     * Sanitizers that apply to every API because they address OpenAPI 3.1 constructs the Java
     * generator renders into code that does not compile or that loses type information.
     *
     * <p>{@link #relaxNullOnlySchemas} must run before {@link #inferArrayTypeFromItems} so the
     * {@code items} it strips from a null-only schema is not read back as an array type.</p>
     */
    static void sanitizeSpec(Map spec) {
        relaxNullOnlySchemas(spec)
        inferArrayTypeFromItems(spec)
        flattenStringEnumUnions(spec)
        inlinePathLevelParameters(spec)
    }

    /** Shared Broker sanitizers for pin preprocess and upstream-adopt preprocess. */
    static void sanitizeBrokerSpec(Map spec) {
        removeEmptyKeyProperties(spec)
        removeDiscriminatorEnums(spec)
        removeActivityV2DetailTrdRequired(spec)
        normalizeBrokerNtaSseMediaType(spec)
        sanitizeSpec(spec)
    }

    /** Shared Market Data sanitizers for pin preprocess and upstream-adopt preprocess. */
    static void sanitizeDataSpec(Map spec) {
        removeInternalSchemaMarkers(spec)
        sanitizeSpec(spec)
    }

    /** Shared Trading sanitizers for pin preprocess and upstream-adopt preprocess. */
    static void sanitizeTradingSpec(Map spec) {
        removeActivityV2DetailTrdRequired(spec)
        requireDistinctAccountActivityTypes(spec)
        sanitizeSpec(spec)
    }

    static void writeSanitizedSpec(String source, File outputFile, Closure sanitize) {
        outputFile.parentFile.mkdirs()
        def spec = loadSpec(source) as Map
        sanitize.call(spec)
        outputFile.text = dumpYaml(spec)
    }

    /**
     * Fails closed when a pinned SSE operation appears, disappears, or changes its identifying
     * contract without a corresponding handwritten-support decision.
     */
    static void verifySseContracts(Map<String, File> specFiles) {
        def expected = [
            broker: [
                contract('/v1/events/nta', 'get-v1-events-nta', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToNonTradingActivities'),
                contract('/v1/events/accounts/status', 'subscribeToAccountStatusSSE', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToAccountStatus'),
                contract('/v1/events/journals/status', 'subscribeToJournalStatusSSE', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToJournalStatusLegacy'),
                contract('/v1/events/transfers/status', 'subscribeToTransferStatusSSE',
                    'deprecated'),
                contract('/v2beta1/accounts/{account_id}/events/activities/{event_id}',
                    'getAccountActivityEvent', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#getAccountActivityEventAsync'),
                contract('/v2beta1/events/activities', 'subscribeToActivitiesSSE', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToActivities'),
                contract('/v2/events/admin-actions', 'subscribeToAdminActionSSE', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToAdminActions'),
                contract('/v2/events/funding/status', 'subscribeToFundingStatusSSE', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToFundingStatus'),
                contract('/v2/events/ipos', 'subscribeToIPOEventsSSE', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToIpoEvents'),
                contract('/v2/events/journals/status', 'subscribeToJournalStatusV2SSE', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToJournalStatus'),
                contract('/v2/events/system', 'subscribeToSystemEventV2SSE', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToSystemEvents'),
                contract('/v2/events/trades', 'subscribeToTradeV2SSE', 'supported',
                    'markets.alpaca.client.broker.sse.BrokerEventsSseClient' +
                        '#subscribeToTradeEvents'),
            ],
            data: [
                contract('/v1beta1/events/corporate-actions',
                    'SubscribeToCorporateActionsEventsSSE', 'supported',
                    'markets.alpaca.client.data.sse.CorporateActionsSseClient' +
                        '#subscribeToCorporateActions'),
            ],
            trading: [
                contract('/v2beta1/events/activities', 'subscribeToActivitiesSSE', 'supported',
                    'markets.alpaca.client.trading.sse.TradingEventsSseClient' +
                        '#subscribeToActivities'),
            ],
        ]

        expected.each { api, expectedOperations ->
            expectedOperations.each { validateSseSupportDecision(it, specFiles) }
            def file = specFiles[api]
            if (file == null || !file.isFile()) {
                throw new IllegalStateException("Missing pinned ${api} OpenAPI document")
            }
            def spec = loadSpec(file.absolutePath) as Map
            def actual = []
            spec.paths.each { path, pathItem ->
                if (!(pathItem instanceof Map)) return
                OPERATION_METHODS.each { method ->
                    def operation = pathItem[method]
                    if (!(operation instanceof Map)) return
                    def content = operation?.responses?.get('200')?.content
                    boolean sseMedia = content instanceof Map &&
                        content.containsKey('text/event-stream')
                    if (!sseMedia) return
                    if (!(content instanceof Map) || content.isEmpty()) {
                        throw new IllegalStateException(
                            "${api} ${method.toUpperCase()} ${path} has no success content")
                    }
                    def schema = content['text/event-stream']?.schema
                    if (!(schema instanceof Map)) {
                        throw new IllegalStateException(
                            "${api} ${method.toUpperCase()} ${path} has no SSE schema")
                    }
                    def security = resolvedSecurityRequirements(spec, operation)
                    if (security.isEmpty()) {
                        throw new IllegalStateException(
                            "${api} ${method.toUpperCase()} ${path} has no resolved security")
                    }
                    actual << [path: path, operationId: operation.operationId]
                }
            }

            def expectedIdentity =
                expectedOperations.collect { [it.path, it.operationId] }.toSet()
            def actualIdentity = actual.collect { [it.path, it.operationId] }.toSet()
            if (actualIdentity != expectedIdentity) {
                throw new IllegalStateException(
                    "${api} SSE inventory changed. Expected ${expectedIdentity}; " +
                        "found ${actualIdentity}")
            }
        }

        def broker = loadSpec(specFiles.broker.absolutePath) as Map
        def legacyDateRange = [
            queryParameter('since', 'string', 'date'),
            queryParameter('until', 'string', 'date'),
            queryParameter('since_id', 'integer'),
            queryParameter('until_id', 'integer'),
        ]
        def legacyUlidRange = [
            queryParameter('since_ulid', 'string', 'ulid'),
            queryParameter('until_ulid', 'string', 'ulid'),
        ]
        def activityRange = [
            queryParameter('since', 'string', 'date-time'),
            queryParameter('until', 'string', 'date-time'),
            queryParameter('since_id', 'string', 'ulid'),
            queryParameter('until_id', 'string', 'ulid'),
        ]
        def dateUlidRange = [
            queryParameter('since', 'string', 'date'),
            queryParameter('until', 'string', 'date'),
            queryParameter('since_id', 'string', 'ulid'),
            queryParameter('until_id', 'string', 'ulid'),
        ]
        def brokerShapes = [
            brokerSseShape('/v1/events/nta',
                [queryParameter('id', 'string')] +
                    legacyDateRange +
                    legacyUlidRange +
                    [queryParameter('include_preprocessing', 'boolean'),
                        queryParameter('group_id', 'string', 'uuid')],
                'ref:#/components/schemas/NonTradeActivityEvent'),
            brokerSseShape('/v1/events/accounts/status',
                legacyDateRange + legacyUlidRange + [queryParameter('id', 'string')],
                'array-ref:#/components/schemas/AccountStatusEvent'),
            brokerSseShape('/v1/events/journals/status',
                legacyDateRange + legacyUlidRange + [queryParameter('id', 'string')],
                'array-ref:#/components/schemas/JournalStatusEvent'),
            brokerSseShape('/v1/events/transfers/status',
                legacyDateRange + legacyUlidRange,
                'array-ref:#/components/schemas/TransferStatusEvent'),
            brokerSseShape('/v2beta1/accounts/{account_id}/events/activities/{event_id}',
                [pathParameter('account_id', 'string', 'uuid'),
                    pathParameter('event_id', 'string', 'ulid')],
                'ref:#/components/schemas/ActivityEventV2'),
            brokerSseShape('/v2beta1/events/activities',
                activityRange,
                'array-ref:#/components/schemas/ActivityEventV2'),
            brokerSseShape('/v2/events/admin-actions',
                activityRange,
                'array-oneOf:#/components/schemas/AdminActionLegacyNote|' +
                    '#/components/schemas/AdminActionLiquidation|' +
                    '#/components/schemas/AdminActionTransactionCancel'),
            brokerSseShape('/v2/events/funding/status',
                dateUlidRange,
                'array-ref:#/components/schemas/StatusFundingEvent'),
            brokerSseShape('/v2/events/ipos',
                activityRange,
                'array-ref:#/components/schemas/IPOEvent'),
            brokerSseShape('/v2/events/journals/status',
                activityRange + [queryParameter('id', 'string')],
                'array-ref:#/components/schemas/JournalStatusEventV2'),
            brokerSseShape('/v2/events/system',
                activityRange,
                'array-ref:#/components/schemas/SystemEventV2'),
            brokerSseShape('/v2/events/trades',
                dateUlidRange,
                'array-ref:#/components/schemas/TradeUpdateEventV2'),
        ]
        brokerShapes.each { expectedShape ->
            def operation = broker.paths[expectedShape.path]?.get
            if (!(operation instanceof Map)) {
                throw new IllegalStateException(
                    "Broker SSE operation is no longer GET ${expectedShape.path}")
            }
            def parameters =
                resolvedParameterSignatures(broker, broker.paths[expectedShape.path], operation)
            if (parameters != expectedShape.parameters) {
                throw new IllegalStateException(
                    "Broker SSE parameters changed for ${expectedShape.path}: ${parameters}")
            }
            def schema = operation.responses['200']
                .content['text/event-stream'].schema
            def signature = sseSchemaSignature(schema)
            if (signature != expectedShape.schema) {
                throw new IllegalStateException(
                    "Broker SSE response schema changed for ${expectedShape.path}: ${signature}")
            }
            def security = resolvedSecurityRequirements(broker, operation)
            if (security != [['BasicAuth': []]].toSet()) {
                throw new IllegalStateException(
                    "Broker SSE security changed for ${expectedShape.path}: ${security}")
            }
        }
        def basicAuth = broker.components?.securitySchemes?.BasicAuth
        if (!(basicAuth instanceof Map) ||
            basicAuth.type != 'http' ||
            basicAuth.scheme != 'basic') {
            throw new IllegalStateException(
                "Broker BasicAuth definition changed: ${basicAuth}")
        }

        def trading = loadSpec(specFiles.trading.absolutePath) as Map
        def tradingOperation =
            trading.paths['/v2beta1/events/activities'].get
        def parameterSignatures = resolvedParameterSignatures(
            trading, trading.paths['/v2beta1/events/activities'], tradingOperation)
        if (parameterSignatures != activityRange) {
            throw new IllegalStateException(
                "Trading activity SSE parameters changed: ${parameterSignatures}")
        }
        def itemRef = tradingOperation.responses['200']
            .content['text/event-stream'].schema?.items?.get('$ref')
        if (itemRef != '#/components/schemas/ActivityEventV2') {
            throw new IllegalStateException(
                "Trading activity SSE item schema changed: ${itemRef}")
        }
        def tradingSecurity = resolvedSecurityRequirements(trading, tradingOperation)
        if (tradingSecurity != [['API_Key': [], 'API_Secret': []]].toSet()) {
            throw new IllegalStateException(
                "Trading activity SSE security changed: ${tradingSecurity}")
        }
        assertApiKeySecurityScheme(
            trading, 'API_Key', 'APCA-API-KEY-ID', 'Trading')
        assertApiKeySecurityScheme(
            trading, 'API_Secret', 'APCA-API-SECRET-KEY', 'Trading')

        def data = loadSpec(specFiles.data.absolutePath) as Map
        def corporateActionsOperation =
            data.paths['/v1beta1/events/corporate-actions'].get
        def corporateActionsParameters = resolvedParameterSignatures(
            data, data.paths['/v1beta1/events/corporate-actions'], corporateActionsOperation)
        def eventIdSchema = [
            type: 'string',
            format: 'ulid',
            pattern: '^[0-7][0-9A-HJKMNP-TV-Z]{25}$',
        ]
        def corporateActionEventTypeSchema = [
            type: 'string',
            enum: [
                'capital_gains_distribution_corporateaction_event',
                'cash_dividend_corporateaction_event',
                'cash_merger_corporateaction_event',
                'equity_partial_call_corporateaction_event',
                'forward_split_corporateaction_event',
                'name_change_corporateaction_event',
                'redemption_corporateaction_event',
                'reorganization_corporateaction_event',
                'reverse_split_corporateaction_event',
                'rights_distribution_corporateaction_event',
                'spin_off_corporateaction_event',
                'stock_and_cash_merger_corporateaction_event',
                'stock_dividend_corporateaction_event',
                'stock_merger_corporateaction_event',
                'unit_split_corporateaction_event',
                'worthless_removal_corporateaction_event',
            ],
        ]
        def expectedCorporateActionsParameters = [
            queryArrayReferenceParameter(
                'type',
                '#/components/schemas/corporate_action_event_type',
                corporateActionEventTypeSchema,
                false),
            querySchemaParameter(
                'region', [type: 'string', enum: ['all', 'us', 'non_us'], default: 'all']),
            queryParameter('since', 'string', 'date-time'),
            queryParameter('until', 'string', 'date-time'),
            queryReferenceParameter(
                'since_id', '#/components/schemas/event_id', eventIdSchema),
            queryReferenceParameter(
                'until_id', '#/components/schemas/event_id', eventIdSchema),
            headerReferenceParameter(
                'Last-Event-Id', '#/components/schemas/event_id', eventIdSchema),
        ]
        if (corporateActionsParameters != expectedCorporateActionsParameters) {
            throw new IllegalStateException(
                "Market Data corporate-actions SSE parameters changed: " +
                    "${corporateActionsParameters}")
        }
        def corporateActionsItemRef = corporateActionsOperation.responses['200']
            .content['text/event-stream'].schema?.items?.get('$ref')
        if (corporateActionsItemRef != '#/components/schemas/corporate_action_event') {
            throw new IllegalStateException(
                "Market Data corporate-actions SSE item schema changed: " +
                    "${corporateActionsItemRef}")
        }
        def corporateActionsSecurity =
            resolvedSecurityRequirements(data, corporateActionsOperation)
        def expectedCorporateActionsSecurity = [
            ['apiKey': [], 'apiSecret': []],
            ['BasicAuth': []],
        ].toSet()
        if (corporateActionsSecurity != expectedCorporateActionsSecurity) {
            throw new IllegalStateException(
                "Market Data corporate-actions SSE security changed: " +
                    "${corporateActionsSecurity}")
        }
        assertApiKeySecurityScheme(data, 'apiKey', 'APCA-API-KEY-ID', 'Market Data')
        assertApiKeySecurityScheme(
            data, 'apiSecret', 'APCA-API-SECRET-KEY', 'Market Data')
        def dataBasicAuth = data.components?.securitySchemes?.BasicAuth
        if (!(dataBasicAuth instanceof Map) ||
            dataBasicAuth.type != 'http' ||
            dataBasicAuth.scheme != 'basic') {
            throw new IllegalStateException(
                "Market Data BasicAuth definition changed: ${dataBasicAuth}")
        }
    }

    private static Map contract(
        String path, String operationId, String status, String binding = null) {
        [path: path, operationId: operationId, status: status, binding: binding]
    }

    private static Map brokerSseShape(String path, List parameters, String schema) {
        [path: path, parameters: parameters, schema: schema]
    }

    private static List resolvedParameterSignatures(Map spec, Object pathItem, Map operation) {
        def parameters = []
        if (pathItem instanceof Map && pathItem.parameters instanceof List) {
            parameters.addAll(pathItem.parameters)
        }
        if (operation.parameters instanceof List) {
            parameters.addAll(operation.parameters)
        }
        parameters.collect { parameter ->
            def resolved = parameter instanceof Map && parameter['$ref']
                ? spec.components?.parameters?.get(
                    parameter['$ref'].toString().tokenize('/').last())
                : parameter
            resolved instanceof Map
                ? parameterSignature(resolved, spec)
                : "invalid:${parameter}"
        }
    }

    private static String queryParameter(String name, String type, String format = null) {
        def schema = [type: type]
        if (format != null) schema.format = format
        parameterSignature([name: name, in: 'query', schema: schema])
    }

    private static String pathParameter(String name, String type, String format = null) {
        def schema = [type: type]
        if (format != null) schema.format = format
        parameterSignature([name: name, in: 'path', required: true, schema: schema])
    }

    private static String querySchemaParameter(String name, Map schema) {
        parameterSignature([name: name, in: 'query', schema: schema])
    }

    private static String queryReferenceParameter(
        String name, String reference, Map resolvedSchema) {
        parameterSignature([
            name: name,
            in: 'query',
            schema: ['$ref': reference, 'x-expected-resolved': resolvedSchema],
        ])
    }

    private static String headerReferenceParameter(
        String name, String reference, Map resolvedSchema) {
        parameterSignature([
            name: name,
            in: 'header',
            schema: ['$ref': reference, 'x-expected-resolved': resolvedSchema],
        ])
    }

    private static String queryArrayReferenceParameter(
        String name, String itemReference, Map resolvedItemSchema, boolean explode) {
        parameterSignature([
            name: name,
            in: 'query',
            style: 'form',
            explode: explode,
            schema: [
                type: 'array',
                items: [
                    '$ref': itemReference,
                    'x-expected-resolved': resolvedItemSchema,
                ],
            ],
        ])
    }

    private static String parameterSignature(Map parameter, Map spec = null) {
        def location = parameter['in']
        def style = parameter.containsKey('style')
            ? parameter.style
            : defaultParameterStyle(location)
        def explode = parameter.containsKey('explode')
            ? parameter.explode
            : style == 'form'
        def required = parameter.containsKey('required')
            ? parameter.required
            : location == 'path'
        def allowReserved = parameter.containsKey('allowReserved')
            ? parameter.allowReserved
            : false
        "${parameter.name}|${location}|required=${required}|" +
            "schema=${parameterSchemaSignature(spec, parameter.schema, [] as Set)}|" +
            "style=${style}|explode=${explode}|allowReserved=${allowReserved}"
    }

    private static String defaultParameterStyle(Object location) {
        switch (location) {
            case 'query':
            case 'cookie':
                return 'form'
            case 'path':
            case 'header':
                return 'simple'
            default:
                return 'missing'
        }
    }

    private static String parameterSchemaSignature(
        Map spec, Object schema, Set<String> resolvingReferences) {
        if (!(schema instanceof Map)) return 'missing'
        if (schema['$ref']) {
            String reference = schema['$ref']
            if (!resolvingReferences.add(reference)) return "ref:${reference}{recursive}"
            def resolved = schema['x-expected-resolved']
            if (resolved == null && reference.startsWith('#/components/schemas/')) {
                resolved = spec?.components?.schemas?.get(reference.tokenize('/').last())
            }
            String resolvedSignature = parameterSchemaSignature(
                spec, resolved, resolvingReferences)
            resolvingReferences.remove(reference)
            return "ref:${reference}{${resolvedSignature}}"
        }
        def signature = "${schema.type ?: 'missing'}:${schema.format ?: '-'}"
        if (schema.type == 'array') {
            signature += "[${parameterSchemaSignature(spec, schema.items, resolvingReferences)}]"
        }
        def constraintKeys = [
            'enum', 'default', 'pattern', 'minLength', 'maxLength', 'minimum', 'maximum',
            'exclusiveMinimum', 'exclusiveMaximum', 'minItems', 'maxItems', 'uniqueItems',
        ]
        def constraints = constraintKeys.findAll { schema.containsKey(it) }.collect { key ->
            "${key}=${parameterConstraintValue(schema[key])}"
        }
        if (!constraints.isEmpty()) {
            signature += "{${constraints.join(',')}}"
        }
        signature
    }

    private static String parameterConstraintValue(Object value) {
        if (value instanceof List) {
            return "[${value.collect { parameterConstraintValue(it) }.join('|')}]"
        }
        value == null ? 'null' : value.toString()
    }

    private static Set resolvedSecurityRequirements(Map spec, Map operation) {
        def security = operation.containsKey('security')
            ? operation.security
            : spec.security
        if (!(security instanceof List)) return [] as Set
        security.collect { requirement ->
            if (!(requirement instanceof Map)) {
                return ['<invalid>': [requirement.toString()]]
            }
            requirement.collectEntries { name, scopes ->
                def normalizedScopes = scopes instanceof List
                    ? scopes.collect { it.toString() }.sort()
                    : ['<invalid>']
                [(name.toString()): normalizedScopes]
            }
        }.toSet()
    }

    private static void assertApiKeySecurityScheme(
        Map spec, String schemeName, String headerName, String apiName) {
        def scheme = spec.components?.securitySchemes?.get(schemeName)
        if (!(scheme instanceof Map) ||
            scheme.type != 'apiKey' ||
            scheme.in != 'header' ||
            scheme.name != headerName) {
            throw new IllegalStateException(
                "${apiName} ${schemeName} definition changed: ${scheme}")
        }
    }

    private static String sseSchemaSignature(Object schema) {
        if (!(schema instanceof Map)) return 'missing'
        if (schema['$ref']) return "ref:${schema['$ref']}"
        if (schema.type != 'array' || !(schema.items instanceof Map)) {
            return "unsupported:${schema}"
        }
        def items = schema.items as Map
        if (items['$ref']) return "array-ref:${items['$ref']}"
        if (items.oneOf instanceof List) {
            def references = items.oneOf.collect { it instanceof Map ? it['$ref'] : null }
            if (references.every { it }) {
                return "array-oneOf:${references.sort().join('|')}"
            }
        }
        return "unsupported:${schema}"
    }

    private static void validateSseSupportDecision(
        Map operation, Map<String, File> specFiles) {
        def allowedStatuses = ['supported', 'deferred', 'deprecated'].toSet()
        if (!(operation.status in allowedStatuses)) {
            throw new IllegalStateException(
                "Unknown SSE support status '${operation.status}' for ${operation.path}")
        }
        if (operation.status == 'supported' && !operation.binding) {
            throw new IllegalStateException(
                "Supported SSE operation ${operation.path} has no handwritten binding")
        }
        if (operation.status != 'supported' && operation.binding) {
            throw new IllegalStateException(
                "${operation.status} SSE operation ${operation.path} must not declare a binding")
        }
        if (!operation.binding) return

        def parts = operation.binding.toString().split('#', 2)
        if (parts.length != 2 || !parts[0] || !parts[1]) {
            throw new IllegalStateException(
                "Invalid SSE binding '${operation.binding}' for ${operation.path}")
        }
        def projectDir = specFiles.values().first().parentFile.parentFile.parentFile
        def source = new File(
            projectDir, "src/main/java/${parts[0].replace('.', '/')}.java")
        if (!source.isFile() ||
            !(source.text =~ /\b${java.util.regex.Pattern.quote(parts[1])}\s*\(/).find()) {
            throw new IllegalStateException(
                "SSE binding ${operation.binding} for ${operation.path} was not found")
        }
    }

    private static void traverseSchemas(Map spec, Closure visitor) {
        def visited = Collections.newSetFromMap(new IdentityHashMap())
        schemaRoots(spec).each { root -> visitSchema(root, false, visited, visitor) }
    }

    private static void visitSchema(Object node, boolean composed, Set visited, Closure visitor) {
        if (!(node instanceof Map) || !visited.add(node)) return
        visitor.call(node, composed)

        NESTED_SCHEMA_KEYS.each { key -> visitSchema(node[key], false, visited, visitor) }
        (COMPOSITION_KEYS + NESTED_SCHEMA_LIST_KEYS).each { key ->
            def parts = node[key]
            if (parts instanceof List) {
                parts.each { visitSchema(it, key in COMPOSITION_KEYS, visited, visitor) }
            }
        }
        NESTED_SCHEMA_MAP_KEYS.each { key ->
            def entries = node[key]
            if (entries instanceof Map) {
                entries.each { name, value -> visitSchema(value, false, visited, visitor) }
            }
        }
    }

    /** Collects every position in the document that holds a schema object. */
    private static List schemaRoots(Map spec) {
        def roots = []
        def components = spec?.components
        if (components instanceof Map) {
            [components.schemas, components.headers].each { entries ->
                if (entries instanceof Map) roots.addAll(entries.values())
            }
            if (components.parameters instanceof Map) {
                roots.addAll(parameterSchemas(components.parameters.values().toList()))
            }
            [components.responses, components.requestBodies].each { entries ->
                if (!(entries instanceof Map)) return
                entries.each { name, value -> roots.addAll(contentSchemas(value)) }
            }
        }

        def paths = spec?.paths
        if (paths instanceof Map) {
            paths.each { path, item ->
                if (!(item instanceof Map)) return
                roots.addAll(parameterSchemas(item['parameters']))
                OPERATION_METHODS.each { method ->
                    def operation = item[method]
                    if (!(operation instanceof Map)) return
                    roots.addAll(parameterSchemas(operation['parameters']))
                    roots.addAll(contentSchemas(operation['requestBody']))
                    def responses = operation['responses']
                    if (responses instanceof Map) {
                        responses.each { code, response -> roots.addAll(contentSchemas(response)) }
                    }
                }
            }
        }
        roots.findAll { it instanceof Map }
    }

    private static List parameterSchemas(Object parameters) {
        parameters instanceof List
            ? parameters.collect { it instanceof Map ? it['schema'] : null }
            : []
    }

    private static List contentSchemas(Object holder) {
        def content = holder instanceof Map ? holder['content'] : null
        if (!(content instanceof Map)) return []
        content.values().collect { it instanceof Map ? it['schema'] : null }
    }

    /** Resolves a local {@code #/components/schemas} reference; returns null for anything else. */
    private static Object resolveSchemaRef(Object schemas, Object schema) {
        if (!(schema instanceof Map)) return null
        def ref = schema['$ref']
        if (!ref) return schema
        if (!(schemas instanceof Map) || !ref.toString().startsWith(SCHEMA_REF_PREFIX)) return null
        schemas[ref.toString().substring(SCHEMA_REF_PREFIX.length())]
    }

    /** Identity of a parameter as OpenAPI defines it: its location plus its name. */
    private static String parameterKey(Map spec, Object parameter) {
        if (!(parameter instanceof Map)) return null
        def resolved = parameter['$ref']
            ? spec?.components?.parameters?.get(parameter['$ref'].toString().tokenize('/').last())
            : parameter
        if (!(resolved instanceof Map)) return null
        def name = resolved['name']
        def location = resolved['in']
        (name && location) ? "${location}:${name}".toString() : null
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map) {
            def copy = new LinkedHashMap()
            value.each { key, entry -> copy[key] = deepCopy(entry) }
            return copy
        }
        if (value instanceof List) return value.collect { deepCopy(it) }
        value
    }

    private static void constrainActivityType(Object schema, List values) {
        def property = schema?.properties?.get('activity_type')
        if (!(schema instanceof Map) || !(property instanceof Map) || values.isEmpty()) {
            return
        }

        def required = schema.required instanceof List
            ? new LinkedHashSet(schema.required)
            : new LinkedHashSet()
        required.add('activity_type')
        schema.required = new ArrayList(required)

        property.clear()
        property.type = 'string'
        property.enum = new ArrayList(values)
    }

    static String javadocText(Object value) {
        def text = value == null ? '' : value.toString().trim()
        text
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
    }

    /**
     * Normalizes a package-info template into canonical Javadoc layout.
     *
     * <p>{@code stripIndent()} cannot be used here: interpolated multi-line fragments
     * reset the common indent to zero and leave the rest of the template indented.</p>
     */
    static String renderPackageInfo(String content) {
        def lines = []
        def inJavadoc = false
        content.trim().readLines().each { rawLine ->
            def line = rawLine.trim()
            if (line == '/**') {
                inJavadoc = true
                lines << line
            } else if (inJavadoc && line.startsWith('*/')) {
                inJavadoc = false
                lines << " ${line}".replaceFirst(/\s+$/, '')
            } else if (inJavadoc) {
                def body = line.startsWith('*') ? line : "* ${line}"
                lines << " ${body}".replaceFirst(/\s+$/, '')
            } else {
                lines << line
            }
        }
        lines.join(System.lineSeparator()) + System.lineSeparator()
    }

    static void writeOpenApiRootPackageInfo(File javaSourceRoot) {
        def packageDir = new File(
            javaSourceRoot,
            'markets/alpaca/client/openapi')
        packageDir.mkdirs()
        new File(packageDir, 'package-info.java').text = renderPackageInfo("""
            /**
             * Autogenerated OpenAPI REST clients for Alpaca Broker, Market Data, and Trading.
             *
             * <p><b>Do not edit any type in this package tree by hand.</b> Sources under
             * {@code markets.alpaca.client.openapi} are produced by OpenAPI Generator from the
             * pinned specs in {@code specs/}. Hand edits are overwritten on the next
             * {@code ./gradlew generateApis} or {@code ./gradlew adoptOpenApi} /
             * {@code ./gradlew adoptOpenApiBreaking} run.</p>
             *
             * <p>Fix generation defects in preprocessing
             * ({@code OpenApiSpecSupport} / {@code alpaca.openapi-generation.gradle}) or Mustache
             * templates under {@code src/main/openapi-templates/}. Add SDK behavior in handwritten
             * packages such as {@link markets.alpaca.client.AlpacaClientFactory}.</p>
             *
             * <p>Subpackages: {@code broker}, {@code data}, and {@code trading}, each with
             * {@code api}, {@code model}, and {@code http}.</p>
             */
            package markets.alpaca.client.openapi;
            """)
    }

    static void writeOpenApiPackageInfo(
        File outputDir,
        String apiName,
        String specSource,
        String rootPackage,
        String apiPackage,
        String modelPackage,
        String invokerPackage
    ) {
        def spec = loadSpec(specSource)
        def title = javadocText(spec?.info?.title ?: apiName)
        def version = javadocText(spec?.info?.version ?: 'unknown')
        def description = javadocText(spec?.info?.description)
        def apiLabel = javadocText(apiName)
        def doNotEdit = """
                 * <p><b>Do not edit this package by hand.</b> It is autogenerated from the pinned
                 * OpenAPI spec under {@code specs/}. Regenerate with {@code ./gradlew generateApis}
                 * or {@code ./gradlew adoptOpenApi} / {@code ./gradlew adoptOpenApiBreaking}.</p>
            """.stripIndent().trim()

        def packageDocs = [
            (rootPackage): """
                /**
                 * Generated REST client packages for the ${apiLabel}.
                 *
                 ${doNotEdit}
                 *
                 * <p>Generated from the configured OpenAPI spec: <b>${title}</b>, version
                 * <b>${version}</b>.</p>
                 * ${description ? "<p>${description}</p>" : ""}
                 *
                 * <p>The {@code api} package contains endpoint clients, {@code model} contains
                 * request/response DTOs and enums, and {@code http} contains the generated transport,
                 * serialization, callback, response, and exception types. Use
                 * {@link markets.alpaca.client.AlpacaClientFactory} to create these clients with the
                 * correct Alpaca authentication scheme.</p>
                 */
                package ${rootPackage};
            """,
            (apiPackage): """
                /**
                 * Generated endpoint clients for the ${apiLabel}.
                 *
                 ${doNotEdit}
                 *
                 * <p>Classes in this package map OpenAPI operations to Java methods. Method Javadocs
                 * include operation summaries, descriptions, parameters, response details, and external
                 * documentation links when those fields are present in the OpenAPI spec.</p>
                 *
                 * <p>Prefer creating clients through {@link markets.alpaca.client.AlpacaClientFactory}
                 * so base URLs, authentication, and HTTP client configuration are applied correctly.</p>
                 */
                package ${apiPackage};
            """,
            (modelPackage): """
                /**
                 * Generated request and response models for the ${apiLabel}.
                 *
                 ${doNotEdit}
                 *
                 * <p>Model class and accessor Javadocs are generated from OpenAPI schema descriptions,
                 * property descriptions, enum values, nullability, and deprecation metadata when those
                 * fields are present in the spec.</p>
                 */
                package ${modelPackage};
            """,
            (invokerPackage): """
                /**
                 * Generated HTTP transport support for the ${apiLabel}.
                 *
                 ${doNotEdit}
                 *
                 * <p>This package contains the generated {@code ApiClient}, {@code ApiException},
                 * {@code ApiResponse}, JSON serialization helpers, callbacks, and request/response
                 * support classes used by the generated endpoint clients.</p>
                 */
                package ${invokerPackage};
            """,
            ("${invokerPackage}.auth"): """
                /**
                 * Generated authentication helpers for the ${apiLabel}.
                 *
                 ${doNotEdit}
                 *
                 * <p>Applications normally do not configure these classes directly. Use
                 * {@link markets.alpaca.client.AlpacaClientFactory}, which wires Alpaca credentials
                 * into the generated authentication objects for each API.</p>
                 */
                package ${invokerPackage}.auth;
            """,
        ]

        packageDocs.each { packageName, content ->
            def packageDir = new File(
                outputDir,
                "src/main/java/${packageName.replace('.', '/')}")
            packageDir.mkdirs()
            new File(packageDir, 'package-info.java').text = renderPackageInfo(content)
        }
    }

}
