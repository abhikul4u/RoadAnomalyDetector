/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : DetectionReportDao.kt
 *  Package : com.rads.detector.storage
 *
 *  Room Data Access Object (DAO) that defines every database operation the app
 *  performs on the "detection_reports" table. It is the single, type-safe
 *  gateway through which the pipeline inserts new reports, the UI observes live
 *  counts, the upload worker drains pending records, and maintenance code clears
 *  history. Room generates the SQL-executing implementation from these
 *  annotated declarations.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.storage

// Room DAO annotations and the reactive Flow type used for live queries.
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.rads.detector.reporting.DetectionReport
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [DetectionReport] rows.
 *
 * All blocking operations are `suspend` functions so callers run them off the
 * main thread via coroutines; the one `Flow`-returning query is reactive and
 * re-emits whenever the underlying table changes. Marked `@Dao` so Room
 * generates the concrete implementation.
 */
@Dao
interface DetectionReportDao {

    /**
     * Inserts a single report.
     *
     * @param report the fully built report to persist.
     * @return the auto-generated row id assigned by SQLite.
     */
    @Insert
    suspend fun insert(report: DetectionReport): Long

    /**
     * One-shot total count of stored reports.
     *
     * @return the number of rows currently in the table.
     */
    @Query("SELECT COUNT(*) FROM detection_reports")
    suspend fun count(): Int

    /**
     * Observable total count that re-emits on every table change — ideal for
     * driving a live counter in the UI without manual refreshes.
     *
     * @return a [Flow] of the current row count.
     */
    @Query("SELECT COUNT(*) FROM detection_reports")
    fun countFlow(): Flow<Int>

    /**
     * Count of reports not yet synced to the backend (uploaded flag = 0/false).
     *
     * @return the number of pending-upload rows.
     */
    @Query("SELECT COUNT(*) FROM detection_reports WHERE uploaded = 0")
    suspend fun countPendingUpload(): Int

    /**
     * Fetches a batch of un-uploaded reports oldest-first, so the upload worker
     * drains the backlog in chronological order.
     *
     * @param limit maximum number of rows to return in one batch (default 100).
     * @return the next pending reports to upload.
     */
    @Query("SELECT * FROM detection_reports WHERE uploaded = 0 ORDER BY timestampUtc ASC LIMIT :limit")
    suspend fun getPendingForUpload(limit: Int = 100): List<DetectionReport>

    /**
     * Marks the given rows as uploaded after a successful backend sync, so they
     * are excluded from future pending-upload queries.
     *
     * @param ids primary keys of the rows that were successfully uploaded.
     */
    @Query("UPDATE detection_reports SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    /**
     * Returns the most recent reports newest-first, e.g. for a history screen.
     *
     * @param limit maximum number of rows to return (default 1000).
     * @return recent reports ordered by descending capture time.
     */
    @Query("SELECT * FROM detection_reports ORDER BY timestampUtc DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 1000): List<DetectionReport>

    /** Deletes every report — used to reset/clear all stored history. */
    @Query("DELETE FROM detection_reports")
    suspend fun clearAll()

    /**
     * Fetches a single report by its primary key, e.g. to populate a detail
     * screen. Returns null if no row with that id exists (e.g. it was deleted).
     *
     * @param id primary key of the report to load.
     * @return the matching report, or null if not found.
     */
    @Query("SELECT * FROM detection_reports WHERE id = :id")
    suspend fun getById(id: Long): DetectionReport?

    /**
     * Deletes a single report by its primary key — used by the detail screen's
     * delete action. A no-op if the id does not exist.
     *
     * @param id primary key of the report to delete.
     */
    @Query("DELETE FROM detection_reports WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Aggregates report counts grouped by anomaly class, for the Reports
     * summary header.
     *
     * @return one [ClassCount] per distinct className present in the table.
     */
    @Query("SELECT className, COUNT(*) as count FROM detection_reports GROUP BY className")
    suspend fun classBreakdown(): List<ClassCount>

    /**
     * Aggregates report counts grouped by severity bucket, for the Reports
     * summary header.
     *
     * @return one [SeverityCount] per distinct severity present in the table.
     */
    @Query("SELECT severity, COUNT(*) as count FROM detection_reports GROUP BY severity")
    suspend fun severityBreakdown(): List<SeverityCount>
}
