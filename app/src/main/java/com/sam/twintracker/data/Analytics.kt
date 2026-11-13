package com.sam.twintracker.data

data class DayStat(
    val dayStart: Long,
    val feeds: Int = 0,
    val feedMl: Double = 0.0,
    val breastMin: Int = 0,
    val wet: Int = 0,
    val dirty: Int = 0,
    val sleepMin: Int = 0,
) {
    val diapers get() = wet + dirty
}

data class BabyStats(
    val baby: Int,
    val days: List<DayStat>,
    val lastFeed: Entry?,
    val lastDiaper: Entry?,
    val lastSleep: Entry?,
    /** Mean minutes between consecutive feeds within the range. */
    val avgFeedIntervalMin: Int?,
) {
    val today get() = days.last()
    private val full get() = days.dropLast(1).ifEmpty { days } // completed days only, for averages
    val avgFeeds get() = full.map { it.feeds }.average()
    val avgMl get() = full.map { it.feedMl }.average()
    val avgWet get() = full.map { it.wet }.average()
    val avgDirty get() = full.map { it.dirty }.average()
    val avgSleepMin get() = full.map { it.sleepMin }.average()
}

object Analytics {
    /**
     * @param entries entries from [rangeStart] onwards
     * @param dayStarts start of every day in the range, oldest first, last = today
     */
    fun compute(entries: List<Entry>, dayStarts: List<Long>, baby: Int): BabyStats {
        val mine = entries.filter { it.baby == baby }.sortedBy { it.timestamp }
        val days = dayStarts.mapIndexed { i, start ->
            val end = dayStarts.getOrNull(i + 1) ?: Long.MAX_VALUE
            val d = mine.filter { it.timestamp in start until end }
            val feeds = d.filter { it.type == EntryType.FEED }
            DayStat(
                dayStart = start,
                feeds = feeds.size,
                feedMl = feeds.sumOf { it.amountMl ?: 0.0 },
                breastMin = feeds.filter { it.subtype == FeedKind.BREAST }.sumOf { it.durationMin ?: 0 },
                wet = d.count { it.isWet },
                dirty = d.count { it.isDirty },
                sleepMin = d.filter { it.type == EntryType.SLEEP }.sumOf { it.durationMin ?: 0 },
            )
        }
        val feeds = mine.filter { it.type == EntryType.FEED }
        val intervals = feeds.zipWithNext { a, b -> (b.timestamp - a.timestamp) / 60_000 }
            .filter { it in 1..(12 * 60) } // ignore gaps where logging was missed
        return BabyStats(
            baby = baby,
            days = days,
            lastFeed = feeds.lastOrNull(),
            lastDiaper = mine.lastOrNull { it.type == EntryType.OUTPUT },
            lastSleep = mine.lastOrNull { it.type == EntryType.SLEEP },
            avgFeedIntervalMin = if (intervals.isEmpty()) null else intervals.average().toInt(),
        )
    }
}
