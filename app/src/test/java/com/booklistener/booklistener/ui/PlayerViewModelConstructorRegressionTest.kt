package com.booklistener.ui

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6.0 启动闪退根因回归：PlayerViewModel 曾因第二可选参数 database
 * 丢失 (Application) 单参构造，AndroidViewModelFactory 反射创建时
 * NoSuchMethodException → 打开即闪退（debug/release 均崩，与混淆无关；
 * 既有 UT 全部直接 new ViewModel，未走 ViewModelProvider，故全绿未暴露）。
 * @JvmOverloads 修复后，必须存在 (Application) 构造。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerViewModelConstructorRegressionTest {

    @Test
    fun playerViewModel_exposesSingleApplicationConstructor() {
        val ctor = PlayerViewModel::class.java.getDeclaredConstructor(Application::class.java)
        assertNotNull(ctor)
        assertEquals(1, ctor.parameterCount)
    }

    @Test
    fun playerViewModel_exposesApplicationPlusDatabaseConstructor() {
        val ctor = PlayerViewModel::class.java.getDeclaredConstructor(
            Application::class.java,
            com.booklistener.db.AppDatabase::class.java
        )
        assertNotNull(ctor)
        assertEquals(2, ctor.parameterCount)
    }
}
