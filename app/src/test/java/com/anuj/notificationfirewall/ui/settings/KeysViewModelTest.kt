package com.anuj.notificationfirewall.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KeysViewModelTest {

    @Test
    fun transportFailureBecomesFailedResult() = runTest {
        val tester = JevKeyTester { throw IOException("offline") }

        val result = tester.test("jev-key")

        assertTrue(result.isFailure)
        assertEquals("offline", result.exceptionOrNull()?.message)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() = runTest {
        val tester = JevKeyTester { throw CancellationException("cancel") }

        tester.test("jev-key")
    }
}
