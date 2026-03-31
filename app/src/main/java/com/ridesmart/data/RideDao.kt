package com.ridesmart.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class DailyPlatformRow(
    val platform: String,
    val rideCount: Int,
    val grossEarnings: Double,
    val operatingProfit: Double,
    val totalKm: Double,
    val fuelCost: Double,
    val wearCost: Double
)

@Dao
interface RideDao {
    @Query("SELECT * FROM ride_history ORDER BY timestampMs DESC")
    fun getAllRides(): Flow<List<RideEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: RideEntry)

    @Query("DELETE FROM ride_history")
    suspend fun clearHistory()

    @Query("DELETE FROM ride_history WHERE id NOT IN (SELECT id FROM ride_history ORDER BY timestampMs DESC LIMIT 200)")
    suspend fun trimHistory()
    
    @Transaction
    suspend fun insertAndTrim(ride: RideEntry) {
        insertRide(ride)
        trimHistory()
    }

    @Query("""
        SELECT COALESCE(SUM(netProfit), 0.0)
        FROM ride_history
        WHERE timestampMs >= :startOfDayMs
    """)
    fun getTodayEarnings(startOfDayMs: Long): Flow<Double>

    @Query("""
        SELECT COUNT(*)
        FROM ride_history
        WHERE timestampMs >= :startOfDayMs
    """)
    fun getTodayRideCount(startOfDayMs: Long): Flow<Int>

    @Query("""
        SELECT platform, COUNT(*) as rideCount,
               SUM(baseFare) as grossEarnings,
               SUM(netProfit) as operatingProfit,
               SUM(totalKm) as totalKm,
               SUM(fuelCost) as fuelCost,
               SUM(wearCost) as wearCost
        FROM ride_history
        WHERE timestampMs >= :startMs AND timestampMs < :endMs
        GROUP BY platform
    """)
    fun getDailyPlatformSummary(startMs: Long, endMs: Long):
        Flow<List<DailyPlatformRow>>

    @Query("SELECT * FROM ride_history WHERE timestampMs >= :startMs AND timestampMs < :endMs ORDER BY timestampMs DESC")
    fun getRidesInRange(startMs: Long, endMs: Long): Flow<List<RideEntry>>
}
