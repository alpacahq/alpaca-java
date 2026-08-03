package markets.alpaca.gradle

import org.gradle.api.Project

final class AlpacaBuildProperties {
    static final String BROKER_SPEC_DEFAULT =
        'https://docs.alpaca.markets/openapi/broker-api.json'
    static final String DATA_SPEC_DEFAULT =
        'https://docs.alpaca.markets/openapi/market-data-api.json'
    static final String TRADING_SPEC_DEFAULT =
        'https://docs.alpaca.markets/openapi/trading-api.json'

    private AlpacaBuildProperties() {}

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
        [
            broker: resolveOne(
                project, localProperties, 'broker', 'brokerSpec', 'APCA_BROKER_SPEC', BROKER_SPEC_DEFAULT),
            data: resolveOne(
                project, localProperties, 'data', 'dataSpec', 'APCA_DATA_SPEC', DATA_SPEC_DEFAULT),
            trading: resolveOne(
                project, localProperties, 'trading', 'tradingSpec', 'APCA_TRADING_SPEC', TRADING_SPEC_DEFAULT),
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
