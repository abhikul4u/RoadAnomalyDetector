/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : DetectionReport.kt
 *  Package : com.rads.detector.reporting
 *
 *  Defines the immutable data model for a single road-anomaly detection that
 *  the pipeline has decided is worth persisting. Each instance pairs the
 *  classifier's verdict (class, severity, confidence, size) with the GPS
 *  context (where and when it was observed) so the anomaly can later be mapped,
 *  analysed, and synced to a backend. The class is also a Room @Entity, so each
 *  field maps directly to a column in the on-device SQLite "detection_reports"
 *  table.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.reporting

// Room annotations that mark this data class as a database table (@Entity) and
// designate the auto-incrementing primary-key column (@PrimaryKey).
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One detection report, persisted in SQLite. Matches the schema described in
 * Chapter 4, Section 4.2.3 (GPS Annotation and Reporting).
 *
 * This is the canonical record produced once a detection has been confirmed
 * stable (see [PersistenceTracker]) and enriched with location data (see
 * [ReportBuilder]). Being a Room `@Entity`, every property below is mapped 1:1
 * to a column; being a Kotlin `data class` gives us value-equality and
 * `copy()` for free, which is handy when marking records as uploaded.
 *
 * The class deliberately stores primitive/string types only (no nested
 * objects) so the schema stays flat and trivially serialisable for backend
 * sync.
 */
@Entity(tableName = "detection_reports")
data class DetectionReport(
    // Surrogate primary key. Defaults to 0 so callers can construct a report
    // without an id; Room replaces 0 with the real auto-generated row id on
    // insert (autoGenerate = true).
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Anomaly category as a short code emitted by the detector:
    // MH = manhole, PH = pothole, WLPH = water-logged pothole.
    val className: String,                  // MH / PH / WLPH
    // Human-readable severity bucket derived from confidence and size.
    val severity: String,                   // LOW / MEDIUM / HIGH / CRITICAL
    // Raw model confidence in [0,1] for this detection; kept for auditing and
    // for re-thresholding analytics on the backend.
    val confidence: Float,
    // Bounding-box area expressed as a fraction of the full frame area. Acts as
    // a resolution-independent proxy for how large/close the anomaly appeared.
    val bboxAreaFraction: Float,            // bbox area / frame area
    // GPS latitude (decimal degrees) where the anomaly was observed.
    val latitude: Double,
    // GPS longitude (decimal degrees) where the anomaly was observed.
    val longitude: Double,
    // Horizontal accuracy of the GPS fix in metres; lets consumers weight or
    // discard low-quality fixes.
    val accuracyMeters: Float,
    // Direction of travel in degrees (0 = north). 0 is used as a fallback when
    // the device reports no bearing (e.g. stationary).
    val headingDegrees: Float,
    // Capture time as Unix epoch milliseconds in UTC, for stable ordering and
    // time-zone-independent storage.
    val timestampUtc: Long,                 // ms since epoch
    // Anonymous, hashed device identifier (see DeviceIdManager). Lets the
    // backend group reports per device without ever knowing the raw device id.
    val deviceIdHash: String,
    // Sync flag: false until the row has been successfully pushed to the
    // backend. Drives the "pending upload" queries in the DAO.
    val uploaded: Boolean = false,          // for future backend sync
)
