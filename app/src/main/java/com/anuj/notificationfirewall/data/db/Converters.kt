package com.anuj.notificationfirewall.data.db

import androidx.room.TypeConverter
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.OverrideSource
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource

class Converters {

    @TypeConverter
    fun fromIntSet(value: Set<Int>): String =
        value.joinToString(",")

    @TypeConverter
    fun toIntSet(value: String): Set<Int> =
        if (value.isEmpty()) emptySet()
        else value.split(",").map { it.trim().toInt() }.toSet()

    @TypeConverter
    fun fromWallBucket(value: WallBucket?): String? = value?.name

    @TypeConverter
    fun toWallBucket(value: String?): WallBucket? = value?.let { WallBucket.valueOf(it) }

    @TypeConverter
    fun fromWallDecisionSource(value: WallDecisionSource?): String? = value?.name

    @TypeConverter
    fun toWallDecisionSource(value: String?): WallDecisionSource? = value?.let { WallDecisionSource.valueOf(it) }

    @TypeConverter
    fun fromNotificationCategory(value: NotificationCategory?): String? = value?.name

    @TypeConverter
    fun toNotificationCategory(value: String?): NotificationCategory? = value?.let { NotificationCategory.valueOf(it) }

    @TypeConverter
    fun fromOverrideKind(value: OverrideKind?): String? = value?.name

    @TypeConverter
    fun toOverrideKind(value: String?): OverrideKind? = value?.let { OverrideKind.valueOf(it) }

    @TypeConverter
    fun fromOverrideSource(value: OverrideSource?): String? = value?.name

    @TypeConverter
    fun toOverrideSource(value: String?): OverrideSource? = value?.let { OverrideSource.valueOf(it) }
}
