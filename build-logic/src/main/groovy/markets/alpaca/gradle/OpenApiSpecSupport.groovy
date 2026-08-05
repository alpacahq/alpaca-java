package markets.alpaca.gradle

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

final class OpenApiSpecSupport {
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

    /** Shared Broker sanitizers for pin preprocess and upstream-adopt preprocess. */
    static void sanitizeBrokerSpec(Map spec) {
        removeEmptyKeyProperties(spec)
        removeDiscriminatorEnums(spec)
        removeActivityV2DetailTrdRequired(spec)
    }

    /** Shared Market Data sanitizers for pin preprocess and upstream-adopt preprocess. */
    static void sanitizeDataSpec(Map spec) {
        removeInternalSchemaMarkers(spec)
    }

    /** Shared Trading sanitizers for pin preprocess and upstream-adopt preprocess. */
    static void sanitizeTradingSpec(Map spec) {
        removeActivityV2DetailTrdRequired(spec)
        requireDistinctAccountActivityTypes(spec)
    }

    static void writeSanitizedSpec(String source, File outputFile, Closure sanitize) {
        outputFile.parentFile.mkdirs()
        def spec = loadSpec(source) as Map
        sanitize.call(spec)
        outputFile.text = dumpYaml(spec)
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
