package com.booklistener.ui

import com.booklistener.auth.CredentialStore
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.db.AppDatabase
import com.booklistener.player.PlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * v1.6.7：循环模式三档轮换 + prefs 持久化 + 播放控制器同步。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepeatModeTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    private fun newViewModel(): PlayerViewModel =
        PlayerViewModel(ApplicationProvider.getApplicationContext(), database = db, credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false))

    @Test
    fun defaultMode_isOff() {
        assertEquals("默认应为顺序播放", Player.REPEAT_MODE_OFF, newViewModel().repeatMode.value)
    }

    @Test
    fun cycleRotatesOffOneAll() {
        val vm = newViewModel()
        vm.cycleRepeatMode()
        assertEquals(Player.REPEAT_MODE_ONE, vm.repeatMode.value)
        assertEquals("循环模式应同步到播放控制器", Player.REPEAT_MODE_ONE, PlaybackController.repeatMode.value)
        vm.cycleRepeatMode()
        assertEquals(Player.REPEAT_MODE_ALL, vm.repeatMode.value)
        vm.cycleRepeatMode()
        assertEquals(Player.REPEAT_MODE_OFF, vm.repeatMode.value)
    }

    @Test
    fun repeatMode_persistsAcrossInstances() {
        val vm = newViewModel()
        vm.setRepeatMode(Player.REPEAT_MODE_ALL)
        assertEquals("持久化后新实例应恢复", Player.REPEAT_MODE_ALL, newViewModel().repeatMode.value)
    }
}

/**
 * v1.6.7：一次性定时关闭（设置时刻起倒计时到点暂停）与全局定时关闭
 * （每次开始播放重置，到点暂停；一次性激活期间不触发）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    private fun newViewModel(): PlayerViewModel =
        PlayerViewModel(ApplicationProvider.getApplicationContext(), database = db, credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false))

    private fun idleFor(millis: Long) {
        ShadowLooper.idleMainLooper(TimeUnit.MILLISECONDS.toMillis(millis), TimeUnit.MILLISECONDS)
    }

    @Test
    fun onceTimer_expiresThenClearsAndNotifies() {
        val vm = newViewModel()
        vm.startSleepOnce(1500)
        assertTrue("设置后应显示剩余时间", vm.sleepOnceRemaining.value != null)
        idleFor(3000)
        assertNull("到点后应清除剩余时间", vm.sleepOnceRemaining.value)
        assertEquals("到点应提示已暂停", com.booklistener.core.MessageCatalog.MSG_SLEEP_TIMER_DONE, vm.eventMsg.value)
    }

    @Test
    fun onceTimer_cancelClearsImmediately() {
        val vm = newViewModel()
        vm.startSleepOnce(System.currentTimeMillis() + 60_000)
        vm.cancelSleepTimerOnce()
        assertNull("取消后应清除剩余时间", vm.sleepOnceRemaining.value)
        idleFor(2000)
        assertNull("取消后不应到点暂停", vm.eventMsg.value)
    }

    @Test
    fun globalTimer_zeroMinutesFiresImmediately() {
        val vm = newViewModel()
        vm.startGlobalSleepTimer(0)
        idleFor(1000)
        assertEquals(
            "全局 0 分钟应立即到点暂停",
            com.booklistener.core.MessageCatalog.MSG_GLOBAL_SLEEP_DONE,
            vm.eventMsg.value
        )
    }

    @Test
    fun globalTimer_skippedWhenOnceActive() {
        val vm = newViewModel()
        vm.startSleepOnce(System.currentTimeMillis() + 3_600_000)
        vm.startGlobalSleepTimer(0)
        idleFor(1000)
        assertNull("一次性激活期间全局定时不应触发", vm.eventMsg.value)
    }
}

/**
 * v1.6.7：AB 段循环判定纯逻辑——仅当 A/B 均设置且 B>A 且播放位置到达 B 时跳回 A。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbLoopLogicTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    private fun vm(): PlayerViewModel =
        PlayerViewModel(ApplicationProvider.getApplicationContext(), database = db, credentialStore = CredentialStore(ApplicationProvider.getApplicationContext(), secure = false))

    @Test
    fun noPoints_noLoop() {
        val m = vm()
        assertTrue(!m.shouldLoopAb(5000, null, null))
        assertTrue(!m.shouldLoopAb(5000, 1000, null))
        assertTrue(!m.shouldLoopAb(5000, null, 9000))
    }

    @Test
    fun invalidInterval_noLoop() {
        val m = vm()
        assertTrue("B 等于 A 不应循环", !m.shouldLoopAb(2000, 1000, 1000))
        assertTrue("B 小于 A 不应循环", !m.shouldLoopAb(5000, 3000, 1000))
    }

    @Test
    fun reachedB_loopsBackToA() {
        val m = vm()
        assertTrue(!m.shouldLoopAb(999, 1000, 5000))
        assertTrue("到达 B 应立即跳回 A", m.shouldLoopAb(5000, 1000, 5000))
        assertTrue(m.shouldLoopAb(5001, 1000, 5000))
        assertTrue("B 之前不应循环", !m.shouldLoopAb(4999, 1000, 5000))
    }
}
