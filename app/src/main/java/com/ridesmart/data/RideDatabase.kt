package com.ridesmart.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RideEntry::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        @Volatile
        private var INSTANCE: RideDatabase? = null

        /**
         * Migration from version 1 to version 2.
         * Version 1 did not have pickupPenaltyPct, riderRating, and paymentType columns.
         * These are added in version 2 to support detailed ride history analysis and 
         * better profit tracking metrics.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ride_history ADD COLUMN pickupPenaltyPct REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE ride_history ADD COLUMN riderRating REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE ride_history ADD COLUMN paymentType TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "ride_history_db"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
