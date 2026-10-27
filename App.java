import com.sun.net.httpserver.HttpServer; // 小さなWebサーバー機能を読み込みます
import java.net.InetSocketAddress; // 待ち受けるポート番号を指定する機能を読み込みます
import java.net.URLDecoder; // 追加：送られてきた日本語を元に戻す機能を読み込みます
import java.nio.charset.StandardCharsets; // 追加：UTF-8という文字コードを指定する機能を読み込みます
import java.util.ArrayList; // 追加：Todoを入れる一覧を作る機能を読み込みます
import java.util.List; // 追加：Todoの一覧を扱う機能を読み込みます

public class App { // Appという名前のプログラムを定義します
    static List<Todo> todos = new ArrayList<>(); // ★変更
    static int nextId = 1; // ★変更

    public static void main(String[] args) throws Exception { // プログラムの開始地点を定義します
        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0); // 8080番ポートでWebサーバーを用意します
        todos.add(new Todo(nextId++, "牛乳を買う")); // ★変更
        Todo egg = new Todo(nextId++, "卵を買う"); // ★変更
        egg.setDone(true); // ★変更
        todos.add(egg); // ★変更
        server.createContext("/", exchange -> { // 「/」へのアクセスを受け取ったときの処理です
            String path = exchange.getRequestURI().getPath(); // アクセスされたパスを取り出します
            String method = exchange.getRequestMethod(); // 追加：GETかPOSTかを取り出します
            String message;
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8"); // 移動：通常は文字としてUTF-8で返すよう指定します
            if (path.equals("/add") && method.equals("POST")) { // 追加：Todoの追加が送られてきたか調べます
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); // 追加：送られてきた中身をUTF-8で受け取ります
                String value = body.substring(5); // 追加：「todo=」の5文字を除きます
                String title = URLDecoder.decode(value, StandardCharsets.UTF_8); // ★変更
                if (!title.isEmpty()) { // ★変更
                    todos.add(new Todo(nextId, title)); // ★変更
                    nextId++; // ★変更
                } // 追加：空の入力を除く処理を終えます
                exchange.getResponseHeaders().set("Location", "/"); // 追加：戻る場所を「/」に指定します
                exchange.sendResponseHeaders(303, -1); // 追加：「/」へ移動する応答を返します
                exchange.close(); // 追加：今回の通信を閉じます
                return; // 追加：この分岐の処理を終えます
            } else if (path.equals("/")) { // 追加：パスが「/」と同じか比べます
                String html = "<form method='post' action='/add'><input name='todo'><button>追加</button></form><ul>"; // 追加：入力フォームとHTMLの箇条書きを始めます
                for (Todo todo : todos) { // ★変更
                    String mark = ""; // ★変更
                    if (todo.isDone()) { // ★変更
                        mark = " ✔"; // ★変更
                    } // ★変更
                    html += "<li>" + todo.getTitle() + mark + "</li>"; // ★変更
                } // 移動：Todoを1件ずつ取り出す処理を終えます
                html += "</ul>"; // 移動：HTMLの箇条書きを閉じます
                message = html; // 移動：組み立てたHTMLを返す内容にします
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); // 移動：HTMLとしてUTF-8で返すよう指定します
            } else {
                message = "ページが見つかりません";
            }
            byte[] body = message.getBytes("UTF-8"); // 文字を送信できるデータへ変換します
            exchange.sendResponseHeaders(200, body.length); // 正常に処理できたこととデータの長さを返します
            exchange.getResponseBody().write(body); // ブラウザへデータを送ります
            exchange.getResponseBody().close(); // データの送信を終了します
        }); // 「/」への処理の登録を終えます
        server.start(); // Webサーバーの待ち受けを開始します
        System.out.println("サーバー起動: http://localhost:8081 （止めるときは Ctrl+C）"); // 起動したことをターミナルへ表示します
    } // mainの定義を閉じます
} // Appの定義を閉じます

class Todo { // ★変更
    private final int id; // ★変更
    private final String title; // ★変更
    private boolean done; // ★変更

    Todo(int id, String title) { // ★変更
        this.id = id; // ★変更
        this.title = title; // ★変更
        this.done = false; // ★変更
    } // ★変更

    int getId() { // ★変更
        return id; // ★変更
    } // ★変更

    String getTitle() { // ★変更
        return title; // ★変更
    } // ★変更

    boolean isDone() { // ★変更
        return done; // ★変更
    } // ★変更

    void setDone(boolean done) { // ★変更
        this.done = done; // ★変更
    } // ★変更
} // ★変更
