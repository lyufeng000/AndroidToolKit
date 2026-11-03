package com.lyufeng.phoneserialport

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.lyufeng.phoneserialport.core.*
import com.lyufeng.phoneserialport.data.AppSettings
import com.lyufeng.phoneserialport.data.MacroEntity
import com.lyufeng.phoneserialport.data.VofaCommand
import com.lyufeng.phoneserialport.data.VofaCommands
import com.lyufeng.phoneserialport.serial.SerialSession
import com.lyufeng.phoneserialport.ui.SerialApp
import com.lyufeng.phoneserialport.ui.SerialViewModel
import com.lyufeng.phoneserialport.ui.theme.PhoneSerialPortTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import android.content.pm.PackageManager
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[37],application=SerialApplication::class,qualifiers="w800dp-h480dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionUiTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=RuntimeEnvironment.getApplication() as SerialApplication
    private var model: SerialViewModel?=null
    private val sessions=mutableListOf<SerialSession>()
    private suspend fun session(id: String,key: String,write: (ByteArray)->WriteResult = {WriteResult(it.size)}): SerialSession {
        val adapter=object : SerialDriverAdapter {
            override val driverName="SelectionTest"
            override val capabilities=SerialCapabilities()
            override fun apply(config: SerialConfig)=BaudRateReport(config.baudRate)
            override fun read(buffer: ByteArray): Int {Thread.sleep(10);return 0}
            override fun write(bytes: ByteArray,timeoutMs: Int)=write(bytes)
            override fun control(name: String,enabled: Boolean) {}
            override fun readControlLines(): Map<String,Boolean> = emptyMap()
            override fun close() {}
        }
        return SerialSession(app,id,key,0,adapter,AppSettings()) {_,_ -> }.also {sessions+=it;it.start(SerialConfig())}
    }
    private fun show(): SerialViewModel {
        runBlocking {app.container.configuration.saveSettings(AppSettings(hintsSeen=true))}
        val expectedMacros=runBlocking {app.container.dao.allMacros()}
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_USB_HOST,true)
        app.devices.message.value=null
        ReflectionHelpers.getField<MutableStateFlow<Map<String,SerialSession>>>(app.devices,"mutableSessions").value=sessions.associateBy {it.id}
        val vm=SerialViewModel(app);model=vm
        sessions.firstOrNull()?.let {vm.select(it.id)}
        compose.setContent {PhoneSerialPortTheme {SerialApp(vm) {}}}
        compose.waitUntil(5000) {compose.runOnIdle {
            vm.active.value?.id == sessions.firstOrNull()?.id && vm.settings.value.hintsSeen && vm.macros.value == expectedMacros
        }}
        return vm
    }
    @After fun cleanup()=runBlocking {
        model?.viewModelScope?.cancel()
        sessions.forEach {it.close();it.store.close()}
        ReflectionHelpers.getField<CoroutineScope>(app.devices,"scope").cancel()
        app.container.database.close()
    }
    @Test fun selectingSessionUsesTheFullIdentifierWhenPrefixesCollide() {
        val first=runBlocking {session("cafe-1111","port-a")}
        val second=runBlocking {session("cafe-2222","port-b")}
        val vm=show()
        compose.onNodeWithText("菜单").performClick()
        compose.onNodeWithText("调试选项").performClick()
        compose.onNodeWithText("会话: port-a:0 ▾").performClick()
        compose.onNodeWithText("port-b:0",substring=true).performClick()
        compose.runOnIdle {assertEquals(second.id,vm.activeId.value);assertNotEquals(first.id,vm.activeId.value)}
    }
    @Test fun compactMacroButtonsUseTheChosenMacroWhenPrefixesCollide() {
        runBlocking {
            app.container.configuration.editRecords {dao ->
                dao.saveMacro(MacroEntity("cafe-1111","宏一","FIRST",insertOnly=true))
                dao.saveMacro(MacroEntity("cafe-2222","宏二","SECOND",insertOnly=true))
            }
        }
        val vm=show()
        compose.waitUntil(5000) {vm.macros.value.size == 2}
        compose.onNodeWithText("宏二",substring=false).performClick()
        compose.waitUntil(5000) {vm.input.value == "SECOND"}
    }
    @Test fun switchingLanguageUpdatesTheVisibleScreenWithoutRecreatingTheApp() {
        val vm=show()
        compose.onNodeWithText("串口调试").assertIsDisplayed()
        compose.runOnIdle {vm.save(vm.settings.value.copy(language="en"))}
        compose.waitUntil(5000) {compose.runOnIdle {vm.settings.value.language == "en"}}
        compose.onNodeWithText("Serial debug").assertIsDisplayed()
        compose.onNodeWithText("Menu").assertIsDisplayed()
        compose.onNodeWithText("串口调试").assertDoesNotExist()
        compose.runOnIdle {vm.save(vm.settings.value.copy(language="zh"))}
        compose.waitUntil(5000) {compose.runOnIdle {vm.settings.value.language == "zh"}}
        compose.onNodeWithText("串口调试").assertIsDisplayed()
    }
    @Test fun sendingOnAnotherPortDoesNotWaitForTheFirstPort() {
        val firstStarted=CountDownLatch(1);val releaseFirst=CountDownLatch(1);val secondSent=CountDownLatch(1)
        runBlocking {session("first","port-a") {bytes -> firstStarted.countDown();check(releaseFirst.await(5,TimeUnit.SECONDS));WriteResult(bytes.size)}}
        val second=runBlocking {session("second","port-b") {bytes -> secondSent.countDown();WriteResult(bytes.size)}}
        val vm=show()
        try {
            compose.runOnIdle {vm.input.value="A";vm.send()}
            assertTrue(firstStarted.await(2,TimeUnit.SECONDS))
            compose.runOnIdle {vm.select(second.id)}
            compose.waitUntil(5000) {compose.runOnIdle {vm.active.value?.id == second.id}}
            compose.runOnIdle {vm.input.value="B";vm.send()}
            assertTrue("The second port must send while the first port is waiting",secondSent.await(2,TimeUnit.SECONDS))
        } finally {releaseFirst.countDown()}
    }
    @Test @Config(qualifiers="w800dp-h360dp-land")
    fun smallLandscapeKeepsTheComposerAndSendControlsVisible() {
        show()
        compose.onNodeWithText("发送文本").assertIsDisplayed()
        compose.onNodeWithText("发送",substring=false).assertIsDisplayed()
        compose.onNodeWithText("历史").assertDoesNotExist()
        compose.onNodeWithText("参数与诊断").assertDoesNotExist()
        compose.onNodeWithText("设备",substring=false).assertDoesNotExist()
        compose.onNodeWithText("菜单").assertIsDisplayed()
        compose.onNodeWithText("菜单").performClick()
        compose.onNodeWithText("调试选项").performClick()
        compose.onNodeWithText("历史").performScrollTo().assertIsDisplayed()
    }
    @Test fun baudMenuDefaultsTo115200AndKeepsTheHighSpeedPreset() {
        show()
        compose.onNodeWithText("菜单").performClick()
        compose.onNodeWithText("设备",substring=false).performClick()
        compose.onNodeWithText("预设: 115200 ▾").assertIsDisplayed().performClick()
        compose.onNode(hasText("115200",substring=false) and !hasSetTextAction()).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("预设: 115200 ▾").performClick()
        compose.onNode(hasText("1152000",substring=false) and !hasSetTextAction()).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("预设: 1152000 ▾").assertIsDisplayed()
    }
    @Test fun notificationPermissionResultRefreshesTheVisibleStatus() {
        val notifications=shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        notifications.setNotificationsEnabled(false)
        val vm=show()
        compose.waitUntil(5000) {compose.runOnIdle {vm.settings.value.hintsSeen}}
        compose.onNodeWithText("菜单").performClick()
        compose.onNodeWithText("设置",substring=false).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("应用通知未启用"))
        compose.onNodeWithText("应用通知未启用").assertIsDisplayed()
        compose.runOnIdle {notifications.setNotificationsEnabled(true);vm.notificationPermissionFinished()}
        compose.onNodeWithText("应用通知已启用").assertIsDisplayed()
    }
    @Test fun enterSendsOnceAndClearsOnlyAfterAConfirmedWrite() {
        val writes=java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val reject=java.util.concurrent.atomic.AtomicBoolean(false)
        runBlocking {session("enter","test-device") {bytes ->
            writes+=bytes.copyOf()
            if(reject.get()) WriteResult(0,java.io.IOException("test rejection")) else WriteResult(bytes.size)
        }}
        val vm=show()
        compose.runOnIdle {vm.save(vm.settings.value.copy(enterToSend=true,clearAfterSend=true))}
        compose.waitUntil(5000) {compose.runOnIdle {vm.settings.value.enterToSend}}
        compose.onNode(hasSetTextAction()).performTextInput("SC")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5000) {writes.size == 1 && vm.input.value.isEmpty()}
        assertArrayEquals("SC\r\n".toByteArray(),writes.single())
        reject.set(true)
        compose.onNode(hasSetTextAction()).performTextInput("KEEP")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5000) {writes.size == 2}
        compose.runOnIdle {assertEquals("KEEP",vm.input.value)}
    }
    @Test fun importedCommandGroupsCanBeEditedWithoutAConnectedPort() {
        val command=VofaCommand("测试组",group=true,children=listOf(VofaCommand("SC","53 43 0A",delayMs=30000)))
        runBlocking {app.container.configuration.editRecords {it.saveMacro(MacroEntity("vofa-edit","测试组",VofaCommands.content(command)))}}
        val vm=show()
        compose.waitUntil(5000) {vm.macros.value.isNotEmpty()}
        compose.onNodeWithText("＋ 宏").performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("1. SC").performScrollTo().performClick()
        compose.onNodeWithText("发送文本（含结束符）").performScrollTo().performTextReplacement("SM6\n")
        compose.onNodeWithText("保存",substring=false).performClick()
        compose.waitUntil(5000) {
            compose.runOnIdle {vm.macros.value.singleOrNull()?.let {VofaCommands.command(it.content).children.single().hex == "53 4D 36 0A"} == true}
        }
        assertEquals(30000L,VofaCommands.command(vm.macros.value.single().content).children.single().delayMs)
    }
    @Test @Config(qualifiers="w420dp-h850dp-port")
    fun commandGroupsCanBeCreatedReorderedAndRestoredWithoutAConnectedPort() {
        val vm=show()
        compose.runOnIdle {vm.save(vm.settings.value.copy(macroRows=0))}
        compose.waitUntil(5000) {compose.runOnIdle {vm.settings.value.macroRows == 0}}
        compose.onNodeWithText("＋ 宏").performClick()
        compose.onNodeWithText("新建指令组",substring=false).performClick()
        compose.onNodeWithText("保存",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("名称",substring=false).performTextReplacement("自动测试")
        compose.onNodeWithText("保存",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("添加步骤",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("名称",substring=false).performTextReplacement("第一步")
        compose.onNodeWithText("发送文本（含结束符）").performScrollTo().performTextReplacement("A\n")
        compose.onNodeWithText("发送后延时 ms").performScrollTo().performTextReplacement("-1")
        compose.onNodeWithText("保存",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("发送后延时 ms").performScrollTo().performTextReplacement("30000")
        compose.onNodeWithText("重复次数，-1 为无限").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("保存",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("重复次数，-1 为无限").performTextReplacement("1")
        compose.onNodeWithText("返回上一级").performScrollTo().performClick()
        compose.onNodeWithText("循环方式: 单次 ▾").performScrollTo().performClick()
        compose.onNodeWithText("指定次数",substring=false).performClick()
        compose.onNodeWithText("循环次数",substring=false).performScrollTo().performTextReplacement("3")
        compose.onNodeWithText("循环方式: 指定次数 ▾").performScrollTo().performClick()
        compose.onNodeWithText("无限循环",substring=false).performClick()
        compose.onNodeWithText("循环方式: 无限循环 ▾").performScrollTo().performClick()
        compose.onNodeWithText("单次",substring=false).performClick()
        compose.onNodeWithText("添加步骤",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("名称",substring=false).performTextReplacement("第二步")
        compose.onNodeWithText("发送文本（含结束符）").performScrollTo().performTextReplacement("B\n")
        compose.onNodeWithText("返回上一级").performScrollTo().performClick()
        compose.onAllNodesWithText("↓")[0].performScrollTo().performClick()
        compose.onNodeWithText("1. 第二步").assertExists()
        compose.onNodeWithText("保存",substring=false).performClick()
        compose.waitUntil(5000) {compose.runOnIdle {vm.macros.value.size == 1 && vm.settings.value.macroRows > 0}}
        val macro=vm.macros.value.single()
        val root=VofaCommands.command(macro.content)
        assertEquals(listOf("第二步","第一步"),root.children.map {it.name})
        assertEquals(listOf("42 0A","41 0A"),root.children.map {it.hex})
        assertEquals(30000L,root.children[1].delayMs)
        assertEquals(0L,root.delayMs)
        assertEquals(1,root.repeats)
        compose.onNodeWithText("自动测试",substring=false).assertIsDisplayed().assertIsNotEnabled()
        val archived=com.lyufeng.phoneserialport.data.ConfigurationArchive.encode(
            com.lyufeng.phoneserialport.data.ImportedConfiguration(vm.settings.value,listOf(macro),emptyList()))
        val restored=com.lyufeng.phoneserialport.data.ConfigurationArchive.decode(archived,emptyList())
        assertEquals(root,VofaCommands.command(restored.macros.single().content))
        compose.onNodeWithText("＋ 宏").performClick()
        compose.onNodeWithText("编辑",substring=false).performClick()
        compose.onNodeWithText("1. 第二步").performScrollTo().assertIsDisplayed()
    }
    @Test fun copyingAMacroCreatesIndependentBytesAndInvalidHexKeepsTheDraft() {
        runBlocking {app.container.configuration.editRecords {it.saveMacro(MacroEntity("source","源宏","SC"))}}
        val vm=show()
        compose.waitUntil(5000) {vm.macros.value.size == 1}
        compose.onNodeWithText("＋ 宏").performClick()
        compose.onNodeWithText("新建指令组",substring=false).performClick()
        compose.onNodeWithText("名称",substring=false).performTextReplacement("复制测试")
        compose.onNodeWithText("从已有宏添加").performScrollTo().performClick()
        compose.onNode(hasText("源宏",substring=false) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("1. 源宏").performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithText("发送字节 HEX").performScrollTo().performTextReplacement("GG")
        compose.onNodeWithText("保存",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("发送字节 HEX").performTextReplacement("53 43 0D 0A")
        compose.onNodeWithText("保存",substring=false).performClick()
        compose.waitUntil(5000) {compose.runOnIdle {vm.macros.value.size == 2}}
        val group=vm.macros.value.single {it.name == "复制测试"}
        assertEquals("53 43 0D 0A",VofaCommands.command(group.content).children.single().hex)
        compose.runOnIdle {
            vm.saveMacro(vm.macros.value.single {it.id == "source"}.copy(content="CHANGED"))
            vm.save(vm.settings.value.copy(ending="NONE"))
        }
        compose.waitUntil(5000) {compose.runOnIdle {vm.macros.value.single {it.id == "source"}.content == "CHANGED" && vm.settings.value.ending == "NONE"}}
        assertEquals(group.content,vm.macros.value.single {it.id == group.id}.content)
    }
    @Test fun runningAGroupDisablesSendingAndSurvivesPageChanges() {
        val writes=java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        runBlocking {
            session("group","test-device") {bytes -> writes+=bytes.copyOf();WriteResult(bytes.size)}
            val root=VofaCommand("运行组",group=true,delayMs=0,children=listOf(
                VofaCommand("A","41",delayMs=30000),VofaCommand("B","42",delayMs=0)))
            app.container.configuration.editRecords {
                it.saveMacro(MacroEntity("group","运行组",VofaCommands.content(root)))
                it.saveMacro(MacroEntity("other","其他宏","OTHER"))
            }
        }
        val vm=show()
        compose.waitUntil(5000) {vm.macros.value.size == 2}
        compose.runOnIdle {vm.input.value="MANUAL"}
        compose.onNodeWithText("运行组",substring=false).performClick()
        compose.waitUntil(5000) {writes.size == 1 && sessions.single().state.value.commandGroupName != null}
        compose.onNodeWithText("正在执行：运行组").assertIsDisplayed()
        compose.onNodeWithText("发送",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("其他宏",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("菜单").performClick()
        compose.onNodeWithText("调试选项").performClick()
        listOf("重复发送","11 条 AT 指令","发送文件").forEach {
            compose.onNodeWithText(it,substring=false).performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithText("完成",substring=false).performClick()
        compose.onNodeWithText("菜单").performClick()
        compose.onNodeWithText("设置",substring=false).performClick()
        compose.runOnIdle {assertEquals("运行组",sessions.single().state.value.commandGroupName)}
        compose.onNodeWithText("返回",substring=false).performClick()
        compose.onNodeWithText("停止",substring=false).performClick()
        compose.waitUntil(5000) {sessions.single().state.value.commandGroupName == null}
        compose.onNodeWithText("发送",substring=false).assertIsEnabled().performClick()
        compose.waitUntil(5000) {writes.size == 2}
        assertArrayEquals(byteArrayOf(0x41),writes[0])
        assertArrayEquals("MANUAL\r\n".toByteArray(),writes[1])
    }
}
