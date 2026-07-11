# Fast Explorer

Windows エクスプローラより **ファイルサーバ (UNC/SMB) へ高速アクセス** するための軽量エクスプローラです。

**Swing + FlatLaf** でモダンな UI、PowerShell の `Get-ChildItem` に近い速度でフォルダ一覧を表示します。

## なぜ Windows エクスプローラは遅いのか

| 原因 | 説明 |
|------|------|
| シェル拡張 | 右クリックメニュー、列プロバイダなどが各ファイルにフック |
| サムネイル / プレビュー | 画像・PDF 等の生成で追加 I/O |
| プロパティハンドラ | メタデータ (著者、タグ等) の取得 |
| アイコンキャッシュ | ファイル種別ごとのアイコン解決 |

PowerShell は **ファイル名とディレクトリ属性だけ** を取るため、ネットワーク越しでも軽快です。

## Fast Explorer の方針

- Java NIO `DirectoryStream` で **シェル拡張を一切使わない**
- **H2 Database** でディレクトリ一覧・ファイル情報をローカルキャッシュ
- キャッシュヒット時は **即座に表示**（2回目以降のフォルダ移動が高速）
- 期限切れキャッシュは **stale-while-revalidate**（表示後にバックグラウンド更新）
- 検索は H2 インデックスを優先、不足分は FS 走査でキャッシュを充実
- サイズ・更新日時を常時表示（ファイルのみ stat 取得）
- UNC パス (`\\server\share\folder`) をそのまま入力可能
- **FlatLaf** によるモダンな Swing UI
- バックグラウンドスレッドで一覧取得（UI をブロックしない）

## 必要環境

- Java 17 以上
- Maven 3.9+

## ビルド & 実行

```powershell
cd fast-explorer
mvn package
java -jar target/FastExplorer.jar
```

開発中にすぐ起動する場合:

```powershell
mvn compile exec:java
```

## exe 化 (jpackage)

Java 17 付属の `jpackage` でネイティブ exe を作成できます。

```powershell
mvn package

jpackage `
  --input target `
  --name "Fast Explorer" `
  --main-jar FastExplorer.jar `
  --main-class com.fastexplorer.FastExplorerApp `
  --type app-image `
  --dest release
```

出力: `release\Fast Explorer\Fast Explorer.exe`

インストーラ形式が必要な場合:

```powershell
jpackage --input target --name "Fast Explorer" --main-jar FastExplorer.jar --main-class com.fastexplorer.FastExplorerApp --type exe --dest release
```

## 使い方

1. アドレスバーに `\\fileserver\share` や `C:\Users\...` を入力して **移動**
2. フォルダをダブルクリックで移動、ファイルをダブルクリックで関連アプリで開く
3. **フォルダ**（青）/ **テキストファイル**（緑）/ **バイナリファイル**（茶）で色分け表示
4. **検索** 欄で現在のフォルダを即時絞り込み（入力するだけ）
5. **サブフォルダも検索** を ON にして Enter / 検索ボタンで再帰検索（ネットワーク越しは時間がかかる場合あり）
6. **Esc** または **✕** で検索をクリア
7. **↻ 更新** でキャッシュを無視してネットワークから再取得
8. **Grep** 欄でテキストファイル内を検索。**パス** / **ファイル** (`*.java`) / **拡張子** (`.java,.log`) で対象を絞り込み
9. 検索・Grep の結果は **現在のタブ** に追加表示される
10. **追加先** で「新規タブ」または既存の「タブ１」「タブ２」…を選び、**別タブに追加** で結果をコピーできる
11. 結果タブを選んで Grep を実行すると、そのタブ内のファイルだけを対象にさらに Grep できる

## H2 キャッシュ

| 項目 | 内容 |
|------|------|
| 保存場所 | `%USERPROFILE%\.fast-explorer\cache.mv.db` |
| 鮮度 (FRESH) | 2 分以内 → キャッシュのみ使用 |
| 猶予 (STALE) | 30 分以内 → キャッシュ表示 + バックグラウンド更新 |
| 強制更新 | ↻ ボタンでキャッシュ無効化 |

ステータスバーに `H2 キャッシュ` / `ネットワーク` / `更新完了` が表示されます。

## 技術スタック

- Java 17
- Swing + [FlatLaf](https://www.formdev.com/flatlaf/) 3.5
- [H2 Database](https://www.h2database.com/) 2.2（組み込みキャッシュ）
- Maven (shade plugin で fat JAR)

## ライセンス

MIT
