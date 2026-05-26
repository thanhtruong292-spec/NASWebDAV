import sys

with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Append the extension function
func = '''
fun WebDavViewModel.fetchLivestreamStatusOnly(context: android.content.Context) {
    androidx.lifecycle.viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val apiBaseUrl = currentUrl.toApiBaseUrl()
            if (apiBaseUrl.isBlank()) return@launch
            val requestBuilder = okhttp3.Request.Builder().url(apiBaseUrl + "/api/livestream/status")
            val user = com.nas.naswebdav.SecurePrefsHelper.getUser(context)
            val pass = com.nas.naswebdav.SecurePrefsHelper.getPass(context)
            if (user.isNotEmpty() && pass.isNotEmpty()) {
                requestBuilder.header("Authorization", okhttp3.Credentials.basic(user, pass))
            }
            localApiClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use
                val responseStr = response.body?.string() ?: "{}"
                val json = org.json.JSONObject(responseStr)
                val jobsArray = json.optJSONArray("jobs") ?: org.json.JSONArray()
                
                val newJobs = mutableListOf<LivestreamJob>()
                for (i in 0 until jobsArray.length()) {
                    val jobObj = jobsArray.getJSONObject(i)
                    val status = jobObj.optString("status", "")
                    val jobId = jobObj.optString("job_id", "")
                    val platform = jobObj.optString("platform", "")
                    val watchUser = jobObj.optString("watch_username", "")
                    if (status == "recording" && jobId.isNotEmpty()) {
                        newJobs.add(LivestreamJob(jobId, platform, watchUser))
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (activeLivestreams.size != newJobs.size || activeLivestreams != newJobs) {
                        activeLivestreams.clear()
                        activeLivestreams.addAll(newJobs)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
'''
if 'fun WebDavViewModel.fetchLivestreamStatusOnly' not in content:
    content += '\n' + func + '\n'

# 2. Add thumb notification logic
target_thumb = 'thumbEtaFmt = json.optString("eta_fmt", "--:--")\n\n                        }                    }\n                }\n            } catch (_: Exception) {}\n        }\n    }'
target_thumb_cr = target_thumb.replace('\n', '\r\n')

thumb_func = '''thumbEtaFmt = json.optString("eta_fmt", "--:--")

                        val appContext = com.nas.naswebdav.NasApplication.instance.applicationContext
                        if (thumbRunning && thumbTotal > 0) {
                            val percent = if (thumbTotal > 0) (thumbGenerated * 100 / thumbTotal) else 0
                            showSystemNotification(appContext, 9011, "Đang tạo Thumbnail (" + thumbGenerated + " / " + thumbTotal + ")", "File hiện tại: " + thumbLastFile, percent)
                        } else {
                            cancelSystemNotification(appContext, 9011)
                        }
                        }                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun showSystemNotification(context: android.content.Context, id: Int, title: String, content: String, progress: Int? = null) {
        val channelId = "nas_background_tasks"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Tiến trình ngầm NAS", android.app.NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            context.getSystemService(android.app.NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(content).setOngoing(true).setSilent(true).setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
        if (progress != null) { builder.setProgress(100, progress, false) } else { builder.setProgress(0, 0, true) }
        try { androidx.core.app.NotificationManagerCompat.from(context).notify(id, builder.build()) } catch (_: SecurityException) {}
    }
    
    private fun cancelSystemNotification(context: android.content.Context, id: Int) {
        try { androidx.core.app.NotificationManagerCompat.from(context).cancel(id) } catch (_: SecurityException) {}
    }'''

actual_target = target_thumb if target_thumb in content else target_thumb_cr
if actual_target in content:
    content = content.replace(actual_target, thumb_func)
else:
    print('THUMB TARGET NOT FOUND')

with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('SUCCESS')