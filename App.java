import com.sun.net.httpserver.HttpServer; // 小さなWebサーバー機能を読み込みます
import java.net.InetSocketAddress; // 待ち受けるポート番号を指定する機能を読み込みます

public class App { // Appという名前のプログラムを定義します
    public static void main(String[] args) throws Exception { // プログラムの開始地点を定義します
        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0); // 8080番ポートでWebサーバーを用意します
        server.createContext("/", exchange -> { // 「/」へのアクセスを受け取ったときの処理です
            String message = "Hello, Server!"; // ブラウザへ返す文字を用意します
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8"); // 文字をUTF-8として返すよう指定します
            byte[] body = message.getBytes("UTF-8"); // 文字を送信できるデータへ変換します
            exchange.sendResponseHeaders(200, body.length); // 正常に処理できたこととデータの長さを返します
            exchange.getResponseBody().write(body); // ブラウザへデータを送ります
            exchange.getResponseBody().close(); // データの送信を終了します
        }); // 「/」への処理の登録を終えます
        server.start(); // Webサーバーの待ち受けを開始します
        System.out.println("サーバー起動: http://localhost:8081 （止めるときは Ctrl+C）"); // 起動したことをターミナルへ表示します
    } // mainの定義を閉じます
} // Appの定義を閉じます