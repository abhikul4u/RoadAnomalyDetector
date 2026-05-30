/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : ClassCount.kt
 *  Package : com.rads.detector.storage
 *
 *  Lightweight projection (NOT a Room @Entity) that holds the result of a
 *  GROUP BY query counting how many stored reports fall into each anomaly
 *  class (MH / PH / WLPH). Used by the Reports screen to render the per-class
 *  breakdown in the summary header.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.storage

/**
 * One row of a "reports per class" aggregation.
 *
 * The property names deliberately match the SELECTed column names/aliases in
 * [DetectionReportDao.classBreakdown] (`className` and the `count` alias) so
 * Room can map the query result straight onto this data class.
 *
 * @property className the anomaly class code (e.g. MH, PH, WLPH).
 * @property count number of stored reports with that class.
 */
data class ClassCount(
    val className: String,
    val count: Int,
)
