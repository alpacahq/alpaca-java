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
            def connection = new URI(source).toURL().openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            return connection.getInputStream().withCloseable { stream -> new Yaml().load(stream) }
        }
        new File(source).withInputStream { stream -> new Yaml().load(stream) }
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
