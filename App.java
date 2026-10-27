import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.Collator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class App {
    private static final String DB_URL = "jdbc:sqlite:todos.db";
    private static final List<String> CATEGORIES = List.of("買い物", "家事", "手続き", "仕事", "勉強", "趣味", "予定", "その他");
    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");

    public static void main(String[] args) throws Exception {
        createTable();
        int port = Integer.getInteger("port", 8081);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            try { handle(exchange); }
            catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500, "text/plain; charset=UTF-8", "データベースエラーが発生しました");
            }
        });
        server.start();
        System.out.println("サーバー起動: http://localhost:" + port + " （停止は Ctrl+C）");
    }

    private static void handle(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/add") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String title = formValue(body, "todo");
            String dueInput = formValue(body, "dueAt");
            String dueAt = normalizeDueAt(dueInput);
            String category = normalizeCategory(formValue(body, "category"));
            if (!title.isBlank() && (dueInput.isBlank() || dueAt != null) && category != null) insertTodo(title, dueAt, category);
            redirect(exchange); return;
        }
        if (path.equals("/done") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) update("UPDATE todos SET done = CASE WHEN done = 1 THEN 0 ELSE 1 END WHERE id = ?", id);
            redirect(exchange); return;
        }
        if (path.equals("/delete") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) update("DELETE FROM todos WHERE id = ?", id);
            redirect(exchange); return;
        }
        if (path.equals("/") && method.equals("GET")) { showList(exchange); return; }
        send(exchange, 404, "text/plain; charset=UTF-8", "ページが見つかりません");
    }

    private static void showList(HttpExchange exchange) throws IOException, SQLException {
        List<Todo> todos = selectTodos();
        String filter = normalizeFilter(queryValue(exchange, "filter"));
        String sort = normalizeSort(queryValue(exchange, "sort"));
        List<Todo> displayed = new ArrayList<>();
        for (Todo todo : todos) if (filter.equals("all") || filter.equals("done") == todo.done) displayed.add(todo);
        sortTodos(displayed, sort);
        long completed = todos.stream().filter(todo -> todo.done).count();
        int progress = todos.isEmpty() ? 0 : (int) Math.round(completed * 100.0 / todos.size());

        StringBuilder html = new StringBuilder("""
            <!doctype html><html lang='ja'><head><meta charset='UTF-8'>
            <meta name='viewport' content='width=device-width,initial-scale=1'><title>TODO LIST</title><style>
            :root{--ink:#153f50;--line:#55727c;--paper:#e9dfd8;--muted:#6d7779;--accent:#b75f4a}
            *{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font-family:"Yu Gothic","Hiragino Kaku Gothic ProN",sans-serif;min-height:100vh}
            .page{width:min(1080px,calc(100% - 32px));margin:42px auto 64px}.masthead{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:12px}
            h1{margin:0;font-family:"Arial Rounded MT Bold","Trebuchet MS",sans-serif;font-size:clamp(42px,7vw,76px);line-height:.95;letter-spacing:.1em}.flower{font-size:.68em;letter-spacing:0}
            .summary{text-align:right;font-size:13px;letter-spacing:.08em}.summary strong{font-size:26px}.progress{width:180px;height:5px;background:#cdbfb6;margin-top:8px}.progress span{display:block;height:100%;background:var(--ink)}
            .panel{border:1px solid var(--line);background:#ffffff80;box-shadow:7px 7px 0 #153f5014}.add-form{display:grid;grid-template-columns:minmax(220px,1fr) 150px 220px auto;border-bottom:1px solid var(--line)}
            .field{padding:12px 14px;border-right:1px solid var(--line)}label{display:block;font-size:11px;font-weight:700;letter-spacing:.12em;margin-bottom:6px}input,select,button{font:inherit;color:var(--ink)}
            input,select{width:100%;height:38px;border:0;border-bottom:1px solid #9aadb2;background:transparent;outline:none}input:focus,select:focus{border-color:var(--ink)}
            .add-button{border:0;background:var(--ink);color:white;padding:0 24px;font-weight:700;letter-spacing:.12em;cursor:pointer}.add-button:hover{background:#24576a}
            .toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:12px 14px;border-bottom:1px solid var(--line);background:#f7f3ef}.filter,.sort{display:flex;align-items:center;gap:7px;margin:0}.filter span,.sort span{font-size:12px;font-weight:700}
            .filter button{border:1px solid var(--ink);background:transparent;padding:6px 13px;cursor:pointer}.filter button.active,.filter button:hover{background:var(--ink);color:#fff}.sort select{width:180px;height:32px}
            table{width:100%;border-collapse:collapse;table-layout:fixed}.status-col{width:74px}.due-col{width:210px}.category-col{width:130px}.action-col{width:130px}
            th{height:44px;padding:8px;background:var(--ink);color:white;border-right:1px solid #b4c4c9;font-size:12px;letter-spacing:.15em}th:last-child{border-right:0}
            td{height:64px;padding:10px 14px;border-right:1px solid var(--line);border-bottom:1px solid var(--line);background:#ffffffc7;vertical-align:middle}td:last-child{border-right:0}.todo-row:last-child td{border-bottom:0}
            .check{display:inline-grid;place-items:center;width:25px;height:25px;border:2px solid var(--ink);color:transparent;text-decoration:none}.check:hover{background:#d7e1e2}.done .check{background:var(--ink);color:#fff}.title{font-weight:700;overflow-wrap:anywhere}.done .title{text-decoration:line-through;color:#7d898b}
            time{font-size:13px;color:#4f6268}.category{display:inline-block;border:1px solid #84989e;border-radius:20px;padding:4px 10px;font-size:12px;white-space:nowrap}.actions{display:flex;gap:10px;justify-content:center}.actions a{font-size:13px}.delete{color:var(--accent);text-decoration:none}.empty{padding:60px 20px;text-align:center;background:#ffffffc7;color:var(--muted)}
            @media(max-width:760px){.page{margin-top:24px}.masthead{align-items:flex-start;flex-direction:column}.summary{text-align:left}.add-form{grid-template-columns:1fr 1fr}.field:first-child{grid-column:1/-1;border-bottom:1px solid var(--line)}.field:nth-child(3){border-right:0}.add-button{grid-column:1/-1;height:48px}.toolbar{align-items:stretch;flex-direction:column}.table-wrap{overflow-x:auto}table{min-width:720px}}
            </style></head><body><main class='page'><header class='masthead'><h1>TODO LIST<span class='flower'>✿</span></h1>
            """);
        html.append("<div class='summary'><strong>").append(completed).append(" / ").append(todos.size())
            .append("</strong><br>完了したタスク<div class='progress'><span style='width:").append(progress).append("%'></span></div></div></header>")
            .append("<section class='panel'><form class='add-form' method='post' action='/add'>")
            .append("<div class='field'><label for='todo'>内容</label><input id='todo' name='todo' placeholder='新しいタスクを入力' maxlength='200' required></div>")
            .append("<div class='field'><label for='category'>カテゴリ</label><select id='category' name='category' required>").append(categoryOptions()).append("</select></div>")
            .append("<div class='field'><label for='dueAt'>締切</label><input id='dueAt' type='datetime-local' name='dueAt'></div><button class='add-button'>追加 ＋</button></form>")
            .append("<div class='toolbar'><form class='filter' method='get'><span>表示</span><input type='hidden' name='sort' value='").append(sort).append("'>")
            .append(filterButton("all", "すべて", filter)).append(filterButton("done", "完了", filter)).append(filterButton("undone", "未完了", filter)).append("</form>")
            .append("<form class='sort' method='get'><span>並び替え</span><input type='hidden' name='filter' value='").append(filter).append("'><select name='sort' onchange='this.form.submit()'>")
            .append(sortOption("newest", "追加順（新しい順）", sort)).append(sortOption("oldest", "追加順（古い順）", sort)).append(sortOption("name-asc", "名前順（昇順）", sort))
            .append(sortOption("name-desc", "名前順（降順）", sort)).append(sortOption("category", "カテゴリ順", sort)).append("</select><noscript><button>適用</button></noscript></form></div>");
        if (displayed.isEmpty()) html.append("<div class='empty'>該当するタスクはありません</div>");
        else {
            html.append("<div class='table-wrap'><table><thead><tr><th class='status-col'>進捗</th><th class='due-col'>締切</th><th>内容</th><th class='category-col'>カテゴリ</th><th class='action-col'>操作</th></tr></thead><tbody>");
            for (Todo todo : displayed) {
                html.append("<tr class='todo-row").append(todo.done ? " done" : "").append("'><td>");
                html.append("<a class='check' aria-label='").append(todo.done ? "未完了に戻す" : "完了にする")
                    .append("' href='/done?id=").append(todo.id).append("'>✓</a>");
                html.append("</td><td><time datetime='").append(escapeHtml(todo.dueAt == null ? "" : todo.dueAt)).append("'>").append(escapeHtml(formatDueAt(todo.dueAt)))
                    .append("</time></td><td class='title'>").append(escapeHtml(todo.title)).append("</td><td><span class='category'>").append(escapeHtml(todo.category))
                    .append("</span></td><td><div class='actions'>");
                if (!todo.done) html.append("<a href='/done?id=").append(todo.id).append("'>完了</a>");
                html.append("<a class='delete' href='/delete?id=").append(todo.id).append("' onclick=\"return confirm('削除しますか？')\">削除</a></div></td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        html.append("</section></main></body></html>");
        send(exchange, 200, "text/html; charset=UTF-8", html.toString());
    }

    private static void createTable() throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY, title TEXT, done INTEGER, due_at TEXT)");
            if (!hasColumn(c, "due_at")) s.execute("ALTER TABLE todos ADD COLUMN due_at TEXT");
            if (!hasColumn(c, "category")) s.execute("ALTER TABLE todos ADD COLUMN category TEXT DEFAULT 'その他'");
        }
    }
    private static boolean hasColumn(Connection c, String column) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA table_info(todos)")) {
            while (r.next()) if (column.equals(r.getString("name"))) return true;
        } return false;
    }
    private static void insertTodo(String title, String dueAt, String category) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL); PreparedStatement s = c.prepareStatement("INSERT INTO todos (title, done, due_at, category) VALUES (?, 0, ?, ?)")) {
            s.setString(1, title); s.setString(2, dueAt); s.setString(3, category); s.executeUpdate();
        }
    }
    private static void update(String sql, int id) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL); PreparedStatement s = c.prepareStatement(sql)) { s.setInt(1, id); s.executeUpdate(); }
    }
    private static List<Todo> selectTodos() throws SQLException {
        List<Todo> todos = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB_URL); PreparedStatement s = c.prepareStatement("SELECT id,title,done,due_at,category FROM todos ORDER BY id"); ResultSet r = s.executeQuery()) {
            while (r.next()) todos.add(new Todo(r.getInt("id"), r.getString("title"), r.getInt("done") != 0, r.getString("due_at"), categoryOrOther(r.getString("category"))));
        } return todos;
    }
    private static void sortTodos(List<Todo> todos, String sort) {
        Comparator<Todo> comparator;
        if (sort.equals("oldest")) comparator = Comparator.comparingInt(t -> t.id);
        else if (sort.equals("name-asc") || sort.equals("name-desc")) {
            Collator collator = Collator.getInstance(Locale.JAPANESE);
            comparator = (a, b) -> collator.compare(a.title, b.title);
            if (sort.equals("name-desc")) comparator = comparator.reversed();
            comparator = comparator.thenComparingInt(t -> t.id);
        } else if (sort.equals("category")) comparator = Comparator.comparingInt((Todo t) -> CATEGORIES.indexOf(t.category)).thenComparingInt(t -> t.id);
        else comparator = Comparator.comparingInt((Todo t) -> t.id).reversed();
        todos.sort(comparator);
    }
    private static String normalizeFilter(String v) { return "done".equals(v) || "undone".equals(v) ? v : "all"; }
    private static String normalizeSort(String v) { return "oldest".equals(v) || "name-asc".equals(v) || "name-desc".equals(v) || "category".equals(v) ? v : "newest"; }
    private static String normalizeDueAt(String v) {
        if (v == null || v.isBlank()) return null;
        try { return LocalDateTime.parse(v).toString(); } catch (DateTimeParseException ignored) { return null; }
    }
    private static String normalizeCategory(String v) { return CATEGORIES.contains(v) ? v : null; }
    private static String categoryOrOther(String v) { String category = normalizeCategory(v); return category == null ? "その他" : category; }
    private static String categoryOptions() {
        StringBuilder out = new StringBuilder();
        for (String category : CATEGORIES) out.append("<option value='").append(escapeHtml(category)).append("'>").append(escapeHtml(category)).append("</option>");
        return out.toString();
    }
    private static String formatDueAt(String v) {
        if (v == null || v.isBlank()) return "締切なし";
        try { return LocalDateTime.parse(v).format(DUE_FORMAT); } catch (DateTimeParseException ignored) { return v; }
    }
    private static String filterButton(String value, String label, String current) { return "<button name='filter' value='" + value + "'" + (value.equals(current) ? " class='active'" : "") + ">" + label + "</button>"; }
    private static String sortOption(String value, String label, String current) { return "<option value='" + value + "'" + (value.equals(current) ? " selected" : "") + ">" + label + "</option>"; }
    private static String formValue(String body, String name) {
        for (String part : body.split("&")) { String[] pair = part.split("=", 2); if (decode(pair[0]).equals(name)) return pair.length == 2 ? decode(pair[1]) : ""; }
        return "";
    }
    private static Integer queryId(HttpExchange e) { try { String v = queryValue(e, "id"); return v == null ? null : Integer.valueOf(v); } catch (NumberFormatException ignored) { return null; } }
    private static String queryValue(HttpExchange e, String name) {
        String query = e.getRequestURI().getRawQuery();
        if (query != null) for (String part : query.split("&")) { String[] pair = part.split("=", 2); if (pair.length == 2 && decode(pair[0]).equals(name)) return decode(pair[1]); }
        return name.equals("filter") ? "all" : null;
    }
    private static String decode(String v) { return URLDecoder.decode(v, StandardCharsets.UTF_8); }
    private static String escapeHtml(String v) { return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static void redirect(HttpExchange e) throws IOException { e.getResponseHeaders().set("Location", "/"); e.sendResponseHeaders(303, -1); e.close(); }
    private static void send(HttpExchange e, int status, String type, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", type); e.sendResponseHeaders(status, body.length); e.getResponseBody().write(body); e.getResponseBody().close();
    }
    private static class Todo {
        final int id; final String title; final boolean done; final String dueAt; final String category;
        Todo(int id, String title, boolean done, String dueAt, String category) { this.id=id; this.title=title; this.done=done; this.dueAt=dueAt; this.category=category; }
    }
}
