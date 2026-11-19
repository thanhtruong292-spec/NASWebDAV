# Rust Modules Review

> Reviewed: `nas_hasher` + `nas_indexer` tại `backend/rust_modules/`
> Reviewer: reviewer-rust (Antigravity swarm)
> Date: 2026-11-19

---

## 🔴 Must Fix (crash / memory leak / security)

### 1. `nas_hasher/src/lib.rs:36-39` — Buffer overwrite nếu SHA256 trả về ít hơn 64 byte
```rust
for i in 0..64 {
    *out_hex.add(i) = bytes[i] as c_char;  // panic nếu bytes.len() < 64
}
```
`compute_sha256_path` trả về `hex::encode(hasher.finalize())` → luôn 64 byte → thực tế không lỗi, **nhưng** nếu logic hash thay đổi hoặc một nhánh future dùng hàm khác, write OOB không có guard. Fix: thêm `assert_eq!(bytes.len(), 64)` hoặc dùng `slice::copy_from_slice` với bounds check.

### 2. `nas_hasher/src/lib.rs:65-68` — Tương tự với MD5 (32 byte)
Cùng pattern với SHA256. Không có guard `bytes.len() >= 32` trước vòng lặp write.

### 3. `nas_indexer/src/lib.rs:88` — Slice index không được kiểm tra (panic tiềm ẩn)
```rust
let header_prefix = &meta_json[..meta_json.len() - 1];
```
`meta_json` luôn `>= 1` byte nếu JSON hợp lệ, nhưng nếu `serde_json::to_string` thất bại (đã xử lý trên), thì dòng này không bao giờ chạy. **Tuy nhiên**, nếu `meta_json` rỗng (bất kỳ edge case nào), panic tại runtime. Fix: dùng `meta_json.strip_suffix('}').unwrap_or(&meta_json)` hoặc kiểm tra độ dài.

### 4. `nas_indexer/src/search.rs:155-156` — ATTACH/DETACH không an toàn khi gọi đồng thời nhiều request
```rust
conn.execute("ATTACH DATABASE ?1 AS fts", params![fts_path])
// ... query ...
let _ = conn.execute("DETACH DATABASE fts", []);
```
`ATTACH` là **per-connection**: nếu hai Python thread gọi đồng thời với cùng connection handle (dùng `OpenFlags::SQLITE_OPEN_NO_MUTEX`), `ATTACH DATABASE fts AS fts` trên connection thứ 2 sẽ báo lỗi `"database fts is already in use"`. Tuy nhiên mỗi lời gọi `search_file_index` mở **connection mới** (`Connection::open_with_flags`), nên thực chất an toàn per-connection. ⚠️ Rủi ro: nếu future refactor dùng connection pool, bug này sẽ lộ ra. Fix: thêm comment rõ ràng.

### 5. `rust_bridge.py:37` — `_init_rust_bridge` có bug lệch indent: code thoát ra khỏi `with _RUST_INIT_LOCK` block
```python
with _RUST_INIT_LOCK:
    if _RUST_LIB is not None:
        return _RUST_AVAILABLE

    # Các đường dẫn tìm kiếm thư viện  ← indent sai! Thoát khỏi with block
candidates = [...]
```
`candidates` và vòng lặp CDLL load **không nằm trong lock** → race condition khi hai thread gọi đồng thời: cả hai đều thấy `_RUST_LIB is None`, đều gọi `CDLL()`, một trong hai overwrite `_RUST_LIB`. Đây là bug Python nhưng ảnh hưởng trực tiếp đến Rust bridge. (**Đây là lỗi trong Python reviewer scope nhưng liên quan C-ABI interface checklist E.**)

### 6. `nas_hasher/Cargo.toml` — Thiếu version pin đầy đủ cho `image` và `fast_image_resize`
```toml
image = { version = "0.25.10", ... }
fast_image_resize = "6.1.0"
```
Dùng `"0.25.10"` nghĩa là SemVer sẽ accept `0.25.10` đến `<0.26.0` — OK. Nhưng `fast_image_resize = "6.1.0"` accept `>= 6.1.0, < 7.0.0` — crate này đổi API thường xuyên theo minor. Nên pin chính xác `"=6.1.0"` hoặc dùng `Cargo.lock` (đã có).

---

## 🟡 Should Fix

### 7. `nas_hasher/src/lib.rs` — Không có path traversal validation trên C-ABI paths
Tất cả các hàm C-ABI (`nas_hasher_sha256`, `nas_hasher_md5_1mb`, `nas_hasher_generate_thumb`, `nas_hasher_find_duplicates_json`, `nas_hasher_parse_mp4_json`) nhận path từ Python và **không validate** xem path có nằm trong WebDAV root hợp lệ không. Rust tin tưởng hoàn toàn vào Python để pre-validate. Điều này đúng với thiết kế hiện tại nhưng cần document rõ ràng để tránh regression khi refactor.

### 8. `nas_indexer/src/search.rs:181` — Upper bound string thoat domain khi thư mục có tên kết thúc bằng `/`
```rust
let upper_bound = format!("{}0", root_clean);
```
`'0'` (ASCII 48) là ký tự ngay sau `'/'` (ASCII 47) — cách scoping phổ biến với SQLite range query. Nhưng nếu tên thư mục có ký tự > ASCII 48 (ví dụ ký tự Unicode), `upper_bound` có thể cắt sót một số path. **Thực tế**: vì `norm_name` đã được `remove_diacritics` (ASCII-only), rủi ro thấp. Tuy nhiên `path` không được normalize — nếu có folder tên `"photos0"`, nó sẽ bị loại nhầm. Nên dùng `format!("{}{{", root_clean)` (`{` = ASCII 123, > tất cả printable ASCII) hoặc thêm prefix length check.

### 9. `nas_indexer/src/search.rs:106` — `unwrap_or_default()` cho norm_name khi insert FTS
```rust
let norm_name: String = row.get::<_, String>(1).unwrap_or_default();
```
Nếu `norm_name` là NULL trong DB (file chưa được index đúng), FTS insert row với chuỗi rỗng, không báo lỗi. Kết quả là file không tìm được nhưng không có cảnh báo nào. Nên log warning khi `norm_name` là rỗng.

### 10. `nas_indexer/src/storage_summary.rs:97-105` — Top10 sort xảy ra **mỗi row** khi qualifies
```rust
top10.push((sz, path_str));
top10.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
if top10.len() > 10 { top10.truncate(10); }
```
Với 467K file, sort 11 phần tử mỗi row trong trường hợp xấu. `BinaryHeap` hoặc `Vec::partition_point` insertion sẽ tốt hơn. Hiện tại vẫn hoạt động và fast đủ (O(11 log 11) rất nhỏ), nhưng là anti-pattern rõ ràng.

### 11. `nas_hasher/src/duplicates.rs:149-152` — Rayon pool được tạo lại mỗi lần gọi
```rust
let pool = rayon::ThreadPoolBuilder::new().num_threads(threads).build()...
```
Mỗi lần gọi `find_duplicates_in_dir` tạo một rayon pool mới. Trên NAS với 1GB RAM, spawning threads có overhead. Nên dùng `rayon::ThreadPoolBuilder::new().num_threads(threads).build_global()` một lần hoặc tái sử dụng pool.

### 12. `nas_indexer/src/lib.rs:167-168` — `nas_indexer_stream_to_file` trả về `c_int` nhưng count có thể > i32::MAX
```rust
match stream_fast_index(db_path, writer) {
    Ok(count) => count as c_int,  // usize cast to i32 — truncation nếu > 2.1B files
```
Trên NAS với 467K file, không xảy ra. Nhưng cast cần explicit check hoặc dùng `c_longlong`.

### 13. `nas_hasher/src/image_thumb.rs:48` — Luôn decode sang RGB8 kể cả ảnh RGBA/Grayscale
```rust
let rgb = decoded.to_rgb8();
```
Ảnh PNG với alpha channel bị mất transparency khi thumbnail. Hành vi khớp với Pillow (convert RGB trước khi lưu JPEG), nhưng cần document.

### 14. `nas_indexer/src/search.rs` — FTS DETACH không được gọi khi query trả về lỗi sớm
```rust
let query_result = (|| -> Option<Vec<SearchRow>> {
    // ...
    return None;  // nhiều điểm thoát sớm
})();
let _ = conn.execute("DETACH DATABASE fts", []);
```
Nếu closure return `None` (FTS stale, ATTACH fail, etc.), `DETACH` vẫn được gọi — đây là pattern đúng. Nhưng nếu `ATTACH` thất bại, `DETACH` cũng sẽ fail (lặng lẽ bỏ qua nhờ `let _`). Không gây leak vì connection bị drop sau khi hàm return.

---

## 🟢 Nice to Have

### 15. `nas_hasher/Cargo.toml` — Thiếu `[profile.release]` optimization cho ARM64 NAS
Nên thêm:
```toml
[profile.release]
opt-level = 3
lto = "thin"
codegen-units = 1
strip = true
```
Đặc biệt `lto = "thin"` giúp giảm binary size đáng kể và cải thiện NEON autovectorization.

### 16. `nas_indexer/src/media_queries.rs` — Code trùng lặp giữa scoped và global path
`query_recent_media` và `query_photos_timeline` đều có hai nhánh if/else với cấu trúc gần như giống nhau (chỉ khác SQL query). Có thể refactor dùng closure hoặc macro, nhưng readability hiện tại vẫn tốt.

### 17. `nas_indexer/src/dir_scanner.rs:112-114` — symlink_metadata thất bại → push vào special
```rust
Err(_) => {
    special.push(name.to_string());
    continue;
}
```
Nếu `symlink_metadata` fail (ví dụ permission denied), file bị xếp vào `special` thay vì bị bỏ qua hoàn toàn. Python sẽ cố resolve nó qua `_index_resolve_directory` và thất bại. Nên log warning.

### 18. `nas_indexer/Cargo.toml` — `rusqlite` không pin patch version
```toml
rusqlite = { version = "0.31", features = ["bundled"] }
```
`"0.31"` accept `0.31.x` — bình thường. Nhưng `bundled` SQLite version có thể thay đổi theo patch. Hiện tại OK với `Cargo.lock`.

### 19. `nas_hasher/src/mp4_parser.rs:258` — Giới hạn top_boxes = 4096 rất lớn
```rust
while pos + 8 <= file_size && top_boxes < 4096 {
```
MP4 hợp lệ có < 20 top-level boxes. 4096 là con số bảo vệ chống file độc hại nhưng không tight. Có thể giảm xuống 128 để bảo vệ tốt hơn với chi phí bằng 0.

### 20. `nas_hasher/Cargo.toml` — Thiếu `rayon` và `walkdir` version minor lock
```toml
rayon = "1.12.0"       # accept >= 1.12.0, < 2.0 — OK
walkdir = "2.5.0"      # accept >= 2.5.0, < 3.0 — OK
```
Có `Cargo.lock` nên build reproducible. Chỉ cần đảm bảo `Cargo.lock` được commit vào repo.

---

## ✅ Tốt

- **Memory Safety**: Toàn bộ `unsafe` block đều có null pointer check trước khi deref. `CStr::from_ptr` chỉ được gọi sau khi kiểm tra `!ptr.is_null()`.
- **CString lifecycle**: `Box::into_raw` / `CString::into_raw` đều được `free` đúng thông qua `nas_hasher_free_string` / `nas_indexer_free_string`. Pattern nhất quán, không leak.
- **SQLite injection**: 100% query dùng parameterized `params![...]` hoặc `[]`. Không có string formatting trong SQL.
- **Seek overflow (>4GB)**: `mp4_parser.rs` dùng `u64` cho `box_start`, `parent_end`, `file_size` xuyên suốt. `SeekFrom::Start(u64)` đúng. Không có u32 downcast ngầm trong seek paths.
- **Rayon thread limit**: `workers.clamp(1, 4)` ở cả `batch_md5_1mb_parallel` và `find_duplicates_in_dir` — bảo vệ NAS 1GB RAM đúng như thiết kế.
- **No logging spam**: Các hàm Rust không ghi log ra `/var/log`. Toàn bộ error được encode vào return value (null/negative int).
- **FTS5 trigram validation**: `try_search_via_fts` kiểm tra `(src_gen, src_rev) == fts_meta` trước khi dùng — tránh stale index sau khi reindex.
- **follow_links(false)**: `duplicates.rs` không follow symlink, bảo vệ khỏi symlink loop trên NAS.
- **JSON output**: `serde_json::to_string` luôn produce valid UTF-8 JSON, Python 3.5.3 `json.loads(str)` parse được.
- **Error strings**: Các error message từ Rust không leak internal path vì hàm return `null` khi error, không return error string qua C-ABI (ngoại trừ `lib.rs:116` error format string trong `find_duplicates_in_dir` nhưng đây là Rust internal `String`, không được expose ra C-ABI).
- **C-ABI export**: `nas_hasher_free_string` và `nas_indexer_free_string` được export với `#[no_mangle]` đúng. `rust_bridge.py` gọi đúng.
- **Diacritics**: `diacritics.rs` đơn giản, thuần safe Rust, không có unsafe. Parity test với 36 ground-truth cases.
- **dir_scanner.rs**: Bỏ qua dotfiles, `@eaDir`, `#recycle` nhất quán với Python. Dùng `symlink_metadata` (lstat) thay vì `metadata` (stat) — đúng thiết kế.
- **mp4_parser.rs**: Box iteration có step counter (512/256 iterations) chống infinite loop với file MP4 độc hại. `checked_add` cho `box_end` chống u64 overflow.

---

## Tóm tắt rủi ro

| Mức | Số lượng | Vấn đề chính |
|-----|----------|--------------|
| 🔴 Must Fix | 6 | Buffer write OOB (lib.rs SHA/MD5), slice index panic, race condition rust_bridge indent, Cargo pin |
| 🟡 Should Fix | 8 | Path validation doc, scope upper_bound, FTS DETACH, cast truncation, rayon pool reuse |
| 🟢 Nice to Have | 6 | Profile optimize, code dedup, symlink log, MP4 box limit |
| ✅ Tốt | 14 điểm | Memory safety, SQL injection free, seek u64, thread limit, FTS validation |

**Ưu tiên cao nhất**: Fix #5 (rust_bridge.py race condition) và #1/#2 (buffer write OOB guard).
