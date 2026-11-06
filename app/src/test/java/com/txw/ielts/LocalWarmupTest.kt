package com.txw.ielts

import com.txw.ielts.llm.LocalWarmup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 练习页预热判定的回归锁定。
 *
 * 真机上的失败模式是「预热开关默认打开、进入写作页停 5 秒也合规，但提交后仍要等 60 秒」：
 * 第一版条件写成了「内部目录已就绪就跳过」，而 isReady 只表示文件已拷贝、不表示已加载。
 * 于是冷启动最常见的「文件已就绪、内存里还没有」这一档被直接跳过，预热形同虚设。
 */
class LocalWarmupTest {

    @Test
    fun `内部目录已就绪时仍要预热`() {
        // 这正是修复点：文件已完整拷贝 ≠ 模型已加载进内存。
        assertTrue(LocalWarmup.shouldPreload(enabled = true, ready = true, delivered = false))
    }

    @Test
    fun `只在外部投递目录时也要预热`() {
        // 首次使用：preload 会先拷贝到内部目录再加载，用户提交时少等一次冷加载。
        assertTrue(LocalWarmup.shouldPreload(enabled = true, ready = false, delivered = true))
    }

    @Test
    fun `内部与外部都有时预热`() {
        assertTrue(LocalWarmup.shouldPreload(enabled = true, ready = true, delivered = true))
    }

    @Test
    fun `两条来源都没有时不预热`() {
        assertFalse(LocalWarmup.shouldPreload(enabled = true, ready = false, delivered = false))
    }

    @Test
    fun `开关关闭时任何来源都不预热`() {
        assertFalse(LocalWarmup.shouldPreload(enabled = false, ready = true, delivered = true))
        assertFalse(LocalWarmup.shouldPreload(enabled = false, ready = true, delivered = false))
        assertFalse(LocalWarmup.shouldPreload(enabled = false, ready = false, delivered = true))
        assertFalse(LocalWarmup.shouldPreload(enabled = false, ready = false, delivered = false))
    }

    @Test
    fun `真值表只由开关与模型来源决定`() {
        // 预热 = 开关打开 且（内部已就绪 或 外部已投递），不引入其它隐藏条件。
        for (enabled in listOf(false, true)) {
            for (ready in listOf(false, true)) {
                for (delivered in listOf(false, true)) {
                    val expected = enabled && (ready || delivered)
                    assertTrue(
                        "enabled=$enabled ready=$ready delivered=$delivered",
                        LocalWarmup.shouldPreload(enabled, ready, delivered) == expected,
                    )
                }
            }
        }
    }
}
