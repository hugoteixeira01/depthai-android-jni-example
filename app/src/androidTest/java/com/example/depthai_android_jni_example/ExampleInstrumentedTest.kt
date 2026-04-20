package com.example.depthai_android_jni_example

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.depthai_android_jni_example", appContext.packageName)
    }
}