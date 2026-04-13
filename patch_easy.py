import sys

kt_file = r'd:\\Android\\NASWebDAV\\app\\src\\main\\java\\com\\nas\\naswebdav\\ui\\screens\\MainMenuScreen.kt'
with open(kt_file, 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Colors
text = text.replace('Color(0xFF1E1E1E)', 'Color.Black')
text = text.replace('Color(0xFF2C2C2C)', 'Color(0xFF161616)')

# 2. Top Padding
padding_target = """        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkSurface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
        Spacer(Modifier.height(8.dp))"""
padding_replacement = """        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkSurface)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
        Spacer(Modifier.height(16.dp))"""
text = text.replace(padding_target, padding_replacement)

# 3. Inject Chart & Hide Tasks (Wrapping Call site)
chart_target = """        // Disk Partitions đã được gộp vào Thẻ hệ thống ở trên.
        Spacer(Modifier.height(4.dp))
        Text("TÁC VỤ NỀN", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 16.dp))
        Spacer(Modifier.height(4.dp))
        SystemStatusCards(viewModel, mContext)"""
chart_replacement = """        // --- CHÈN BIỂU ĐỒ GIÁM SÁT VÀ BÁO CÁO Ở ĐÂY ---
        Spacer(Modifier.height(8.dp))
        com.nas.naswebdav.ui.screens.MonitoringChartCard(viewModel)
        Spacer(Modifier.height(8.dp))

        SystemStatusCards(viewModel, mContext) // TÁC VỤ NỀN Text has been moved INSIDE this function"""

text = text.replace(chart_target, chart_replacement)

with open(kt_file, 'w', encoding='utf-8') as f:
    f.write(text)

print("SUCCESS: Colors, Top Padding, Chart wrapper applied.")
