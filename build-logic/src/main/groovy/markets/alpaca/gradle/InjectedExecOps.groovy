package markets.alpaca.gradle

import org.gradle.process.ExecOperations

import javax.inject.Inject

/**
 * Injection point for {@link ExecOperations} (Gradle 9 removed {@code Project#exec}).
 */
interface InjectedExecOps {
    @Inject
    ExecOperations getExecOps()
}
