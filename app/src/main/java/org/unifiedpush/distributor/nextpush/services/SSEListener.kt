package org.unifiedpush.distributor.nextpush.services

import android.content.Context
import android.util.Base64
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSource
import android.util.Log
import okhttp3.Response
import java.lang.Exception
import com.google.gson.Gson
import org.unifiedpush.distributor.nextpush.api.SSEResponse
import org.unifiedpush.distributor.nextpush.distributor.DistributorUtils.getDb
import org.unifiedpush.distributor.nextpush.distributor.DistributorUtils.sendMessage
import org.unifiedpush.distributor.nextpush.distributor.DistributorUtils.sendUnregistered
import java.util.*

private const val TAG = "SSEListener"

class SSEListener (val context: Context) : EventSourceListener() {

    companion object {
        var lastEventDate : Calendar? = null
        var keepalive = 900
    }

    override fun onOpen(eventSource: EventSource, response: Response) {
        StartService.newEventSource(context, eventSource)
        StartService.wakeLock?.let {
            while (it.isHeld) {
                it.release()
            }
        }
        try {
            Log.d(TAG, "onOpen: " + response.code)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        Log.d(TAG, "New SSE message event: $type")
        StartService.wakeLock?.acquire(30000L /*30 secs*/)
        lastEventDate = Calendar.getInstance()

        when (type) {
            "keepalive" -> {
                val message = Gson().fromJson(data, SSEResponse::class.java)
                keepalive = message.keepalive
                Log.d(TAG, "New keepalive: $keepalive")
            }
            "message" -> {
                val message = Gson().fromJson(data, SSEResponse::class.java)
                sendMessage(
                    context,
                    message.token,
                    Base64.decode(message.message, Base64.DEFAULT)
                )
            }
            "deleteApp" -> {
                val message = Gson().fromJson(data, SSEResponse::class.java)
                val db = getDb(context.applicationContext)
                val connectorToken = db.getConnectorToken(message.token)
                if (connectorToken.isEmpty())
                    return
                sendUnregistered(context.applicationContext, connectorToken)
                db.unregisterApp(connectorToken)
            }
        }
        StartService.wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    override fun onClosed(eventSource: EventSource) {
        if (!StartService.isServiceStarted)
            return
        Log.d(TAG, "onClosed: $eventSource")
        StartService.newFail(context, eventSource)
        RestartWorker.start(context, delay = 0)
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        if (!StartService.isServiceStarted)
            return
        Log.d(TAG, "onFailure")
        t?.let {
            Log.d(TAG, "An error occurred: $t")
        }
        response?.let {
            Log.d(TAG, "onFailure: ${it.code}")
        }
        StartService.newFail(context, eventSource)
        val delay = when (StartService.nFails) {
            1 -> 2          // 2sec
            2 -> 20         // 20sec
            3 -> 60         // 1min
            4 -> 300        // 5min
            5 -> 600        // 10min
            else -> return  // else keep the worker with its 16min
        }.toLong()
        Log.d(TAG, "Retrying in $delay s")
        RestartWorker.start(context, delay = delay)
    }
}
