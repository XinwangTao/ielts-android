package com.txw.ielts.llm

import com.txw.ielts.data.CardText
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 批注的「忽略 / 撤销」。
 *
 * 模型（尤其端侧 4B）的批注并不总能直接采纳：有的只换个同义词就算改进，
 * 有的把正确的句子改错，有的干脆答非所问。以前这些条目只能整段接受——
 * 「把这几点加入背诵」「提取生词」会把它们一并塞进 SRS，之后复习的就是错的。
 * 现在每条批注可以单独忽略，忽略后：
 * - 不再出现在「最该改的三点 / 逐句打磨」；
 * - 不进入背诵卡、逐句改错卡和生词提取的输入；
 * - 随时可以「恢复」，本轮选择按会话存盘，重新打开结果页仍然有效。
 *
 * 判重键复用 [CardText.key]（忽略大小写/空白/句末标点），
 * 顶层批注加 `f:`、逐句改法加 `s:` 前缀，两类互不干扰。
 */
object FixTriage {

    private const val FIX_PREFIX = "f:"
    private const val SENTENCE_PREFIX = "s:"

    /** 顶层批注（top_fixes/fixes）的忽略键。 */
    fun fixKey(fix: String): String = FIX_PREFIX + CardText.key(fix)

    /** 逐句改法的忽略键：以「原句」为准，同一原句多条改法一起忽略。 */
    fun sentenceKey(sentence: String): String = SENTENCE_PREFIX + CardText.key(sentence)

    fun isMuted(muted: Set<String>, key: String): Boolean = key in muted

    /** 忽略 ↔ 恢复：同一个按钮切换，撤销就是再点一次。 */
    fun toggle(muted: Set<String>, key: String): Set<String> =
        if (key in muted) muted - key else muted + key

    /** 只保留用户没有忽略的顶层批注，顺序不变。 */
    fun filterFixes(fixes: List<String>, muted: Set<String>): List<String> =
        fixes.filterNot { fixKey(it) in muted }

    /** 只保留用户没有忽略的逐句改法（按原句判定）。 */
    fun filterSentenceFixes(
        items: List<Triple<String, String, String>>,
        muted: Set<String>,
    ): List<Triple<String, String, String>> = items.filterNot { sentenceKey(it.first) in muted }

    /**
     * 会话选择存盘：{"<sessionId>":["f:...","s:..."]}。
     * 空集合不写入（连旧键一起删掉），避免设置文件越攒越大。
     */
    fun withSession(store: String?, sessionId: Long, muted: Set<String>): String {
        val root = runCatching { JSONObject(store ?: "") }.getOrNull() ?: JSONObject()
        val id = sessionId.toString()
        if (muted.isEmpty()) {
            root.remove(id)
        } else {
            root.put(id, JSONArray(muted.sorted()))
        }
        return root.toString()
    }

    /** 读取某个会话的忽略集合；存储损坏/类型不对一律当作「没忽略过」。 */
    fun sessionKeys(store: String?, sessionId: Long): Set<String> {
        val root = runCatching { JSONObject(store ?: "") }.getOrNull() ?: return emptySet()
        val arr = root.optJSONArray(sessionId.toString()) ?: return emptySet()
        return (0 until arr.length())
            .mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotBlank() } }
            .toSet()
    }
}
