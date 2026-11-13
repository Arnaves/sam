package com.sam.twintracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Entry::class, Baby::class, VaccineDose::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun babies(): BabyDao
    abstract fun vaccines(): VaccineDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "twin_tracker.db").build()
    }
}
