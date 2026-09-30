package ai.passioncode.fabricvr.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs a test dispatcher as `Dispatchers.Main` for the length of one test class.
 *
 * Every view model here launches on `viewModelScope`, which is `Dispatchers.Main.immediate`, so a
 * class that forgets this fails with `Module with the Main dispatcher had failed to initialize` —
 * a message about a missing Android runtime, which is not what went wrong. Five test classes
 * re-type the same `setMain`/`resetMain` pair by hand today; each new one rediscovers it, and the
 * one that forgets spends its first half-hour on the wrong problem.
 *
 * **Use `rule.dispatcher` as the test's own scheduler too** — `runTest(rule.dispatcher)`. A bare
 * `runTest {}` in a class that installed a different dispatcher as Main builds a *second*
 * scheduler that nothing pumps, so work queued on Main never runs and assertions read the initial
 * state as if the act had done nothing. That is not hypothetical: it is why `T-029`'s two
 * prescribed tests would have passed against an untouched view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
