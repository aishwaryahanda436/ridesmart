package com.ridesmart.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class RideHistoryRepository(context: Context) {

    private val rideDao = RideDatabase.getDatabase(context).rideDao()

    // Flow of all saved ride entries, newest first
    val historyFlow: Flow<List<RideEntry>> = rideDao.getAllRides()

    suspend fun saveRide(entry: RideEntry) {
        rideDao.insertAndTrim(entry)
    }

    suspend fun clearHistory() {
        rideDao.clearHistory()
    }

    fun getDailyPlatformSummary(startMs: Long, endMs: Long) =
        rideDao.getDailyPlatformSummary(startMs, endMs)

    fun getRidesInRange(startMs: Long, endMs: Long) =
        rideDao.getRidesInRange(startMs, endMs)

    fun getTodayEarnings(startOfDayMs: Long): Flow<Double> =
        rideDao.getTodayEarnings(startOfDayMs)

    fun getTodayRideCount(startOfDayMs: Long): Flow<Int> =
        rideDao.getTodayRideCount(startOfDayMs)
}
