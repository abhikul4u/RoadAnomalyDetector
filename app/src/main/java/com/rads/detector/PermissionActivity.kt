/*
 * ============================================================================
 *  Project : Road Anomaly Detection System (RADS)
 *  File    : PermissionActivity.kt
 *  Package : com.rads.detector
 *
 *  The launcher Activity and gatekeeper for the runtime permissions the
 *  detection pipeline depends on (camera for frames, fine location for
 *  geotagging anomalies, and — on Android 13+ — notifications for alerts).
 *  It ensures all required permissions are granted before the live detection
 *  screen ([MainActivity]) is ever shown, guiding the user to app settings if
 *  they decline.
 *
 *  Author  : Rutuja Kulkarni
 * ============================================================================
 */
package com.rads.detector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rads.detector.databinding.ActivityPermissionBinding

/**
 * Entry-point Activity. Requests required permissions, then hands off to
 * [MainActivity]. If all permissions are already granted, transitions
 * immediately — no flicker.
 */
class PermissionActivity : AppCompatActivity() {

    // View-binding handle for activity_permission.xml.
    private lateinit var binding: ActivityPermissionBinding

    /**
     * The set of runtime permissions the app must hold before detection can run.
     * Built lazily (only when first accessed) because it depends on the device's
     * API level: POST_NOTIFICATIONS is a runtime permission only on Android 13
     * (TIRAMISU) and above, so it is conditionally added.
     */
    private val requiredPermissions: Array<String> by lazy {
        buildList {
            add(Manifest.permission.CAMERA)            // needed to capture frames
            add(Manifest.permission.ACCESS_FINE_LOCATION) // needed to geotag reports
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS) // alerts on Android 13+
            }
        }.toTypedArray()
    }

    /**
     * Registered handler for the multiple-permission request flow. The result
     * callback runs after the system permission dialog is dismissed: if every
     * permission was granted we proceed to the main screen; otherwise we switch
     * the UI to guide the user toward enabling them via app settings.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            // All requested permissions granted — continue into the app.
            launchMain()
        } else {
            // At least one was denied; repurpose the button to open settings,
            // since re-prompting may be suppressed by the system.
            binding.tvMessage.text = getString(R.string.permission_denied_message)
            binding.btnGrant.text = getString(R.string.permission_open_settings)
            binding.btnGrant.setOnClickListener { openAppSettings() }
        }
    }

    /**
     * Inflates the permission UI and either skips straight to [MainActivity]
     * (if everything is already granted) or arms the grant button to launch the
     * system permission prompt.
     *
     * @param savedInstanceState saved instance state, unused here.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fast path: if permissions were granted on a previous run, don't show
        // this screen at all — go straight to detection.
        if (hasAllPermissions()) {
            launchMain()
            return
        }

        // Otherwise, request the permissions when the user taps the grant button.
        binding.btnGrant.setOnClickListener {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    /**
     * @return true only if every entry in [requiredPermissions] is currently
     *         granted to the app.
     */
    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Transitions to the main detection screen and finishes this Activity so the
     * permission screen is removed from the back stack (the user should not be
     * able to navigate back to it).
     */
    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * Opens the system "App details" settings page for this app, allowing the
     * user to manually grant permissions they previously denied (the only path
     * once the OS stops showing the runtime prompt).
     */
    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        // Target this specific app's settings via a package: URI.
        intent.data = android.net.Uri.fromParts("package", packageName, null)
        startActivity(intent)
    }
}
