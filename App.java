import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection; // ★ SQLite接続用
import java.sql.DriverManager; // ★ SQLite接続用
import java.sql.PreparedStatement; // ★ SQLを安全に実行するため追加
import java.sql.ResultSet; // ★ SELECT結果の読み出し用
import java.sql.SQLException; // ★ DBエラー処理用
import java.sql.Statement; // ★ テーブル作成用
import java.text.Collator;
import java.util.ArrayList; // ★ SELECT結果を一覧にするため使用
import java.util.Comparator;
import java.util.List; // ★ SELECT結果を一覧にするため使用
import java.util.Locale;

public class App {
    private static final String DB_URL = "jdbc:sqlite:todos.db"; // ★ CSVではなくSQLiteを指定

    public static void main(String[] args) throws Exception {
        createTable(); // ★ 起動時にtodos表を用意

        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
        server.createContext("/", exchange -> {
            try { // ★ DB処理で発生するSQLExceptionを処理
                handle(exchange); // ★ リクエスト処理を分離
            } catch (SQLException e) { // ★ DBエラーをHTTP 500に変換
                e.printStackTrace(); // ★ 原因をサーバー側へ出力
                send(exchange, 500, "text/plain; charset=UTF-8", "データベースエラーが発生しました"); // ★
            }
        });
        server.start();
        System.out.println("サーバー起動: http://localhost:8081 （停止は Ctrl+C）");
    }

    private static void handle(HttpExchange exchange) throws IOException, SQLException { // ★ DB例外も呼び出し元へ渡す
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/add") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String title = formValue(body, "todo");
            if (!title.isBlank()) {
                insertTodo(title); // ★ INSERTで追加
            }
            redirect(exchange);
            return;
        }

        if (path.equals("/done") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) {
                completeTodo(id); // ★ UPDATEで完了状態に変更
            }
            redirect(exchange);
            return;
        }

        if (path.equals("/delete") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) {
                deleteTodo(id); // ★ DELETEで削除
            }
            redirect(exchange);
            return;
        }

        if (path.equals("/") && method.equals("GET")) {
            List<Todo> todos = selectTodos(); // ★ SELECTで最新の一覧を読み出す
            String filter = normalizeFilter(queryValue(exchange, "filter"));
            String sort = normalizeSort(queryValue(exchange, "sort"));
            List<Todo> displayedTodos = filterTodos(todos, filter);
            sortTodos(displayedTodos, sort);
            long completedCount = todos.stream().filter(todo -> todo.done).count();
            StringBuilder html = new StringBuilder()
                    .append("<!doctype html><html lang='ja'><head><meta charset='UTF-8'>")
                    .append("<title>わたしのTodo</title>")
                    .append("<style>body{max-width:640px;margin:40px auto;padding:0 16px;font-size:16px}")
                    .append("h1{font-size:24px}input,button,select{font-size:16px;padding:6px}li{margin:8px 0}")
                    .append(".controls{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}")
                    .append(".controls span{align-self:center}.active{font-weight:bold;background:#333;color:#fff}</style>")
                    .append("</head><body><h1>わたしのTodo</h1>")
                    .append("<form method='post' action='/add'><input name='todo' required>")
                    .append("<button>追加</button></form>")
                    .append("<p>").append(todos.size()).append("件中")
                    .append(completedCount).append("件完了</p>")
                    .append("<form class='controls' method='get' action='/'>")
                    .append("<span>表示:</span><input type='hidden' name='sort' value='").append(sort).append("'>")
                    .append(filterButton("all", "全て", filter))
                    .append(filterButton("done", "完了", filter))
                    .append(filterButton("undone", "未完了", filter))
                    .append("</form>")
                    .append("<form class='controls' method='get' action='/'>")
                    .append("<span>並び替え:</span><input type='hidden' name='filter' value='").append(filter).append("'>")
                    .append("<select name='sort' onchange='this.form.submit()'>")
                    .append(sortOption("newest", "追加順（新しい順）", sort))
                    .append(sortOption("oldest", "追加順（古い順）", sort))
                    .append(sortOption("name-asc", "名前順（昇順）", sort))
                    .append(sortOption("name-desc", "名前順（降順）", sort))
                    .append("</select><noscript><button type='submit'>切り替え</button></noscript>")
                    .append("</form>");

            if (displayedTodos.isEmpty()) {
                html.append("<p>該当する項目はありません。</p>");
            } else {
                html.append("<ul>");
                for (Todo todo : displayedTodos) {
                    html.append("<li>")
                            .append(escapeHtml(todo.title))
                            .append(todo.done ? " ✓" : "")
                            .append(" <a href='/done?id=").append(todo.id).append("'>完了</a>")
                            .append(" <a href='/delete?id=").append(todo.id).append("'>削除</a></li>");
                }
                html.append("</ul>");
            }
            html.append("</body></html>");
            send(exchange, 200, "text/html; charset=UTF-8", html.toString());
            return;
        }

        send(exchange, 404, "text/plain; charset=UTF-8", "ページが見つかりません");
    }

    private static void createTable() throws SQLException { // ★ SQLite用テーブル初期化
        String sql = "CREATE TABLE IF NOT EXISTS todos " // ★
                + "(id INTEGER PRIMARY KEY, title TEXT, done INTEGER)"; // ★
        try (Connection connection = DriverManager.getConnection(DB_URL); // ★
             Statement statement = connection.createStatement()) { // ★
            statement.execute(sql); // ★
        }
    }

    private static void insertTodo(String title) throws SQLException { // ★ CSV保存をINSERTへ変更
        String sql = "INSERT INTO todos (title, done) VALUES (?, ?)"; // ★
        try (Connection connection = DriverManager.getConnection(DB_URL); // ★
             PreparedStatement statement = connection.prepareStatement(sql)) { // ★
            statement.setString(1, title); // ★
            statement.setInt(2, 0); // ★
            statement.executeUpdate(); // ★
        }
    }

    private static void completeTodo(int id) throws SQLException { // ★ メモリ更新をUPDATEへ変更
        String sql = "UPDATE todos SET done = ? WHERE id = ?"; // ★
        try (Connection connection = DriverManager.getConnection(DB_URL); // ★
             PreparedStatement statement = connection.prepareStatement(sql)) { // ★
            statement.setInt(1, 1); // ★
            statement.setInt(2, id); // ★
            statement.executeUpdate(); // ★
        }
    }

    private static void deleteTodo(int id) throws SQLException { // ★ メモリ削除をDELETEへ変更
        String sql = "DELETE FROM todos WHERE id = ?"; // ★
        try (Connection connection = DriverManager.getConnection(DB_URL); // ★
             PreparedStatement statement = connection.prepareStatement(sql)) { // ★
            statement.setInt(1, id); // ★
            statement.executeUpdate(); // ★
        }
    }

    private static List<Todo> selectTodos() throws SQLException { // ★ CSV読込をSELECTへ変更
        List<Todo> todos = new ArrayList<>(); // ★
        String sql = "SELECT id, title, done FROM todos ORDER BY id"; // ★
        try (Connection connection = DriverManager.getConnection(DB_URL); // ★
             PreparedStatement statement = connection.prepareStatement(sql); // ★
             ResultSet resultSet = statement.executeQuery()) { // ★
            while (resultSet.next()) { // ★
                todos.add(new Todo( // ★
                        resultSet.getInt("id"), // ★
                        resultSet.getString("title"), // ★
                        resultSet.getInt("done") != 0)); // ★
            }
        }
        return todos; // ★
    }

    private static List<Todo> filterTodos(List<Todo> todos, String filter) {
        List<Todo> filteredTodos = new ArrayList<>();
        for (Todo todo : todos) {
            if (filter.equals("done") && todo.done
                    || filter.equals("undone") && !todo.done
                    || filter.equals("all")) {
                filteredTodos.add(todo);
            }
        }
        return filteredTodos;
    }

    private static String normalizeFilter(String filter) {
        if (filter == null || !filter.equals("done") && !filter.equals("undone")) {
            return "all";
        }
        return filter;
    }

    private static void sortTodos(List<Todo> todos, String sort) {
        Comparator<Todo> comparator;
        if (sort.equals("oldest")) {
            comparator = Comparator.comparingInt(todo -> todo.id);
        } else if (sort.equals("name-asc") || sort.equals("name-desc")) {
            Collator collator = Collator.getInstance(Locale.JAPANESE);
            comparator = (left, right) -> collator.compare(left.title, right.title);
            if (sort.equals("name-desc")) {
                comparator = comparator.reversed();
            }
            comparator = comparator.thenComparingInt(todo -> todo.id);
        } else {
            comparator = Comparator.comparingInt((Todo todo) -> todo.id).reversed();
        }
        todos.sort(comparator);
    }

    private static String normalizeSort(String sort) {
        if (sort == null || !sort.equals("oldest") && !sort.equals("name-asc")
                && !sort.equals("name-desc")) {
            return "newest";
        }
        return sort;
    }

    private static String filterButton(String value, String label, String currentFilter) {
        String activeClass = value.equals(currentFilter) ? " class='active'" : "";
        return "<button type='submit' name='filter' value='" + value + "'"
                + activeClass + ">" + label + "</button>";
    }

    private static String sortOption(String value, String label, String currentSort) {
        String selected = value.equals(currentSort) ? " selected" : "";
        return "<option value='" + value + "'" + selected + ">" + label + "</option>";
    }

    private static String formValue(String body, String name) {
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            }
        }
        return "";
    }

    private static Integer queryId(HttpExchange exchange) {
        String value = queryValue(exchange, "id");
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String queryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return name.equals("filter") ? "all" : null;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2
                    && URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return name.equals("filter") ? "all" : null;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String message)
            throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private static class Todo {
        private final int id;
        private final String title;
        private final boolean done;

        private Todo(int id, String title, boolean done) { // ★ SELECTしたdoneも受け取る
            this.id = id;
            this.title = title;
            this.done = done; // ★ DBの完了状態を保持
        }
    }
}
