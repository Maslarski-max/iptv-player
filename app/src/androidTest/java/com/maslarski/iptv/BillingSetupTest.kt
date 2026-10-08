package com.maslarski.iptv

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens Settings -> "Activate premium" on a real Play-enabled device and asserts that
 * BillingManager reports onBillingSetupFinished OK well before its 10 s setup timeout.
 */
@RunWith(AndroidJUnit4::class)
class BillingSetupTest {
    @Test
    fun billingSetupFinishesWithoutTimeout() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instr)
        val ctx = instr.targetContext
        Runtime.getRuntime().exec("logcat -c").waitFor()

        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(launch)
        device.wait(Until.hasObject(By.pkg(ctx.packageName).depth(0)), 20_000)

        val settingsLabel = ctx.getString(R.string.nav_settings)
        val activateLabel = ctx.getString(R.string.account_activate)
        if (device.wait(Until.hasObject(By.text(settingsLabel)), 30_000) == true) {
            device.findObject(By.text(settingsLabel)).click()
        }
        assertTrue("Settings -> '$activateLabel' not visible", device.wait(Until.hasObject(By.text(activateLabel)), 20_000) == true)
        device.findObject(By.text(activateLabel)).click()
        val connectStart = System.currentTimeMillis()

        val okRegex = Regex("BillingManager.*Billing setup finished OK")
        var lines = ""
        var okAt = -1L
        val deadline = connectStart + 15_000
        while (System.currentTimeMillis() < deadline) {
            lines = dumpLogcat()
            if (okRegex.containsMatchIn(lines)) { okAt = System.currentTimeMillis() - connectStart; break }
            Thread.sleep(500)
        }
        Log.i("BillingSetupTest", "BillingManager logcat:\n$lines")
        probeBillingClient(ctx)
        instr.sendStatus(0, Bundle().apply { putString("billing_logcat", lines); putLong("setup_ok_after_ms", okAt) })
        assertTrue("onBillingSetupFinished OK not logged within 15 s. Logcat:\n$lines", okAt >= 0)
        assertTrue("setup took ${okAt}ms (>= 10 s timeout)", okAt < 10_000)
        assertTrue("timeout fired:\n$lines", !lines.contains("Billing setup timed out"))
    }

    /** Independent BillingClient on the same device, to separate Play availability from app wiring. */
    private fun probeBillingClient(ctx: android.content.Context) {
        val latch = CountDownLatch(1)
        var result: BillingResult? = null
        val start = System.currentTimeMillis()
        val probe = BillingClient.newBuilder(ctx)
            .setListener { _, _ -> }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            probe.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) { result = r; latch.countDown() }
                override fun onBillingServiceDisconnected() {}
            })
        }
        val fired = latch.await(15, TimeUnit.SECONDS)
        Log.i(
            "BillingSetupTest",
            "probe onBillingSetupFinished fired=$fired after=${System.currentTimeMillis() - start}ms " +
                "response=${result?.responseCode} debug=${result?.debugMessage}",
        )
        probe.endConnection()
    }

    private fun dumpLogcat(): String {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-s", "BillingManager:*", "BillingClient:*"))
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }
}
