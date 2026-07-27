package com.anuj.notificationfirewall.data.db

import androidx.room.TypeConverter
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.DecisionSource

class Converters {

    @TypeConverter
    fun fromIntSet(value: Set<Int>): String =
        value.joinToString(",")

    @TypeConverter
    fun toIntSet(value: String): Set<Int> =
        if (value.isEmpty()) emptySet()
        else value.split(",").map { it.trim().toInt() }.toSet()

    @TypeConverter
    fun fromBucketAction(value: BucketAction?): String? = value?.name

    @TypeConverter
    fun toBucketAction(value: String?): BucketAction? = value?.let { BucketAction.valueOf(it) }

    @TypeConverter
    fun fromDecisionSource(value: DecisionSource?): String? = value?.name

    @TypeConverter
    fun toDecisionSource(value: String?): DecisionSource? = value?.let { DecisionSource.valueOf(it) }
}
