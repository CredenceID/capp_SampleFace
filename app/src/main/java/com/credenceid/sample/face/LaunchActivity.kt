package com.credenceid.sample.face

import android.Manifest.permission.CAMERA
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import android.widget.Toast.LENGTH_SHORT
import com.credenceid.biometrics.Biometrics
import com.credenceid.biometrics.Biometrics.ResultCode.*
import com.credenceid.biometrics.BiometricsManager

/**
 * When permissions are requested you must pass a number to reference result by.
 */
private const val REQUEST_ALL_PERMISSIONS = 5791
/**
 * List of different permissions to request for.
 */
private val PERMISSIONS = arrayOf(CAMERA, WRITE_EXTERNAL_STORAGE)

class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        this.requestPermissions()
    }

    /**
     * Each time a permission is either allowed/denied this callback is invoked.
     */
    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {

        if (REQUEST_ALL_PERMISSIONS != requestCode)
            return

        /* Loop through each permission status. */
        for (i in permissions.indices) {
            /* If permission was denied show popup notifying user that certain features may not work
             * and stop checking rest of permission statuses.
             */
            if (PERMISSION_GRANTED != grantResults[i]) {

                val builder = android.app.AlertDialog.Builder(this)
                        .setMessage(getString(R.string.permission_denied))
                        .setPositiveButton(getString(R.string.ok)) { _, _ ->
                            this.requestPermissions()
                            /* Note: Androids built in method "requestPermissions()" can
                             * ONLY request one permission at a time. Every time user
                             * "Allows" a permission we then need to request for next one.
                             */
                        }
                        .setNegativeButton(getString(R.string.cancel)) { _, _ -> finish() }

                if (!this.isFinishing)
                    builder.create().show()
                return
            }
        }

        /* Reach here is all permissions have been granted. */
        this.initBiometrics()
    }

    /**
     * Requests for first permission that is not granted.
     */
    private fun requestPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (permission in PERMISSIONS) {
                /* Requests for first permission not granted. When user grants permission, inside
                 * "Allow" callback we must then request for next non-granted permission.
                 *
                 * This is essentially a recursive function that goes through an array one-by-one.
                 */
                if (PERMISSION_GRANTED != checkSelfPermission(permission)) {
                    requestPermissions(PERMISSIONS, REQUEST_ALL_PERMISSIONS)
                    return
                }
            }

            /* Reach here if all permissions have been granted. */
            this.initBiometrics()
        }
    }


    private fun initBiometrics() {

        /*  Create new biometrics object. */
        App.BioManager = BiometricsManager(this)

        /* Initialize object, meaning tell CredenceService to bind to this application. */
        App.BioManager!!.initializeBiometrics { rc: Biometrics.ResultCode,
                                                _: String,
                                                _: String ->


            when (rc) {
                OK -> {
                    Toast.makeText(this, getString(R.string.bio_init), LENGTH_SHORT).show()

                    App.DevFamily = App.BioManager!!.deviceFamily
                    App.DevType = App.BioManager!!.deviceType

                    /* Launch main activity. */
                    val intent = Intent(this, CameraActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    this.finish()
                    startActivity(intent)


                }
                INTERMEDIATE -> {
                    /* This code is never returned for this API. */
                }
                FAIL -> Toast.makeText(this, getString(R.string.bio_init_fail), LENGTH_LONG).show()
            }
        }
    }
}