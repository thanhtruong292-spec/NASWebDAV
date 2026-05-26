import sys

with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

target_ext = 'fun WebDavViewModel.fetchLivestreamStatusOnly(context: android.content.Context) {'

if target_ext in content:
    # Delete the block
    start_idx = content.find(target_ext)
    end_idx = content.find('fun WebDavViewModel.fetchSystemProcesses(')
    if start_idx != -1 and end_idx != -1:
        content = content[:start_idx] + content[end_idx:]
        
    func = '''
    fun fetchLivestreamStatusOnly(context: android.content.Context) {
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
                    var hasRecording = false
                    for (i in 0 until jobsArray.length()) {
                        val jobObj = jobsArray.getJSONObject(i)
                        val status = jobObj.optString("status", "")
                        val jobId = jobObj.optString("job_id", "")
                        val platform = jobObj.optString("platform", "")
                        val watchUser = jobObj.optString("watch_username", "")
                        if (status == "recording" && jobId.isNotEmpty()) {
                            hasRecording = true
                            newJobs.add(LivestreamJob(jobId, platform, watchUser))
                        }
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        hasLivestreamRecording = hasRecording
                        if (livestreamJobs.size != newJobs.size || livestreamJobs != newJobs) {
                            livestreamJobs.clear()
                            livestreamJobs.addAll(newJobs)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
'''
    content = content.replace('\n}\n\nfun WebDavViewModel.fetchSystemProcesses', func + '\nfun WebDavViewModel.fetchSystemProcesses')
    with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'w', encoding='utf-8', newline='') as f:
        f.write(content)
    print('SUCCESS')
else:
    print('NOT FOUND')