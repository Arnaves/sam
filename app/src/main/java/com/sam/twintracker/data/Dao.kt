package com.sam.twintracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Insert
    suspend fun insert(entry: Entry): Long

    @Update
    suspend fun update(entry: Entry)

    @Delete
    suspend fun delete(entry: Entry)

    /**
     * Day log search. [baby] = 0 means both babies, [type] null means all types,
     * [q] is a free-text filter over type, subtype, side, note and the raw voice text.
     */
    @Query(
        """
        SELECT * FROM entries
        WHERE timestamp >= :from AND timestamp < :to
          AND (:baby = 0 OR baby = :baby)
          AND (:type IS NULL OR type = :type)
          AND (:q = '' OR type LIKE '%' || :q || '%'
                       OR IFNULL(subtype,'') LIKE '%' || :q || '%'
                       OR IFNULL(side,'') LIKE '%' || :q || '%'
                       OR IFNULL(note,'') LIKE '%' || :q || '%'
                       OR IFNULL(rawText,'') LIKE '%' || :q || '%')
        ORDER BY timestamp DESC
        """
    )
    fun search(from: Long, to: Long, baby: Int, type: String?, q: String): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE timestamp >= :from ORDER BY timestamp ASC")
    fun since(from: Long): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE baby = :baby AND type = :type ORDER BY timestamp DESC LIMIT 1")
    fun latest(baby: Int, type: EntryType): Flow<Entry?>

    @Query("SELECT * FROM entries ORDER BY timestamp ASC")
    suspend fun all(): List<Entry>
}

@Dao
interface BabyDao {
    @Query("SELECT * FROM babies ORDER BY id")
    fun observeAll(): Flow<List<Baby>>

    @Query("SELECT * FROM babies ORDER BY id")
    suspend fun all(): List<Baby>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(baby: Baby)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(baby: Baby)
}

@Dao
interface VaccineDao {
    @Query("SELECT * FROM vaccine_doses ORDER BY baby, dueAgeDays, id")
    fun observeAll(): Flow<List<VaccineDose>>

    @Query("SELECT * FROM vaccine_doses ORDER BY baby, dueAgeDays, id")
    suspend fun all(): List<VaccineDose>

    @Query("SELECT COUNT(*) FROM vaccine_doses WHERE baby = :baby")
    suspend fun countFor(baby: Int): Int

    @Query("SELECT * FROM vaccine_doses WHERE baby = :baby AND vaccineKey = :key AND givenAt IS NULL ORDER BY dueAgeDays, id LIMIT 1")
    suspend fun nextPending(baby: Int, key: String): VaccineDose?

    @Insert
    suspend fun insertAll(doses: List<VaccineDose>)

    @Insert
    suspend fun insert(dose: VaccineDose): Long

    @Update
    suspend fun update(dose: VaccineDose)

    @Delete
    suspend fun delete(dose: VaccineDose)
}
