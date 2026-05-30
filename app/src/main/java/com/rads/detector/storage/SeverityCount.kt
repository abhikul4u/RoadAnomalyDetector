/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : SeverityCount.kt
 *  Package : com.rads.detector.storage
 *
 *  Lightweight projection (NOT a Room @Entity) that holds the result of a
 *  GROUP BY query counting how many stored reports fall into each severity
 *  bucket (LOW / MEDIUM / HIGH / CRITICAL). Used by the Reports screen to
 *  render the per-severity breakdown in the summary header.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector.storage

/**
 * One row of a "reports per severity" aggregation.
 *
 * The property names deliberately match the SELECTed column names/aliases in
 * [DetectionReportDao.severityBreakdown] (`severity` and the `count` alias) so
 * Room can map the query result straight onto this data class.
 *
 * @property severity the stored severity name (LOW / MEDIUM / HIGH / CRITICAL).
 * @property count number of stored reports with that severity.
 */
data class SeverityCount(
    val severity: String,
    val count: Int,
)
