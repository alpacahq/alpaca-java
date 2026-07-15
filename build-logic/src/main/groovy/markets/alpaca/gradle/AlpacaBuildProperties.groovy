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

    static Map<String, String> resolveSpecSources(Project project, Properties localProperties) {
        def oasRoot = (project.findProperty('oasRoot') ?:
            System.getenv('APCA_OAS_ROOT') ?:
            localProperties.getProperty('oasRoot')) as String

        [
            broker: (project.findProperty('brokerSpec') ?:
                System.getenv('APCA_BROKER_SPEC') ?:
                localProperties.getProperty('brokerSpec') ?:
                (oasRoot ? "${oasRoot}/broker/openapi.yaml" : null) ?:
                BROKER_SPEC_DEFAULT) as String,
            data: (project.findProperty('dataSpec') ?:
                System.getenv('APCA_DATA_SPEC') ?:
                localProperties.getProperty('dataSpec') ?:
                (oasRoot ? "${oasRoot}/data/openapi.yaml" : null) ?:
                DATA_SPEC_DEFAULT) as String,
            trading: (project.findProperty('tradingSpec') ?:
                System.getenv('APCA_TRADING_SPEC') ?:
                localProperties.getProperty('tradingSpec') ?:
                (oasRoot ? "${oasRoot}/trading/openapi.yaml" : null) ?:
                TRADING_SPEC_DEFAULT) as String,
        ]
    }
}
