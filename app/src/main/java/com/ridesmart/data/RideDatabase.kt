package com.ridesmart.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ridesmart.model.Signal

@Database(entities = [RideEntry::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        @Volatile
        private var INSTANCE: RideDatabase? = null

        fun getDatabase(context: Context): RideDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "ride_history_db"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
