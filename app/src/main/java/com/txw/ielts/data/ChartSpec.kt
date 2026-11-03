package com.txw.ielts.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * AI 出图题的数据规格：同一对象同时驱动「Canvas 图表」与「题干/数据清单」，
 * 保证图表数字与题干数字完全一致（图见 ielts-ai-question_workflow）。
 */
data class ChartSeries(val name: String, val values: List<Double>)

data class ChartSpec(
    val kind: String,              // line / bar / pie / table
    val title: String,             // 图表英文标题
    val unit: String,              // 单位：%, people, USD...
    val categories: List<String>,  // x 轴类别 / 饼图扇区
    val series: List<ChartSeries>,
    val years: List<Int> = emptyList(),  // 折线的年份
    /** 本地 LLM 润色后的题干（只改措辞，不改数据）；为空时用模板。 */
    val promptOverride: String? = null,
) {
    /** 题干：英语 Task 1 提示语。 */
    fun prompt(): String = promptOverride?.takeIf { it.isNotBlank() } ?: when (kind) {
        "line" -> "The line graph below shows $title between ${years.firstOrNull() ?: ""} and ${years.lastOrNull() ?: ""}. Summarise the information by selecting and reporting the main features, and make comparisons where relevant."
        "bar" -> "The bar chart below shows $title. Summarise the information by selecting and reporting the main features, and make comparisons where relevant."
        "pie" -> "The pie chart below shows $title. Summarise the information by selecting and reporting the main features, and make comparisons where relevant."
        "map" -> "The maps below show $title. Summarise the information by selecting and reporting the main features, and make comparisons where relevant."
        "process" -> "The diagram below shows $title. Summarise the information by selecting and reporting the main features."
        else -> "The table below shows $title. Summarise the information by selecting and reporting the main features, and make comparisons where relevant."
    }

    /** 数据清单：作为评分 topic 的一部分，模型据此核对数据。 */
    fun dataText(): String = buildString {
        append("Chart data (").append(kind).append("): ").append(title)
        if (unit.isNotBlank()) append(" [unit: ").append(unit).append("]")
        append("\n")
        when (kind) {
            "line" -> {
                append("Years: ").append(years.joinToString(", ")).append("\n")
                series.forEach { s ->
                    append("- ").append(s.name).append(": ")
                    s.values.forEachIndexed { i, v -> if (i > 0) append(", "); append(v) }
                    append("\n")
                }
            }
            "table" -> {
                append("Rows: ").append(categories.joinToString(", ")).append("\n")
                series.forEach { s ->
                    append("- ").append(s.name).append(": ")
                    s.values.forEachIndexed { i, v -> if (i > 0) append(", "); append(v) }
                    append("\n")
                }
            }
            "map" -> {
                append("Before (${series.getOrNull(0)?.name ?: "before"}):\n")
                categories.forEachIndexed { i, c ->
                    val v = series.getOrNull(0)?.values?.getOrNull(i) ?: 0.0
                    append("- $c: ").append(if (v > 0) "present" else "absent / open land").append("\n")
                }
                append("After (${series.getOrNull(1)?.name ?: "after"}):\n")
                categories.forEachIndexed { i, c ->
                    val v = series.getOrNull(1)?.values?.getOrNull(i) ?: 0.0
                    append("- $c: ").append(
                        when {
                            v >= 2 -> "newly built"
                            v > 0 -> "unchanged"
                            else -> "removed / open land"
                        }
                    ).append("\n")
                }
            }
            "process" -> {
                append("Steps:\n")
                categories.forEachIndexed { i, c -> append("${i + 1}. $c\n") }
            }
            else -> {
                categories.forEachIndexed { i, c ->
                    append("- ").append(c).append(": ")
                    series.forEachIndexed { si, s -> if (si > 0) append(" / "); append(s.values.getOrElse(i) { 0.0 }) }
                    append("\n")
                }
            }
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("kind", kind); put("title", title); put("unit", unit)
        put("categories", JSONArray(categories))
        put("years", JSONArray(years))
        put("series", JSONArray().apply {
            series.forEach { s ->
                put(JSONObject().put("name", s.name).put("values", JSONArray(s.values)))
            }
        })
        promptOverride?.let { put("promptOverride", it) }
    }.toString()

    companion object {
        fun fromJson(raw: String): ChartSpec? = runCatching {
            val o = JSONObject(raw)
            ChartSpec(
                kind = o.getString("kind"),
                title = o.getString("title"),
                unit = o.optString("unit", ""),
                categories = o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.get(it).toString() } } ?: emptyList(),
                years = o.optJSONArray("years")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                series = o.optJSONArray("series")?.let { a ->
                    (0 until a.length()).map { i ->
                        val s = a.getJSONObject(i)
                        ChartSeries(
                            s.optString("name", "Series ${i + 1}"),
                            s.optJSONArray("values")?.let { v -> (0 until v.length()).map { v.getDouble(it) } } ?: emptyList(),
                        )
                    }
                } ?: emptyList(),
                promptOverride = o.optString("promptOverride").takeIf { it.isNotBlank() },
            )
        }.getOrNull()

        /** 随机生成一道 Task 1 数据题；同一 spec 驱动图表与题干。 */
        fun random(kind: String = listOf("line", "bar", "pie", "table", "map", "process").random(), rnd: Random = Random.Default): ChartSpec {
            val topics = TOPICS
            val topic = topics.random(rnd)
            return when (kind) {
                "line" -> randomLine(topic, rnd)
                "bar" -> randomBar(topic, rnd)
                "pie" -> randomPie(topic, rnd)
                "map" -> randomMap(rnd)
                "process" -> randomProcess(rnd)
                else -> randomTable(topic, rnd)
            }
        }

        private data class Topic(
            val title: String, val unit: String,
            val categories: List<String>, val seriesNames: List<String>,
            // 折线图的系列名（x 轴是年份，图例应是国家/群体，不能挪用 seriesNames 里的年份）
            val lineSeries: List<String> = listOf("Urban", "Rural"),
        )

        private val TOPICS = listOf(
            Topic("average household spending by category", "USD per month",
                listOf("Food", "Housing", "Transport", "Leisure", "Other"), listOf("1995", "2015"),
                listOf("Urban", "Rural")),
            Topic("energy consumption by source", "% of total",
                listOf("Coal", "Gas", "Nuclear", "Hydro", "Solar"), listOf("2000", "2020"),
                listOf("Country A", "Country B")),
            Topic("visitors to three museums", "thousands",
                listOf("National", "Science", "Art", "History"), listOf("2010", "2015", "2020"),
                listOf("Museum A", "Museum B")),
            Topic("employment by sector", "% of workforce",
                listOf("Agriculture", "Industry", "Services", "Construction"), listOf("2005", "2015", "2025"),
                listOf("Men", "Women")),
            Topic("online and in-store shopping", "% of sales",
                listOf("Online", "In-store", "Hybrid", "Other"), listOf("2018", "2021", "2024"),
                listOf("Young", "Older")),
            Topic("household water use", "litres per person",
                listOf("Bathing", "Kitchen", "Toilet", "Garden", "Laundry"), listOf("City A", "City B"),
                listOf("City A", "City B")),
            Topic("time spent on daily activities", "minutes per day",
                listOf("Work", "Commute", "Chores", "Leisure", "Sleep"), listOf("Men", "Women"),
                listOf("Men", "Women")),
            Topic("exports of three countries", "billion USD",
                listOf("Country A", "Country B", "Country C"), listOf("2010", "2015", "2020"),
                listOf("Country A", "Country B")),
        )

        private fun randomLine(t: Topic, rnd: Random): ChartSpec {
            val start = 2000 + rnd.nextInt(20)
            val years = (0..4).map { start + it * 4 }
            val series = t.lineSeries.take(2).mapIndexed { si, name ->
                var v = 20.0 + rnd.nextInt(40)
                ChartSeries(name, years.map {
                    v += -8 + rnd.nextInt(17) + if (si == 0) 1.5 else -1.0
                    v = v.coerceIn(5.0, 95.0)
                    Math.round(v * 10) / 10.0
                })
            }
            return ChartSpec("line", t.title, t.unit, t.categories, series, years)
        }

        private fun randomBar(t: Topic, rnd: Random): ChartSpec {
            val cats = t.categories.shuffled(rnd).take(4 + rnd.nextInt(1))
            val series = t.seriesNames.take(2).mapIndexed { si, name ->
                ChartSeries(name, cats.map { (15 + rnd.nextInt(70) + si * 5).toDouble() })
            }
            return ChartSpec("bar", t.title, t.unit, cats, series)
        }

        private fun randomPie(t: Topic, rnd: Random): ChartSpec {
            val cats = t.categories.shuffled(rnd).take(4)
            val raw = cats.map { 10 + rnd.nextInt(50) }
            val sum = raw.sum().toDouble()
            val pct = raw.map { Math.round(it / sum * 100.0).toInt() }
            // 把舍入误差补到最大扇区，保证总和 100
            val diff = 100 - pct.sum()
            val fixed = pct.toMutableList().also { it[it.indexOf(it.max())] += diff }
            return ChartSpec("pie", t.title, "%", cats, listOf(ChartSeries("Share", fixed.map { v -> v.toDouble() })))
        }

        private fun randomTable(t: Topic, rnd: Random): ChartSpec {
            val cats = t.categories.shuffled(rnd).take(5)
            val series = t.seriesNames.take(2).map { name ->
                ChartSeries(name, cats.map { (10 + rnd.nextInt(80)).toDouble() })
            }
            return ChartSpec("table", t.title, t.unit, cats, series)
        }

        private val MAP_PLACES = listOf(
            "School", "Park", "Station", "Shops", "Houses", "Factory", "River", "Car park",
        )

        /** 地图题：同一地点在 2000 与 today 的「有/无/新建」，values 用状态码 0/1/2。 */
        private fun randomMap(rnd: Random): ChartSpec {
            val places = MAP_PLACES.shuffled(rnd).take(6)
            val before = places.map { if (rnd.nextInt(3) == 0) 0.0 else 1.0 }
            // 2 只用于「原本没有、今天新建」；原本存在的只能是「不变」或「拆除」，避免语义矛盾。
            val after = places.mapIndexed { i, _ ->
                when {
                    before[i] == 0.0 && rnd.nextInt(2) == 0 -> 2.0
                    before[i] > 0.0 && rnd.nextInt(3) == 0 -> 0.0
                    else -> before[i]
                }
            }
            return ChartSpec(
                "map", "a town centre in 2000 and today", "",
                places,
                listOf(ChartSeries("2000", before), ChartSeries("today", after)),
            )
        }

        private data class ProcessTopic(val title: String, val steps: List<String>)

        private val PROCESS_TOPICS = listOf(
            ProcessTopic("how chocolate is made", listOf("Beans harvested", "Dried in the sun", "Roasted", "Ground into paste", "Packaged")),
            ProcessTopic("how a clay pot is made", listOf("Clay dug", "Mixed with water", "Shaped by hand", "Fired in a kiln", "Painted")),
            ProcessTopic("how cheese is produced", listOf("Milk collected", "Pasteurised", "Culture added", "Pressed into blocks", "Aged")),
            ProcessTopic("how timber is processed", listOf("Trees cut", "Dried", "Cut into planks", "Sanded and treated", "Shipped")),
        )

        private fun randomProcess(rnd: Random): ChartSpec {
            val t = PROCESS_TOPICS.random(rnd)
            return ChartSpec(
                "process", t.title, "",
                t.steps,
                listOf(ChartSeries("Step", (1..t.steps.size).map { it.toDouble() })),
            )
        }
    }
}
