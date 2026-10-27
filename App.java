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
    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");

    public static void main(String[] args) throws Exception {
        createTable();
        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500, "text/plain; charset=UTF-8", "データベースエラーが発生しました");
            }
        });
        server.start();
        System.out.println("サーバー起動: http://localhost:8081 （停止は Ctrl+C）");
    }

    private static void handle(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/add") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String title = formValue(body, "todo");
            String dueAt = normalizeDueAt(formValue(body, "dueAt"));
            if (!title.isBlank() && dueAt != null) insertTodo(title, dueAt);
            redirect(exchange);
            return;
        }
        if (path.equals("/done") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) update("UPDATE todos SET done = 1 WHERE id = ?", id);
            redirect(exchange);
            return;
        }
        if (path.equals("/delete") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) update("DELETE FROM todos WHERE id = ?", id);
            redirect(exchange);
            return;
        }
        if (path.equals("/") && method.equals("GET")) {
            showList(exchange);
            return;
        }
        send(exchange, 404, "text/plain; charset=UTF-8", "ページが見つかりません");
    }

    private static void showList(HttpExchange exchange) throws IOException, SQLException {
        List<Todo> todos = selectTodos();
        String filter = normalizeFilter(queryValue(exchange, "filter"));
        String sort = normalizeSort(queryValue(exchange, "sort"));
        List<Todo> displayed = new ArrayList<>();
        for (Todo todo : todos) {
            if (filter.equals("all") || filter.equals("done") == todo.done) displayed.add(todo);
        }
        sortTodos(displayed, sort);
        long completed = todos.stream().filter(todo -> todo.done).count();

        StringBuilder html = new StringBuilder("<!doctype html><html lang='ja'><head><meta charset='UTF-8'>")
                .append("<title>わたしのTodo</title><style>")
                .append("body{max-width:720px;margin:40px auto;padding:0 16px;font-size:16px}h1{font-size:24px}")
                .append("input,button,select{font-size:16px;padding:6px}li{margin:8px 0}.add-form,.controls{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:16px 0}")
                .append(".active{font-weight:bold;background:#333;color:#fff}time{margin-left:8px;color:#555}</style></head><body>")
                .append("<h1>わたしのTodo</h1><form class='add-form' method='post' action='/add'>")
                .append("<input name='todo' placeholder='Todo' required>")
                .append("<label>締切日時 <input type='datetime-local' name='dueAt' required></label>")
                .append("<button>追加</button></form><p>").append(todos.size()).append("件中 ")
                .append(completed).append("件完了</p><form class='controls' method='get' action='/'>")
                .append("<span>表示:</span><input type='hidden' name='sort' value='").append(sort).append("'>")
                .append(filterButton("all", "すべて", filter)).append(filterButton("done", "完了", filter))
                .append(filterButton("undone", "未完了", filter)).append("</form>")
                .append("<form class='controls' method='get' action='/'><span>並び替え:</span>")
                .append("<input type='hidden' name='filter' value='").append(filter).append("'>")
                .append("<select name='sort' onchange='this.form.submit()'>")
                .append(sortOption("newest", "追加順（新しい順）", sort))
                .append(sortOption("oldest", "追加順（古い順）", sort))
                .append(sortOption("name-asc", "名前順（昇順）", sort))
                .append(sortOption("name-desc", "名前順（降順）", sort))
                .append("</select><noscript><button>並び替え</button></noscript></form>");
        if (displayed.isEmpty()) {
            html.append("<p>該当する項目はありません。</p>");
        } else {
            html.append("<ul>");
            for (Todo todo : displayed) {
                html.append("<li>").append(escapeHtml(todo.title))
                        .append(" <time datetime='").append(escapeHtml(todo.dueAt == null ? "" : todo.dueAt))
                        .append("'>締切: ").append(escapeHtml(formatDueAt(todo.dueAt))).append("</time>")
                        .append(todo.done ? " ✓" : "").append(" <a href='/done?id=").append(todo.id)
                        .append("'>完了</a> <a href='/delete?id=").append(todo.id).append("'>削除</a></li>");
            }
            html.append("</ul>");
        }
        html.append("</body></html>");
        send(exchange, 200, "text/html; charset=UTF-8", html.toString());
    }

    private static void createTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY, title TEXT, done INTEGER, due_at TEXT)");
            if (!hasColumn(connection, "due_at")) statement.execute("ALTER TABLE todos ADD COLUMN due_at TEXT");
        }
    }

    private static boolean hasColumn(Connection connection, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(todos)")) {
            while (result.next()) if (column.equals(result.getString("name"))) return true;
        }
        return false;
    }

    private static void insertTodo(String title, String dueAt) throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement("INSERT INTO todos (title, done, due_at) VALUES (?, 0, ?)")) {
            statement.setString(1, title);
            statement.setString(2, dueAt);
            statement.executeUpdate();
        }
    }

    private static void update(String sql, int id) throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    private static List<Todo> selectTodos() throws SQLException {
        List<Todo> todos = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement("SELECT id, title, done, due_at FROM todos ORDER BY id");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) todos.add(new Todo(result.getInt("id"), result.getString("title"), result.getInt("done") != 0, result.getString("due_at")));
        }
        return todos;
    }

    private static void sortTodos(List<Todo> todos, String sort) {
        Comparator<Todo> comparator;
        if (sort.equals("oldest")) comparator = Comparator.comparingInt(todo -> todo.id);
        else if (sort.equals("name-asc") || sort.equals("name-desc")) {
            Collator collator = Collator.getInstance(Locale.JAPANESE);
            comparator = (left, right) -> collator.compare(left.title, right.title);
            if (sort.equals("name-desc")) comparator = comparator.reversed();
            comparator = comparator.thenComparingInt(todo -> todo.id);
        } else comparator = Comparator.comparingInt((Todo todo) -> todo.id).reversed();
        todos.sort(comparator);
    }

    private static String normalizeFilter(String value) {
        return "done".equals(value) || "undone".equals(value) ? value : "all";
    }
    private static String normalizeSort(String value) {
        return "oldest".equals(value) || "name-asc".equals(value) || "name-desc".equals(value) ? value : "newest";
    }
    private static String normalizeDueAt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDateTime.parse(value).toString(); }
        catch (DateTimeParseException ignored) { return null; }
    }
    private static String formatDueAt(String value) {
        if (value == null || value.isBlank()) return "未設定";
        try { return LocalDateTime.parse(value).format(DUE_FORMAT); }
        catch (DateTimeParseException ignored) { return value; }
    }
    private static String filterButton(String value, String label, String current) {
        return "<button type='submit' name='filter' value='" + value + "'" + (value.equals(current) ? " class='active'" : "") + ">" + label + "</button>";
    }
    private static String sortOption(String value, String label, String current) {
        return "<option value='" + value + "'" + (value.equals(current) ? " selected" : "") + ">" + label + "</option>";
    }
    private static String formValue(String body, String name) {
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            if (decode(pair[0]).equals(name)) return pair.length == 2 ? decode(pair[1]) : "";
        }
        return "";
    }
    private static Integer queryId(HttpExchange exchange) {
        try { String value = queryValue(exchange, "id"); return value == null ? null : Integer.valueOf(value); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static String queryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && decode(pair[0]).equals(name)) return decode(pair[1]);
        }
        return name.equals("filter") ? "all" : null;
    }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/"); exchange.sendResponseHeaders(303, -1); exchange.close();
    }
    private static void send(HttpExchange exchange, int status, String contentType, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType); exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body); exchange.getResponseBody().close();
    }

    private static class Todo {
        final int id; final String title; final boolean done; final String dueAt;
        Todo(int id, String title, boolean done, String dueAt) {
            this.id = id; this.title = title; this.done = done; this.dueAt = dueAt;
        }
    }
}
