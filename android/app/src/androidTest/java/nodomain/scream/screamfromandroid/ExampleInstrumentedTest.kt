package nodomain.scream.screamfromandroid

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * 在 Android 设备上运行的仪器化测试，检查应用包名。
 *
 * 参见[测试文档](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    /** 断言设备上的目标应用使用预期的包名。 */
    @Test
    fun useAppContext() {
        // 获取被测应用的上下文。
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("nodomain.scream.screamfromandroid", appContext.packageName)
    }
}