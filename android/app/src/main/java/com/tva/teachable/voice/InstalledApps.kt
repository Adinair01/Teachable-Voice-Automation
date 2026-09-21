package com.tva.teachable.voice

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Launchable apps as {label, package}, so the backend can resolve a spoken app name ("open Gmail") itself. */
object InstalledApps {
    fun toJson(context: Context): JSONArray {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
        return JSONArray().apply { apps.forEach { (label, pkg) -> put(JSONObject().put("label", label).put("package", pkg)) } }
    }
}
