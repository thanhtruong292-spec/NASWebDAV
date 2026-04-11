@Composable
fun DuplicateFilesDialog(viewModel: WebDavViewModel, onDismiss: () -> Unit) {
    var selectedFilter by remember { mutableStateOf("all") }
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Column {
                Text("Tệp trùng lặp", style = MaterialTheme.typography.titleMedium, color = Color.Red)
                // BỔ SUNG: Hiển thị tổng số file rác phát hiện được nếu danh sách không trống
                if (viewModel.duplicateFilesList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Phát hiện ${viewModel.duplicateFilesList.size} tệp trùng lặp",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            if (viewModel.duplicateFilesList.isEmpty()) {
                Text("Xin chúc mừng! Không có dữ liệu trùng lặp nào.", color = Color.Green)
            } else {
                // GIAO DIỆN CHUẨN SAMSUNG GALLERY: Phân nhóm trực quan và hiển thị Thumbnail
                // SỬA LỖI: Nhóm theo Hash/Fingerprint thay vì chỉ theo Size để đảm bảo tuyệt đối file có nội dung giống nhau mới nằm chung nhóm
                val groupedDuplicates = remember(viewModel.duplicateFilesList) {
                    viewModel.duplicateFilesList.groupBy { it.contentLength }.values.filter { it.size >= 2 }.toList()
                }

                // ═══ BỘ LỌC NHANH ═══
                var selectedFilter by remember { mutableStateOf("all") } // all, image, video, doc
                val filteredGroups = remember(groupedDuplicates, selectedFilter) {
                    when (selectedFilter) {
                        "image" -> groupedDuplicates.filter { group ->
                            group.any { it.name.lowercase().run { endsWith(".jpg") || endsWith(".jpeg") || endsWith(".png") || endsWith(".webp") || endsWith(".heic") || endsWith(".gif") || endsWith(".bmp") } }
                        }
                        "video" -> groupedDuplicates.filter { group ->
                            group.any { com.nas.naswebdav.utils.MediaUtils.isVideo(it.name) }
                        }
                        "doc" -> groupedDuplicates.filter { group ->
                            group.any { f -> val n = f.name.lowercase(); !n.run { endsWith(".jpg") || endsWith(".jpeg") || endsWith(".png") || endsWith(".webp") || endsWith(".heic") || endsWith(".gif") || endsWith(".bmp") } && !com.nas.naswebdav.utils.MediaUtils.isVideo(f.name) }
                        }
                        else -> groupedDuplicates
                    }
                }

                Column(Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                    // ═══ FILTER CHIP ROW ═══
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        data class FilterOption(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
                        val filters = listOf(
                            FilterOption("all", "Tất cả (${groupedDuplicates.size})", Icons.Default.SelectAll),
                            FilterOption("image", "🖼 Ảnh", Icons.Default.Image),
                            FilterOption("video", "🎬 Video", Icons.Default.PlayCircle),
                            FilterOption("doc", "📄 Khác", Icons.Default.InsertDriveFile)
                        )
                        filters.forEach { opt ->
                            androidx.compose.material3.FilterChip(
                                selected = selectedFilter == opt.key,
                                onClick = { selectedFilter = opt.key },
                                label = { Text(opt.label, fontSize = 10.sp, maxLines = 1) },
                                leadingIcon = if (selectedFilter == opt.key) {{ Icon(Icons.Default.Done, null, Modifier.size(14.dp)) }} else null,
                                modifier = Modifier.height(30.dp)
                            )
                        }
                    }

                    // Nút Tự động chọn thông minh (Giữ lại 1 bản, tick chọn xóa các bản copy)
                    TextButton(
                        onClick = {
                            viewModel.selectedDuplicates.clear()
                            groupedDuplicates.forEach { group ->
                                // BÍ QUYẾT: File gốc thường nằm ở thư mục ngoài cùng (đường dẫn ngắn), file copy thường bị ném vào thư mục con sâu hơn.
                                // Nên ta sắp xếp độ dài path, giữ lại phần tử đầu tiên và tick chọn xóa các phần tử phía sau.
                                val filesToDelete = group.sortedBy { it.path.length }.drop(1)
                                viewModel.selectedDuplicates.addAll(filesToDelete)
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF2196F3))
                        Spacer(Modifier.width(4.dp))
                        Text("Chọn thông minh", fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                    }

                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                        items(items = filteredGroups, key = { it.first().contentLength }) { group ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Nhóm ${group.size} tệp trùng lặp (${group.first().contentLength / 1024} KB)",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )

                                    // Hiển thị danh sách file trong nhóm bằng Cuộn Ngang (LazyRow)
                                    androidx.compose.foundation.lazy.LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(items = group, key = { it.path }) { dupFile ->
                                            val isSelected = viewModel.selectedDuplicates.contains(dupFile)
                                            val isImage = dupFile.name.lowercase().run { endsWith(".jpg") || endsWith(".png") || endsWith(".jpeg") || endsWith(".webp") }
                                            val isVideo = com.nas.naswebdav.utils.MediaUtils.isVideo(dupFile.name)
                                            val auth = remember { okhttp3.Credentials.basic(viewModel.webDavManager.currentUser, viewModel.webDavManager.currentPass) }

                                            Box(
                                                modifier = Modifier
                                                    .width(130.dp).height(150.dp) // Kích thước Thumbnail to rõ ràng
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) Color.Red.copy(alpha = 0.2f) else Color.Black)
                                                    .clickable {
                                                        if (isSelected) viewModel.selectedDuplicates.remove(dupFile)
                                                        else viewModel.selectedDuplicates.add(dupFile)
                                                    }
                                            ) {
                                                // 1. Lớp Ảnh Nền (TỐI ƯU HÓA DB CACHE MỚI CHO TẤT CẢ MEDIA)
                                                if (isImage || isVideo) {
                                                    Box(modifier = Modifier.fillMaxSize()) {
                                                        WebDavCachedThumbnail(url = dupFile.path, auth = auth, isVideo = isVideo, modifier = Modifier.fillMaxSize(), viewModel = viewModel)
                                                    }
                                                } else {
                                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center).size(40.dp))
                                                }

                                                // 2. Lớp phủ đỏ mờ nếu đang được tick chọn xóa
                                                if (isSelected) {
                                                    Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.4f)))
                                                }

                                                // 3. Checkbox nằm góc trên phải
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = {
                                                        if (it) viewModel.selectedDuplicates.add(dupFile)
                                                        else viewModel.selectedDuplicates.remove(dupFile)
                                                    },
                                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                                    colors = CheckboxDefaults.colors(checkedColor = Color.Red, uncheckedColor = Color.White)
                                                )

                                                // 4. Tên file + thư mục cha đè ở dưới cùng (Để phân biệt các file)
                                                Column(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .fillMaxWidth()
                                                        .background(Color.Black.copy(alpha = 0.75f))
                                                        .padding(horizontal = 4.dp, vertical = 3.dp)
                                                ) {
                                                    Text(
                                                        text = dupFile.name,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        lineHeight = 10.sp
                                                    )
                                                    val parentFolder = dupFile.path.substringBeforeLast("/").substringAfterLast("/")
                                                    Text(
                                                        text = "📁 $parentFolder",
                                                        fontSize = 7.sp,
                                                        color = Color.Gray,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // Hiển thị nút Xóa hàng loạt màu đỏ nổi bật nếu có file đang được tick
                if (viewModel.selectedDuplicates.isNotEmpty()) {
                    TextButton(onClick = { viewModel.deleteSelectedDuplicates() }) {
                        Text("Xóa (${viewModel.selectedDuplicates.size}) mục", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = {
                    onDismiss()
                    viewModel.selectedDuplicates.clear() // Xóa danh sách tick chọn tạm thời khi đóng hộp thoại
                }) { Text("Đóng") }
            }
        }
    )
}

}
