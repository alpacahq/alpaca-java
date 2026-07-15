package markets.alpaca.gradle

import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

final class AlpacaTestSummaryListener implements TestListener {
    @Override
    void beforeSuite(TestDescriptor suite) {}

    @Override
    void beforeTest(TestDescriptor test) {}

    @Override
    void afterTest(TestDescriptor test, TestResult result) {}

    @Override
    void afterSuite(TestDescriptor suite, TestResult result) {
        if (suite.parent != null) return
        def passRate = result.testCount > 0
            ? String.format(
                '%d%%',
                (result.successfulTestCount * 100 / result.testCount) as int)
            : 'n/a'
        println "\nTests: ${result.testCount} executed, " +
            "${result.successfulTestCount} passed, " +
            "${result.failedTestCount} failed, " +
            "${result.skippedTestCount} skipped " +
            "(${passRate} pass rate) — ${result.resultType}"
    }
}
