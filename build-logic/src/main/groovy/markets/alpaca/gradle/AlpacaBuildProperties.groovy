package markets.alpaca.gradle

import groovy.json.JsonSlurper
import org.gradle.api.Project

final class AlpacaBuildProperties {
    private AlpacaBuildProperties() {}

    static Map<String, String> upstreamUrlDefaults(File projectDir) {
        def urlsFile = new File(projectDir, 'scripts/upstream_openapi_urls.json')
        if (!urlsFile.isFile()) {
            throw new IllegalStateException(
                "Missing upstream OpenAPI URL map: ${urlsFile.absolutePath}")
        }
        def parsed = new JsonSlurper().parse(urlsFile)
        if (!(parsed instanceof Map)) {
            throw new IllegalStateException(
                "upstream_openapi_urls.json must be a JSON object: ${urlsFile.absolutePath}")
        }
        def required = ['broker', 'data', 'trading']
        Map<String, String> urls = [:]
        required.each { api ->
            def value = parsed[api]
            if (!(value instanceof String) || value.isBlank()) {
                throw new IllegalStateException(
                    "upstream_openapi_urls.json missing non-empty '${api}' URL")
            }
            urls[api] = value
        }
        urls
    }

    static Properties loadLocalProperties(Project project) {
        def properties = new Properties()
        def propertiesFile = project.rootProject.file('local.properties')
        if (propertiesFile.exists()) {
            propertiesFile.withInputStream { properties.load(it) }
        }
        properties
    }

    static String firstNonBlank(String... values) {
        values.find { it != null && !it.isBlank() }
    }

    static String pinnedSpecRelativePath(String api) {
        "specs/${api}/openapi.yaml"
    }

    static String resolveOne(
        Project project,
        Properties localProperties,
        String api,
        String propName,
        String envName,
        String urlDefault) {
        def oasRoot = (project.findProperty('oasRoot') ?:
            System.getenv('APCA_OAS_ROOT') ?:
            localProperties.getProperty('oasRoot')) as String
        def pinned = project.rootProject.file(pinnedSpecRelativePath(api))
        return (project.findProperty(propName) ?:
            System.getenv(envName) ?:
            localProperties.getProperty(propName) ?:
            (oasRoot ? "${oasRoot}/${api}/openapi.yaml" : null) ?:
            (pinned.exists() ? pinned.absolutePath : null) ?:
            urlDefault) as String
    }

    static Map<String, String> resolveSpecSources(Project project, Properties localProperties) {
        def upstream = upstreamUrlDefaults(project.rootProject.projectDir)
        [
            broker: resolveOne(
                project, localProperties, 'broker', 'brokerSpec', 'APCA_BROKER_SPEC', upstream.broker),
            data: resolveOne(
                project, localProperties, 'data', 'dataSpec', 'APCA_DATA_SPEC', upstream.data),
            trading: resolveOne(
                project, localProperties, 'trading', 'tradingSpec', 'APCA_TRADING_SPEC', upstream.trading),
        ]
    }

    static boolean isCommittedPin(Project project, String api, String source) {
        if (OpenApiSpecSupport.isUrl(source)) {
            return false
        }
        def pinned = project.rootProject.file(pinnedSpecRelativePath(api))
        def sourceFile = project.rootProject.file(source)
        pinned.exists() && sourceFile.canonicalFile == pinned.canonicalFile
    }
}
