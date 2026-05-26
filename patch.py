import sys

with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

target = 'thumbEtaFmt = json.optString("eta_fmt", "--:--")\n\n                        }                    }\n                }\n            } catch (_: Exception) {}\n        }\n    }'
target_cr = target.replace('\n', '\r\n')

if target in content or target_cr in content:
    actual_target = target if target in content else target_cr
    
    replacement = '''thumbEtaFmt = json.optString("eta_fmt", "--:--")

                        val appContext = NasApplication.instance.applicationContext
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

    content = content.replace(actual_target, replacement)
    with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'w', encoding='utf-8', newline='') as f:
        f.write(content)
    print('SUCCESS')
else:
    print('TARGET NOT FOUND')