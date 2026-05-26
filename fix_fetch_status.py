import sys

with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace the inner loop of fetchLivestreamStatusOnly
old_inner = '''                    if (status == "recording" && jobId.isNotEmpty()) {
                        newJobs.add(WebDavViewModel.LivestreamJob(jobId, platform, watchUser))
                    }'''

new_inner = '''                    if (status == "recording" && jobId.isNotEmpty()) {
                        newJobs.add(
                            WebDavViewModel.LivestreamJob(
                                jobId = jobId,
                                platform = platform,
                                status = status,
                                watchUsername = watchUser,
                                durationSeconds = jobObj.optLong("duration_seconds", 0L),
                                startedTs = jobObj.optLong("started_ts", 0L),
                                fileSize = jobObj.optString("file_size", "0 B"),
                                duration = jobObj.optString("duration_display", "0h00m00s"),
                                speed = jobObj.optString("avg_speed", "—")
                            )
                        )
                    }'''

if old_inner in content:
    content = content.replace(old_inner, new_inner)
else:
    print('OLD INNER NOT FOUND')

with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('SUCCESS_VM')