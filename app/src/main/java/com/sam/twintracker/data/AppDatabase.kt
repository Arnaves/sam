package com.sam.twintracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Entry::class, Baby::class, VaccineDose::class, MedicineSchedule::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun babies(): BabyDao
    abstract fun vaccines(): VaccineDao
    abstract fun medicines(): MedicineDao

    companion object {
        /** v2: measurements/medicine dose on entries, baby aliases, medicine timers. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN value REAL")
                db.execSQL("ALTER TABLE entries ADD COLUMN unit TEXT")
                db.execSQL("ALTER TABLE babies ADD COLUMN aliases TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS medicine_schedules (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, baby INTEGER NOT NULL, name TEXT NOT NULL, " +
                        "dose TEXT, intervalMinutes INTEGER NOT NULL, nextDueAt INTEGER, endAt INTEGER, " +
                        "active INTEGER NOT NULL, oneShot INTEGER NOT NULL)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "twin_tracker.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
