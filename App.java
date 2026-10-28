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
    private static final DateTimeFormatter HISTORY_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    public static void main(String[] args) throws Exception {
        createTable();
        int port = Integer.getInteger("port", 8081);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (SQLException e) {
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
            int priority = normalizePriority(formValue(body, "priority"));
            String recurrence = normalizeRecurrence(formValue(body, "recurrence"));
            if (!title.isBlank() && (dueInput.isBlank() || dueAt != null) && category != null)
                insertTodo(title, dueAt, category, priority, recurrence);
            redirect(exchange);
            return;
        }
        if (path.equals("/done") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null)
                toggleDoneWithHistory(id);
            redirect(exchange);
            return;
        }
        if (path.equals("/title") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer id = parseInteger(formValue(body, "id"));
            String title = formValue(body, "title").trim();
            if (id != null && !title.isBlank() && title.length() <= 200)
                updateTitleWithHistory(id, title);
            redirect(exchange);
            return;
        }
        if (path.equals("/category") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer id = parseInteger(formValue(body, "id"));
            String category = normalizeCategory(formValue(body, "category"));
            if (id != null && category != null)
                updateCategory(id, category);
            redirect(exchange);
            return;
        }
        if (path.equals("/due-at") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer id = parseInteger(formValue(body, "id"));
            String dueInput = formValue(body, "dueAt");
            String dueAt = normalizeDueAt(dueInput);
            if (id != null && (dueInput.isBlank() || dueAt != null))
                updateDueAtWithHistory(id, dueAt);
            redirect(exchange);
            return;
        }
        if (path.equals("/priority") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer id = parseInteger(formValue(body, "id"));
            int priority = normalizePriority(formValue(body, "priority"));
            if (id != null)
                updatePriorityWithHistory(id, priority);
            redirect(exchange);
            return;
        }
        if (path.equals("/delete") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null)
                deleteTodoWithHistory(id);
            redirect(exchange);
            return;
        }
        if (path.equals("/delete-completed") && method.equals("POST")) {
            deleteCompletedTodosWithHistory();
            redirect(exchange);
            return;
        }
        if (path.equals("/history") && method.equals("GET")) {
            showHistory(exchange);
            return;
        }
        if (path.equals("/") && method.equals("GET")) {
            if (exchange.getRequestURI().getRawQuery() == null) {
                redirect(exchange, "/?filter=all&dueFilter=all&categoryFilter=all&priorityFilter=all&sort=newest");
                return;
            }
            showList(exchange);
            return;
        }
        send(exchange, 404, "text/plain; charset=UTF-8", "ページが見つかりません");
    }

    private static void showList(HttpExchange exchange) throws IOException, SQLException {
        List<Todo> todos = selectTodos();
        // 画面を作り始めた時点の日時を一度だけ取得し、すべてのTodoを同じ時刻で判定します。
        LocalDateTime now = LocalDateTime.now();
        String filter = normalizeFilter(queryValue(exchange, "filter"));
        String dueFilter = normalizeDueFilter(queryValue(exchange, "dueFilter"));
        String categoryFilter = normalizeCategoryFilter(queryValue(exchange, "categoryFilter"));
        String priorityFilter = normalizePriorityFilter(queryValue(exchange, "priorityFilter"));
        String sort = normalizeSort(queryValue(exchange, "sort"));
        List<Todo> displayed = new ArrayList<>();
        for (Todo todo : todos) {
            boolean matchesStatus = filter.equals("all") || filter.equals("done") == todo.done;
            boolean matchesDue = matchesDueFilter(todo, dueFilter, now);
            boolean matchesCategory = categoryFilter.equals("all") || categoryFilter.equals(todo.category);
            boolean matchesPriority = priorityFilter.equals("all")
                    || todo.priority == Integer.parseInt(priorityFilter);
            if (matchesStatus && matchesDue && matchesCategory && matchesPriority)
                displayed.add(todo);
        }
        sortTodos(displayed, sort);
        long completed = todos.stream().filter(todo -> todo.done).count();
        int progress = todos.isEmpty() ? 0 : (int) Math.round(completed * 100.0 / todos.size());

        StringBuilder html = new StringBuilder(
                """
                        <!doctype html><html lang='ja'><head><meta charset='UTF-8'>
                        <meta name='viewport' content='width=device-width,initial-scale=1'><title>TODO LIST</title><style>
                        :root{--ink:#153f50;--line:#55727c;--paper:#e9dfd8;--muted:#6d7779;--accent:#b75f4a}
                        *{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font-family:"Yu Gothic","Hiragino Kaku Gothic ProN",sans-serif;min-height:100vh}
                        .page{width:min(1080px,calc(100% - 32px));margin:42px auto 64px}.masthead{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:12px}
                        h1{margin:0;font-family:"Arial Rounded MT Bold","Trebuchet MS",sans-serif;font-size:clamp(42px,7vw,76px);line-height:.95;letter-spacing:.1em}.flower{font-size:.68em;letter-spacing:0}
                        .summary{text-align:right;font-size:13px;letter-spacing:.08em}.summary strong{font-size:26px}.progress{width:180px;height:5px;background:#cdbfb6;margin-top:8px}.progress span{display:block;height:100%;background:var(--ink)}
                        .panel{border:1px solid var(--line);background:#ffffff80;box-shadow:7px 7px 0 #153f5014}.add-form{display:grid;grid-template-columns:minmax(220px,1fr) 135px 105px 210px 135px auto;border-bottom:1px solid var(--line)}
                        .field{padding:12px 14px;border-right:1px solid var(--line)}label{display:block;font-size:11px;font-weight:700;letter-spacing:.12em;margin-bottom:6px}input,select,button{font:inherit;color:var(--ink)}
                        input,select{width:100%;height:38px;border:0;border-bottom:1px solid #9aadb2;background:transparent;outline:none}input:focus,select:focus{border-color:var(--ink)}
                        .add-button{border:0;background:var(--ink);color:white;padding:0 24px;font-weight:700;letter-spacing:.12em;cursor:pointer}.add-button:hover{background:#24576a}
                        .toolbar{display:flex;justify-content:flex-end;align-items:center;gap:16px;padding:12px 14px;border-bottom:1px solid var(--line);background:#f7f3ef;flex-wrap:wrap}.sort,.bulk-delete{display:flex;align-items:center;gap:7px;margin:0}.sort span{font-size:12px;font-weight:700}.sort select{width:180px;height:32px}
                        .toolbar-actions{display:flex;align-items:center;gap:12px}.history-link{display:inline-grid;place-items:center;height:32px;padding:0 12px;border:1px solid var(--ink);color:var(--ink);text-decoration:none;font-size:13px;font-weight:700}.history-link:hover{background:var(--ink);color:#fff}.bulk-delete button{height:32px;border:1px solid var(--accent);background:transparent;color:var(--accent);padding:0 12px;cursor:pointer}.bulk-delete button:hover{background:var(--accent);color:#fff}.bulk-delete button:disabled{border-color:#aaa;color:#999;cursor:not-allowed;background:transparent}
                        table{width:100%;border-collapse:collapse;table-layout:fixed}.status-col{width:125px}.due-col{width:155px}.category-col{width:145px}.priority-col{width:135px}.action-col{width:120px}
                        th{height:44px;padding:8px;background:var(--ink);color:white;border-right:1px solid #b4c4c9;font-size:12px;letter-spacing:.15em}th:last-child{border-right:0}.header-filter{margin:0}.header-filter select{height:28px;border:0;border-bottom:1px solid #b4c4c9;background:var(--ink);color:#fff;font-size:12px;font-weight:700;letter-spacing:.08em;text-align:center;text-align-last:center;cursor:pointer}.header-filter option{background:#fff;color:var(--ink)}
                        td{height:64px;padding:10px 14px;border-right:1px solid var(--line);border-bottom:1px solid var(--line);background:#ffffffc7;vertical-align:middle}td:last-child{border-right:0}.todo-row:last-child td{border-bottom:0}
                        .check{display:inline-grid;place-items:center;width:25px;height:25px;border:2px solid var(--ink);color:transparent;text-decoration:none}.check:hover{background:#d7e1e2}.done .check{background:var(--ink);color:#fff}.title{font-weight:700;overflow-wrap:anywhere}.title-form{margin:0}.title-input{height:34px;font-weight:700}.done .title-input{text-decoration:line-through;color:#7d898b}.recurrence-badge{display:inline-block;margin-top:5px;padding:2px 8px;border:1px solid #6f8991;border-radius:12px;background:#edf3f3;color:#315866;font-size:11px;font-weight:700;letter-spacing:.05em}.done .recurrence-badge{opacity:.65}
                        time{font-size:13px;color:#4f6268}.due-form,.category-form,.priority-form{margin:0;text-align:center}.due-field{position:relative}.due-input{height:34px;font-size:12px;text-align:center;cursor:pointer}.due-input.no-due{color:transparent}.due-input.no-due:focus{color:var(--ink)}.due-input.no-due::-webkit-datetime-edit{color:transparent}.due-input.no-due:focus::-webkit-datetime-edit{color:var(--ink)}.due-placeholder{position:absolute;inset:0 28px 1px 1px;display:grid;place-items:center;font-size:12px;color:#4f6268;pointer-events:none}.due-field:focus-within .due-placeholder{display:none}.category-select,.priority-select{height:32px;border:1px solid #84989e;border-radius:20px;padding:0 9px;font-size:12px;text-align:center;text-align-last:center;cursor:pointer;background:#fff}.priority-cell{padding-left:8px;padding-right:8px}.priority-select{font-size:15px;letter-spacing:1px;padding-left:9px;padding-right:9px}.actions{display:flex;gap:10px;justify-content:center}.actions a{font-size:13px}.delete{color:var(--accent);text-decoration:none}.empty{padding:60px 20px;text-align:center;background:#ffffffc7;color:var(--muted)}
                        .todo-row.overdue td{background:#fff0ec}.todo-row.overdue .due-input{color:#a63f2d;font-weight:700}.overdue-badge{display:inline-block;margin-top:4px;padding:2px 7px;border-radius:10px;background:var(--accent);color:#fff;font-size:11px;font-weight:700;letter-spacing:.08em}
                        @media(max-width:760px){.page{margin-top:24px}.masthead{align-items:flex-start;flex-direction:column}.summary{text-align:left}.add-form{grid-template-columns:1fr 1fr}.field:first-child{grid-column:1/-1;border-bottom:1px solid var(--line)}.field:nth-child(3){border-right:0}.add-button{grid-column:1/-1;height:48px}.toolbar{align-items:stretch;flex-direction:column}.toolbar-actions{justify-content:space-between;flex-wrap:wrap}.table-wrap{overflow-x:auto}table{min-width:820px}}
                        </style></head><body><main class='page'><header class='masthead'><h1>TODO LIST<span class='flower'>✿</span></h1>
                        """);
        html.append("<div class='summary'><strong>").append(completed).append(" / ").append(todos.size())
                .append("</strong><br>完了したタスク<div class='progress'><span style='width:").append(progress)
                .append("%'></span></div></div></header>")
                .append("<section class='panel'><form class='add-form' method='post' action='/add'>")
                .append("<div class='field'><label for='todo'>内容</label><input id='todo' name='todo' placeholder='新しいタスクを入力' maxlength='200' required></div>")
                .append("<div class='field'><label for='category'>カテゴリ</label><select id='category' name='category' required>")
                .append(categoryOptions()).append("</select></div>")
                .append("<div class='field'><label for='priority'>重要度</label><select id='priority' name='priority' aria-label='重要度'>")
                .append(priorityOptions(1)).append("</select></div>")
                .append("<div class='field'><label for='dueAt'>締切</label><input id='dueAt' type='datetime-local' name='dueAt'></div>")
                .append("<div class='field'><label for='recurrence'>繰り返し</label><select id='recurrence' name='recurrence'>")
                .append(recurrenceOptions("none")).append("</select></div><button class='add-button'>追加 ＋</button></form>")
                .append("<div class='toolbar'><div class='toolbar-actions'><form class='sort' method='get'><span>並び替え</span><input type='hidden' name='filter' value='")
                .append(filter).append("'><input type='hidden' name='categoryFilter' value='")
                .append(escapeHtml(categoryFilter)).append("'><input type='hidden' name='priorityFilter' value='")
                .append(priorityFilter).append("'><input type='hidden' name='dueFilter' value='").append(dueFilter)
                .append("'><select name='sort' onchange='this.form.submit()'>")
                .append(sortOption("newest", "追加順（新しい順）", sort)).append(sortOption("oldest", "追加順（古い順）", sort))
                .append(sortOption("name-asc", "名前順（昇順）", sort))
                .append(sortOption("name-desc", "名前順（降順）", sort)).append(sortOption("due-asc", "締切日順（昇順）", sort))
                .append(sortOption("due-desc", "締切日順（降順）", sort)).append(sortOption("category", "カテゴリ順", sort))
                .append(sortOption("priority-asc", "重要度順（昇順）", sort))
                .append(sortOption("priority-desc", "重要度順（降順）", sort))
                .append("</select><noscript><button>適用</button></noscript></form>")
                .append("<a class='history-link' href='/history'>操作履歴</a>")
                .append("<form class='bulk-delete' method='post' action='/delete-completed' onsubmit=\"return confirm('本当に削除しますか？')\"><button type='submit'")
                .append(completed == 0 ? " disabled" : "").append(">完了項目を一括削除</button></form></div></div>");
        html.append("<div class='table-wrap'><table><thead><tr>")
                .append("<th class='status-col'>").append(statusFilterForm(filter, dueFilter, categoryFilter, priorityFilter, sort)).append("</th>")
                .append("<th class='due-col'>").append(dueFilterForm(filter, dueFilter, categoryFilter, priorityFilter, sort)).append("</th>")
                .append("<th>内容</th><th class='category-col'>").append(categoryFilterForm(filter, dueFilter, categoryFilter, priorityFilter, sort)).append("</th>")
                .append("<th class='priority-col'>").append(priorityFilterForm(filter, dueFilter, categoryFilter, priorityFilter, sort)).append("</th>")
                .append("<th class='action-col'>操作</th></tr></thead><tbody>");
        if (displayed.isEmpty()) {
            html.append("<tr><td class='empty' colspan='6'>該当するタスクはありません</td></tr>");
        } else {
            for (Todo todo : displayed) {
                // 未完了で期限を過ぎたTodoには専用のCSSクラスを付け、ブラウザで見分けられるようにします。
                boolean overdue = isOverdue(todo, now);
                html.append("<tr class='todo-row").append(todo.done ? " done" : "")
                        .append(overdue ? " overdue" : "").append("'><td>");
                html.append("<a class='check' aria-label='").append(todo.done ? "未完了に戻す" : "完了にする")
                        .append("' href='/done?id=").append(todo.id).append("'>✓</a>");
                html.append(
                        "</td><td><form class='due-form' method='post' action='/due-at'><input type='hidden' name='id' value='")
                        .append(todo.id)
                        .append("'><div class='due-field'><input class='due-input")
                        .append(todo.dueAt == null ? " no-due" : "")
                        .append("' type='datetime-local' name='dueAt' aria-label='").append(escapeHtml(todo.title))
                        .append("の締切' value='")
                        .append(escapeHtml(todo.dueAt == null ? "" : todo.dueAt))
                        .append("' onchange='this.form.submit()'>")
                        .append(todo.dueAt == null ? "<span class='due-placeholder'>締切なし</span>" : "")
                        .append("</div>")
                        .append(overdue ? "<span class='overdue-badge'>期限切れ</span>" : "")
                        .append("<noscript><button type='submit'>変更</button></noscript></form></td>")
                        .append("<td class='title'><form class='title-form' method='post' action='/title'>")
                        .append("<input type='hidden' name='id' value='").append(todo.id)
                        .append("'><input class='title-input' name='title' maxlength='200' required aria-label='Todoタイトル' value='")
                        .append(escapeHtml(todo.title)).append("' onchange='this.form.submit()'>")
                        .append("<noscript><button type='submit'>変更</button></noscript></form>");
                if (!"none".equals(todo.recurrence))
                    html.append("<span class='recurrence-badge'>↻ ")
                            .append(recurrenceLabel(todo.recurrence)).append("</span>");
                html.append("</td><td><form class='category-form' method='post' action='/category'>")
                        .append("<input type='hidden' name='id' value='").append(todo.id)
                        .append("'><select class='category-select' name='category' aria-label='")
                        .append(escapeHtml(todo.title)).append("のカテゴリ' onchange='this.form.submit()'>")
                        .append(categoryOptions(todo.category))
                        .append("</select><noscript><button type='submit'>変更</button></noscript></form></td>")
                        .append("<td class='priority-cell'><form class='priority-form' method='post' action='/priority'><input type='hidden' name='id' value='")
                        .append(todo.id).append("'><select class='priority-select' name='priority' aria-label='")
                        .append(escapeHtml(todo.title)).append("の重要度' onchange='this.form.submit()'>")
                        .append(priorityOptions(todo.priority))
                        .append("</select><noscript><button type='submit'>変更</button></noscript></form></td><td><div class='actions'>");
                if (!todo.done)
                    html.append("<a href='/done?id=").append(todo.id).append("'>完了</a>");
                html.append("<a class='delete' href='/delete?id=").append(todo.id).append("'>削除</a></div></td></tr>");
            }
        }
        html.append("</tbody></table></div>");
        html.append("</section></main></body></html>");
        send(exchange, 200, "text/html; charset=UTF-8", html.toString());
    }

    private static void showHistory(HttpExchange exchange) throws IOException, SQLException {
        // DBから直近50件を読み込み、取得した順番（新しい順）でHTMLへ追加します。
        List<HistoryEntry> history = selectHistory();
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang='ja'><head><meta charset='UTF-8'>
                <meta name='viewport' content='width=device-width,initial-scale=1'><title>操作履歴</title><style>
                :root{--ink:#153f50;--line:#55727c;--paper:#e9dfd8;--accent:#b75f4a}
                *{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font-family:"Yu Gothic","Hiragino Kaku Gothic ProN",sans-serif;min-height:100vh}
                .page{width:min(760px,calc(100% - 32px));margin:42px auto 64px}header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:18px}h1{margin:0;font-size:42px;letter-spacing:.08em}
                .back{display:inline-grid;place-items:center;height:38px;padding:0 16px;border:1px solid var(--ink);color:var(--ink);text-decoration:none;font-weight:700}.back:hover{background:var(--ink);color:#fff}
                .history{list-style:none;margin:0;padding:0;border:1px solid var(--line);background:#ffffff80;box-shadow:7px 7px 0 #153f5014}.item{padding:18px 20px;border-bottom:1px solid var(--line);background:#ffffffc7}.item:last-child{border-bottom:0}.date{font-size:13px;color:#5c6c71;margin-bottom:7px}.message{font-weight:700}.action{color:var(--accent)}.changes{margin-top:8px;padding-left:14px;border-left:3px solid #c8b9b0;font-size:13px;line-height:1.7}.empty{padding:50px 20px;text-align:center;color:#6d7779}
                @media(max-width:600px){.page{margin-top:24px}header{align-items:flex-start;flex-direction:column}h1{font-size:34px}}
                </style></head><body><main class='page'><header><h1>操作履歴</h1><a class='back' href='/'>Todo一覧に戻る</a></header>
                """);
        if (history.isEmpty()) {
            html.append("<div class='history empty'>操作履歴はまだありません</div>");
        } else {
            html.append("<ol class='history'>");
            for (HistoryEntry entry : history) {
                html.append("<li class='item'><div class='date'>")
                        .append(escapeHtml(formatHistoryDate(entry.operatedAt))).append("</div>")
                        .append("<div class='message'><span class='action'>【")
                        .append(escapeHtml(entry.action)).append("】</span>「")
                        .append(escapeHtml(entry.todoTitle)).append("」")
                        .append(escapeHtml(historyActionSuffix(entry.action))).append("</div>");
                if (entry.oldValue != null || entry.newValue != null)
                    html.append("<div class='changes'>変更前：")
                            .append(escapeHtml(displayHistoryValue(entry.oldValue))).append("<br>変更後：")
                            .append(escapeHtml(displayHistoryValue(entry.newValue))).append("</div>");
                html.append("</li>");
            }
            html.append("</ol>");
        }
        html.append("</main></body></html>");
        // Content-TypeにUTF-8を指定して、日本語を文字化けせずブラウザへ送ります。
        send(exchange, 200, "text/html; charset=UTF-8", html.toString());
    }

    private static void createTable() throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL); Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY, title TEXT, done INTEGER, due_at TEXT)");
            if (!hasColumn(c, "due_at"))
                s.execute("ALTER TABLE todos ADD COLUMN due_at TEXT");
            if (!hasColumn(c, "category"))
                s.execute("ALTER TABLE todos ADD COLUMN category TEXT DEFAULT 'その他'");
            if (!hasColumn(c, "priority"))
                s.execute("ALTER TABLE todos ADD COLUMN priority INTEGER NOT NULL DEFAULT 1");
            if (!hasColumn(c, "recurrence"))
                s.execute("ALTER TABLE todos ADD COLUMN recurrence TEXT NOT NULL DEFAULT 'none'");
            if (!hasColumn(c, "recurrence_generated"))
                s.execute("ALTER TABLE todos ADD COLUMN recurrence_generated INTEGER NOT NULL DEFAULT 0");
            // Todoを削除しても履歴を残すため、外部キーは設定せずTodo IDを数値として保存します。
            s.execute("""
                    CREATE TABLE IF NOT EXISTS operation_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        operated_at TEXT NOT NULL,
                        todo_id INTEGER NOT NULL,
                        todo_title TEXT NOT NULL,
                        action TEXT NOT NULL,
                        old_value TEXT,
                        new_value TEXT
                    )
                    """);
        }
    }

    private static boolean hasColumn(Connection c, String column) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA table_info(todos)")) {
            while (r.next())
                if (column.equals(r.getString("name")))
                    return true;
        }
        return false;
    }

    private static void insertTodo(String title, String dueAt, String category, int priority, String recurrence) throws SQLException {
        runInTransaction(c -> {
            // RETURN_GENERATED_KEYSを指定すると、追加されたTodoのIDを直後に取得できます。
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO todos (title, done, due_at, category, priority, recurrence, recurrence_generated) VALUES (?, 0, ?, ?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                s.setString(1, title);
                s.setString(2, dueAt);
                s.setString(3, category);
                s.setInt(4, priority);
                s.setString(5, recurrence);
                if (s.executeUpdate() == 1) {
                    try (ResultSet keys = s.getGeneratedKeys()) {
                        if (!keys.next())
                            throw new SQLException("追加したTodoのIDを取得できませんでした");
                        saveHistory(c, keys.getInt(1), title, "追加", null, null);
                    }
                }
            }
        });
    }

    private static void updateCategory(int id, String category) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL);
                PreparedStatement s = c.prepareStatement("UPDATE todos SET category = ? WHERE id = ?")) {
            s.setString(1, category);
            s.setInt(2, id);
            s.executeUpdate();
        }
    }

    private static void toggleDoneWithHistory(int id) throws SQLException {
        runInTransaction(c -> {
            Todo todo = selectTodo(c, id);
            if (todo == null)
                return;
            boolean newDone = !todo.done;
            try (PreparedStatement s = c.prepareStatement("UPDATE todos SET done = ? WHERE id = ?")) {
                s.setInt(1, newDone ? 1 : 0);
                s.setInt(2, id);
                if (s.executeUpdate() == 1) {
                    saveHistory(c, id, todo.title, newDone ? "完了" : "未完了", null, null);
                    if (newDone && !"none".equals(todo.recurrence) && !todo.recurrenceGenerated)
                        createNextRecurringTodo(c, todo);
                }
            }
        });
    }

    private static void createNextRecurringTodo(Connection c, Todo todo) throws SQLException {
        LocalDateTime base = todo.dueAt == null || todo.dueAt.isBlank()
                ? LocalDateTime.now()
                : LocalDateTime.parse(todo.dueAt);
        LocalDateTime nextDue = switch (todo.recurrence) {
            case "daily" -> base.plusDays(1);
            case "weekly" -> base.plusWeeks(1);
            case "monthly" -> base.plusMonths(1);
            default -> throw new IllegalArgumentException("不正な繰り返し種別です: " + todo.recurrence);
        };
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO todos (title, done, due_at, category, priority, recurrence, recurrence_generated) VALUES (?, 0, ?, ?, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS);
                PreparedStatement mark = c.prepareStatement(
                        "UPDATE todos SET recurrence_generated = 1 WHERE id = ? AND recurrence_generated = 0")) {
            insert.setString(1, todo.title);
            insert.setString(2, nextDue.toString());
            insert.setString(3, todo.category);
            insert.setInt(4, todo.priority);
            insert.setString(5, todo.recurrence);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next())
                    throw new SQLException("次回TodoのIDを取得できませんでした");
                saveHistory(c, keys.getInt(1), todo.title, "追加", null, null);
            }
            mark.setInt(1, todo.id);
            if (mark.executeUpdate() != 1)
                throw new SQLException("繰り返しTodoの生成状態を更新できませんでした");
        }
    }

    private static void updateTitleWithHistory(int id, String title) throws SQLException {
        runInTransaction(c -> {
            Todo todo = selectTodo(c, id);
            // 値が同じ場合はデータを変更せず、履歴も保存しません。
            if (todo == null || Objects.equals(todo.title, title))
                return;
            try (PreparedStatement s = c.prepareStatement("UPDATE todos SET title = ? WHERE id = ?")) {
                s.setString(1, title);
                s.setInt(2, id);
                if (s.executeUpdate() == 1)
                    saveHistory(c, id, title, "タイトル変更", todo.title, title);
            }
        });
    }

    private static void updateDueAtWithHistory(int id, String dueAt) throws SQLException {
        runInTransaction(c -> {
            Todo todo = selectTodo(c, id);
            if (todo == null || Objects.equals(todo.dueAt, dueAt))
                return;
            try (PreparedStatement s = c.prepareStatement("UPDATE todos SET due_at = ? WHERE id = ?")) {
                s.setString(1, dueAt);
                s.setInt(2, id);
                if (s.executeUpdate() == 1)
                    saveHistory(c, id, todo.title, "期限変更", todo.dueAt, dueAt);
            }
        });
    }

    private static void updatePriorityWithHistory(int id, int priority) throws SQLException {
        runInTransaction(c -> {
            Todo todo = selectTodo(c, id);
            if (todo == null || todo.priority == priority)
                return;
            try (PreparedStatement s = c.prepareStatement("UPDATE todos SET priority = ? WHERE id = ?")) {
                s.setInt(1, priority);
                s.setInt(2, id);
                if (s.executeUpdate() == 1)
                    saveHistory(c, id, todo.title, "優先度変更", Integer.toString(todo.priority),
                            Integer.toString(priority));
            }
        });
    }

    private static void deleteTodoWithHistory(int id) throws SQLException {
        runInTransaction(c -> {
            Todo todo = selectTodo(c, id);
            if (todo == null)
                return;
            try (PreparedStatement s = c.prepareStatement("DELETE FROM todos WHERE id = ?")) {
                s.setInt(1, id);
                if (s.executeUpdate() == 1)
                    saveHistory(c, id, todo.title, "削除", null, null);
            }
        });
    }

    private static void deleteCompletedTodosWithHistory() throws SQLException {
        runInTransaction(c -> {
            List<Todo> completed = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT id,title,done,due_at,category,priority,recurrence,recurrence_generated FROM todos WHERE done = 1");
                    ResultSet r = s.executeQuery()) {
                while (r.next())
                    completed.add(todoFromResultSet(r));
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM todos WHERE id = ?")) {
                for (Todo todo : completed) {
                    s.setInt(1, todo.id);
                    if (s.executeUpdate() == 1)
                        saveHistory(c, todo.id, todo.title, "削除", null, null);
                }
            }
        });
    }

    private static void saveHistory(Connection c, int todoId, String todoTitle, String action,
            String oldValue, String newValue) throws SQLException {
        // このINSERT文は、1件の操作履歴をoperation_historyテーブルへ追加します。
        String sql = "INSERT INTO operation_history "
                + "(operated_at, todo_id, todo_title, action, old_value, new_value) VALUES (?, ?, ?, ?, ?, ?)";
        // PreparedStatementは値を「?」へ安全に設定し、文字列をSQLとして誤解されることを防ぎます。
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, LocalDateTime.now().toString());
            s.setInt(2, todoId);
            s.setString(3, todoTitle);
            s.setString(4, action);
            s.setString(5, oldValue);
            s.setString(6, newValue);
            s.executeUpdate();
        }
    }

    private static void runInTransaction(TransactionWork work) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            c.setAutoCommit(false);
            try {
                // Todoの変更と履歴の追加をまとめて実行し、両方成功したときだけ確定します。
                work.execute(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                // 途中で失敗した場合は変更を取り消し、Todoと履歴の食い違いを防ぎます。
                try {
                    c.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        }
    }

    private static Todo selectTodo(Connection c, int id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id,title,done,due_at,category,priority,recurrence,recurrence_generated FROM todos WHERE id = ?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? todoFromResultSet(r) : null;
            }
        }
    }

    private static Todo todoFromResultSet(ResultSet r) throws SQLException {
        return new Todo(r.getInt("id"), r.getString("title"), r.getInt("done") != 0, r.getString("due_at"),
                categoryOrOther(r.getString("category")), normalizePriority(r.getString("priority")),
                normalizeRecurrence(r.getString("recurrence")), r.getInt("recurrence_generated") != 0);
    }

    private static List<Todo> selectTodos() throws SQLException {
        List<Todo> todos = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB_URL);
                PreparedStatement s = c.prepareStatement("SELECT id,title,done,due_at,category,priority,recurrence,recurrence_generated FROM todos ORDER BY id");
                ResultSet r = s.executeQuery()) {
            while (r.next())
                todos.add(todoFromResultSet(r));
        }
        return todos;
    }

    private static List<HistoryEntry> selectHistory() throws SQLException {
        List<HistoryEntry> history = new ArrayList<>();
        // ORDER BY id DESCで新しい履歴を先にし、LIMIT 50で直近50件だけを読み込みます。
        String sql = "SELECT id,operated_at,todo_id,todo_title,action,old_value,new_value "
                + "FROM operation_history ORDER BY id DESC LIMIT 50";
        try (Connection c = DriverManager.getConnection(DB_URL);
                PreparedStatement s = c.prepareStatement(sql);
                ResultSet r = s.executeQuery()) {
            while (r.next())
                history.add(new HistoryEntry(r.getInt("id"), r.getString("operated_at"), r.getInt("todo_id"),
                        r.getString("todo_title"), r.getString("action"), r.getString("old_value"),
                        r.getString("new_value")));
        }
        return history;
    }

    private static String formatHistoryDate(String value) {
        try {
            return LocalDateTime.parse(value).format(HISTORY_FORMAT);
        } catch (DateTimeParseException ignored) {
            return value;
        }
    }

    private static String displayHistoryValue(String value) {
        return value == null || value.isBlank() ? "なし" : value;
    }

    private static String historyActionSuffix(String action) {
        return switch (action) {
            case "追加" -> "を追加";
            case "完了" -> "を完了";
            case "未完了" -> "を未完了に変更";
            case "削除" -> "を削除";
            case "タイトル変更" -> "のタイトルを変更";
            case "期限変更" -> "の期限を変更";
            case "優先度変更" -> "の優先度を変更";
            default -> "を変更";
        };
    }

    private static void sortTodos(List<Todo> todos, String sort) {
        Comparator<Todo> comparator;
        if (sort.equals("oldest"))
            comparator = Comparator.comparingInt(t -> t.id);
        else if (sort.equals("name-asc") || sort.equals("name-desc")) {
            Collator collator = Collator.getInstance(Locale.JAPANESE);
            comparator = (a, b) -> collator.compare(a.title, b.title);
            if (sort.equals("name-desc"))
                comparator = comparator.reversed();
            comparator = comparator.thenComparingInt(t -> t.id);
        } else if (sort.equals("due-asc") || sort.equals("due-desc")) {
            Comparator<String> dueComparator = sort.equals("due-asc")
                    ? Comparator.naturalOrder()
                    : Comparator.reverseOrder();
            comparator = Comparator.comparing((Todo t) -> t.dueAt, Comparator.nullsLast(dueComparator))
                    .thenComparingInt(t -> t.id);
        } else if (sort.equals("category"))
            comparator = Comparator.comparingInt((Todo t) -> CATEGORIES.indexOf(t.category))
                    .thenComparingInt(t -> t.id);
        else if (sort.equals("priority-asc") || sort.equals("priority-desc")) {
            comparator = Comparator.comparingInt((Todo t) -> t.priority);
            if (sort.equals("priority-desc"))
                comparator = comparator.reversed();
            comparator = comparator.thenComparingInt(t -> t.id);
        }
        else
            comparator = Comparator.comparingInt((Todo t) -> t.id).reversed();
        todos.sort(comparator);
    }

    private static String normalizeFilter(String v) {
        return "done".equals(v) || "undone".equals(v) ? v : "all";
    }

    private static String normalizeDueFilter(String v) {
        return "overdue".equals(v) || "upcoming".equals(v) || "none".equals(v) ? v : "all";
    }

    private static boolean matchesDueFilter(Todo todo, String dueFilter, LocalDateTime now) {
        if ("all".equals(dueFilter))
            return true;
        if ("none".equals(dueFilter))
            return todo.dueAt == null || todo.dueAt.isBlank();
        if (todo.dueAt == null || todo.dueAt.isBlank())
            return false;
        try {
            LocalDateTime dueAt = LocalDateTime.parse(todo.dueAt);
            // 「期限切れ」フィルターにも、画面表示と同じ未完了条件を適用します。
            return "overdue".equals(dueFilter) ? isOverdue(todo, now) : !dueAt.isBefore(now);
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static boolean isOverdue(Todo todo, LocalDateTime now) {
        // 完了済み、または期限が設定されていないTodoは期限切れではありません。
        if (todo.done || todo.dueAt == null || todo.dueAt.isBlank())
            return false;
        try {
            // 保存済みの期限を日時に変換し、現在日時より前なら期限切れです。
            return LocalDateTime.parse(todo.dueAt).isBefore(now);
        } catch (DateTimeParseException ignored) {
            // 不正な期限データがあっても画面を壊さず、期限切れではないものとして扱います。
            return false;
        }
    }

    private static String normalizeCategoryFilter(String v) {
        return CATEGORIES.contains(v) ? v : "all";
    }

    private static String normalizePriorityFilter(String v) {
        Integer priority = parseInteger(v);
        return priority != null && priority >= 1 && priority <= 3 ? priority.toString() : "all";
    }

    private static String normalizeSort(String v) {
        return "oldest".equals(v) || "name-asc".equals(v) || "name-desc".equals(v) || "due-asc".equals(v)
                || "due-desc".equals(v) || "category".equals(v) || "priority-asc".equals(v)
                || "priority-desc".equals(v) ? v : "newest";
    }

    private static String normalizeDueAt(String v) {
        if (v == null || v.isBlank())
            return null;
        try {
            return LocalDateTime.parse(v).toString();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String normalizeCategory(String v) {
        return CATEGORIES.contains(v) ? v : null;
    }

    private static int normalizePriority(String v) {
        Integer priority = parseInteger(v);
        return priority != null && priority >= 1 && priority <= 3 ? priority : 1;
    }

    private static String categoryOrOther(String v) {
        String category = normalizeCategory(v);
        return category == null ? "その他" : category;
    }

    private static String categoryOptions() {
        return categoryOptions(null);
    }

    private static String categoryOptions(String selectedCategory) {
        StringBuilder out = new StringBuilder();
        for (String category : CATEGORIES)
            out.append("<option value='").append(escapeHtml(category)).append("'")
                    .append(category.equals(selectedCategory) ? " selected" : "").append(">")
                    .append(escapeHtml(category)).append("</option>");
        return out.toString();
    }

    private static String categoryFilterOptions(String selectedCategory) {
        StringBuilder out = new StringBuilder("<option value='all'>すべて</option>");
        for (String category : CATEGORIES)
            out.append("<option value='").append(escapeHtml(category)).append("'")
                    .append(category.equals(selectedCategory) ? " selected" : "").append(">")
                    .append(escapeHtml(category)).append("</option>");
        return out.toString();
    }

    private static String priorityOptions(int selectedPriority) {
        StringBuilder out = new StringBuilder();
        for (int priority = 1; priority <= 3; priority++) {
            String stars = "☆".repeat(priority);
            out.append("<option value='").append(priority).append("'")
                    .append(priority == selectedPriority ? " selected" : "").append(">")
                    .append(stars).append("</option>");
        }
        return out.toString();
    }

    private static String priorityFilterOptions(String selectedPriority) {
        StringBuilder out = new StringBuilder("<option value='all'>すべて</option>");
        for (int priority = 1; priority <= 3; priority++) {
            String value = Integer.toString(priority);
            out.append("<option value='").append(value).append("'")
                    .append(value.equals(selectedPriority) ? " selected" : "").append(">")
                    .append("☆".repeat(priority)).append("</option>");
        }
        return out.toString();
    }

    private static String recurrenceOptions(String selected) {
        StringBuilder out = new StringBuilder();
        String[][] options = { { "none", "当日のみ" }, { "daily", "毎日" },
                { "weekly", "毎週" }, { "monthly", "毎月" } };
        for (String[] option : options)
            out.append("<option value='").append(option[0]).append("'")
                    .append(option[0].equals(selected) ? " selected" : "").append(">")
                    .append(option[1]).append("</option>");
        return out.toString();
    }

    private static String normalizeRecurrence(String value) {
        return Set.of("daily", "weekly", "monthly").contains(value) ? value : "none";
    }

    private static String recurrenceLabel(String recurrence) {
        return switch (recurrence) {
            case "daily" -> "毎日";
            case "weekly" -> "毎週";
            case "monthly" -> "毎月";
            default -> "当日のみ";
        };
    }

    private static String formatDueAt(String v) {
        if (v == null || v.isBlank())
            return "締切なし";
        try {
            return LocalDateTime.parse(v).format(DUE_FORMAT);
        } catch (DateTimeParseException ignored) {
            return v;
        }
    }

    private static String statusFilterForm(String filter, String dueFilter, String categoryFilter,
            String priorityFilter, String sort) {
        return filterForm("filter", filter,
                new String[][] { { "all", "進捗：すべて" }, { "done", "進捗：完了" }, { "undone", "進捗：未完了" } },
                filter, dueFilter, categoryFilter, priorityFilter, sort);
    }

    private static String dueFilterForm(String filter, String dueFilter, String categoryFilter,
            String priorityFilter, String sort) {
        return filterForm("dueFilter", dueFilter,
                new String[][] { { "all", "締切：すべて" }, { "overdue", "締切：期限切れ" },
                        { "upcoming", "締切：今後" }, { "none", "締切：なし" } },
                filter, dueFilter, categoryFilter, priorityFilter, sort);
    }

    private static String categoryFilterForm(String filter, String dueFilter, String categoryFilter,
            String priorityFilter, String sort) {
        String[][] options = new String[CATEGORIES.size() + 1][2];
        options[0] = new String[] { "all", "カテゴリ：すべて" };
        for (int i = 0; i < CATEGORIES.size(); i++)
            options[i + 1] = new String[] { CATEGORIES.get(i), CATEGORIES.get(i) };
        return filterForm("categoryFilter", categoryFilter, options, filter, dueFilter, categoryFilter,
                priorityFilter, sort);
    }

    private static String priorityFilterForm(String filter, String dueFilter, String categoryFilter,
            String priorityFilter, String sort) {
        return filterForm("priorityFilter", priorityFilter,
                new String[][] { { "all", "重要度：すべて" }, { "1", "☆" }, { "2", "☆☆" }, { "3", "☆☆☆" } },
                filter, dueFilter, categoryFilter, priorityFilter, sort);
    }

    private static String filterForm(String fieldName, String selected, String[][] options, String filter,
            String dueFilter, String categoryFilter, String priorityFilter, String sort) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("filter", filter);
        values.put("dueFilter", dueFilter);
        values.put("categoryFilter", categoryFilter);
        values.put("priorityFilter", priorityFilter);
        values.put("sort", sort);
        StringBuilder out = new StringBuilder("<form class='header-filter' method='get'>");
        for (Map.Entry<String, String> entry : values.entrySet())
            if (!entry.getKey().equals(fieldName))
                out.append("<input type='hidden' name='").append(entry.getKey()).append("' value='")
                        .append(escapeHtml(entry.getValue())).append("'>");
        out.append("<select name='").append(fieldName).append("' aria-label='絞り込み' onchange='this.form.submit()'>");
        for (String[] option : options)
            out.append("<option value='").append(escapeHtml(option[0])).append("'")
                    .append(option[0].equals(selected) ? " selected" : "").append(">")
                    .append(escapeHtml(option[1])).append("</option>");
        return out.append("</select><noscript><button type='submit'>表示</button></noscript></form>").toString();
    }

    private static String sortOption(String value, String label, String current) {
        return "<option value='" + value + "'" + (value.equals(current) ? " selected" : "") + ">" + label + "</option>";
    }

    private static String formValue(String body, String name) {
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            if (decode(pair[0]).equals(name))
                return pair.length == 2 ? decode(pair[1]) : "";
        }
        return "";
    }

    private static Integer queryId(HttpExchange e) {
        try {
            String v = queryValue(e, "id");
            return v == null ? null : Integer.valueOf(v);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseInteger(String v) {
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String queryValue(HttpExchange e, String name) {
        String query = e.getRequestURI().getRawQuery();
        if (query != null)
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && decode(pair[0]).equals(name))
                    return decode(pair[1]);
            }
        return name.equals("filter") ? "all" : null;
    }

    private static String decode(String v) {
        return URLDecoder.decode(v, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'",
                "&#39;");
    }

    private static void redirect(HttpExchange e) throws IOException {
        redirect(e, "/");
    }

    private static void redirect(HttpExchange e, String location) throws IOException {
        e.getResponseHeaders().set("Location", location);
        e.sendResponseHeaders(303, -1);
        e.close();
    }

    private static void send(HttpExchange e, int status, String type, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", type);
        e.sendResponseHeaders(status, body.length);
        e.getResponseBody().write(body);
        e.getResponseBody().close();
    }

    @FunctionalInterface
    private interface TransactionWork {
        // トランザクション内で実行したいDB処理を、ラムダ式で渡すための型です。
        void execute(Connection connection) throws SQLException;
    }

    private static class HistoryEntry {
        final int id;
        final String operatedAt;
        final int todoId;
        final String todoTitle;
        final String action;
        final String oldValue;
        final String newValue;

        HistoryEntry(int id, String operatedAt, int todoId, String todoTitle, String action, String oldValue,
                String newValue) {
            this.id = id;
            this.operatedAt = operatedAt;
            this.todoId = todoId;
            this.todoTitle = todoTitle;
            this.action = action;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }

    private static class Todo {
        final int id;
        final String title;
        final boolean done;
        final String dueAt;
        final String category;
        final int priority;
        final String recurrence;
        final boolean recurrenceGenerated;

        Todo(int id, String title, boolean done, String dueAt, String category, int priority,
                String recurrence, boolean recurrenceGenerated) {
            this.id = id;
            this.title = title;
            this.done = done;
            this.dueAt = dueAt;
            this.category = category;
            this.priority = priority;
            this.recurrence = recurrence;
            this.recurrenceGenerated = recurrenceGenerated;
        }
    }
}
