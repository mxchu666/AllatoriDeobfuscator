<p align="center">
  <img src="assets/ss.png" alt="Skid Solutions Banner" width="350" />
  <br>
  <font size="6"><b>OpenAllatori</b></font>
  <br>
  <font size="3">by Skid Solutions</font>
</p>

---

<p align="center">
  <a href="https://discord.gg/AEwatvnvSf">https://discord.gg/AEwatvnvSf</a>
</p>

---

[English](README.md) | [Español](README.es.md) | **日本語** | [中文](README.zh.md) | [Русский](README.ru.md) | [Български](README.bg.md) | [Magyar](README.hu.md)

---

# ビルド方法

## 前提条件
- **JDK 17 以上** — Gradle を実行する JDK でそのままビルドできます（JDK 21 や JDK 25 も含む）。生成されるバイトコードは Java 17 をターゲットにします（`--release 17`）。ツールチェーンの固定はありません。

## OpenAllatori のビルド

このフォルダから実行します:

```powershell
.\gradlew.bat clean build
```

`BUILD SUCCESSFUL` が出れば成功です。`build/libs/` に生成されます:
- `OpenAllatori-9.9.jar` — オブファスケーター **と** デオブファスケーター
- `OpenAllatori-9.9-sources.jar`

---

# オブファスケーターの使い方

元の Allatori と同じく、XML 設定ファイルで動かします。

```powershell
java -jar build\libs\OpenAllatori-9.9.jar my-config.xml
```

最も重要なプロパティは **ログファイル** です。オブファスケーターはすべてのリネームをここに書き込み、このファイルこそが後からビルドを元に戻せる理由です:

```xml
<property name="log-file" value="out/mapping.xml"/>
```

# デオブファスケーターの使い方

```powershell
java -cp build\libs\OpenAllatori-9.9.jar com.allatori.deobfuscate.Deobfuscate <コマンド> ...
```

| コマンド | 引数 | 機能 |
|---|---|---|
| `full` | `<mapping.xml> <in.jar> <out.jar> [--remove-decryptor]` | 文字列の復号、デコーダーの削除、全名前の復元 — この順で実行 |
| `rename-back` | `<mapping.xml> <in.jar> <out.jar>` | マッピングから元のクラス/メソッド/フィールド名を復元 |
| `decrypt-strings` | `<in.jar> <out.jar> [--remove-decryptor]` | 暗号化された文字列を平文に置き換え |
| `restore-stacktrace` | `<mapping.xml> <in.txt> <out.txt>` | オファスケート済みスタックトレースを元の名前/行番号に書き戻し |

## デモのラウンドトリップ

```powershell
.\gradlew.bat runDemoRoundTrip
```

小さなデモアプリをコンパイルし、オファスケートし、デオファスケートし、元の・オファスケート済み・デオファスケート済みの 3 つの JAR を実行します。検証済みの結果: デオファスケート済み JAR は元の JAR と **バイト単位で同じ出力** を出し、**同一のスタックトレース**（`at com.example.demo.App.crash(App.java:31)`）を生成し、`javap -c` はすべてのクラスを問題なく逆アセンブルします。

## （任意）Recaf による追加クリーンアップ

さらにきれいな結果が必要な場合は、デオブファスケート済み JAR に対して [Recaf 4.x](https://github.com/Col-E/Recaf) と RecafMCP プラグインによるオプションのクリーンアップを実行できます:

1. RecafMCP プラグインをロードして Recaf を起動します（ブリッジサーバーは `localhost:9847`）。
2. デオブファスケート済み JAR を開きます。
3. `generic_all` トランスフォーマープリセットを複数パスで適用します（5 パスで十分）。
4. JAR をエクスポートします。

効果: 文字列復号の呼び出し書き換えで残る `nop` のペアを削除し、定数を返すだけの静的メソッドを `ldc` 定数に畳み込み、デッドオフセットを圧縮します（`generic_all` プリセットには 23 個のトランスフォーマーがあります）。デモのラウンドトリップで検証済み: 実行出力とスタックトレースはバイト単位で同一のまま、`javap -c` もすべてのクラスを問題なく逆アセンブルします。

---

# デオブファスケーターの仕組み

デオブファスケーターは、オブファスケーターの可逆部分の正確な逆操作です。定数プールおよび命令レベルで動作し、オブファスケーター自身と同じ BCEL モデルを使用するため、書き戻された内容は JVM が期待するものと完全に一致します。

4つのメカニズムが、`full` パイプラインによりこの順序で実行されます。

**1. 文字列復号（`DecryptorSimulator`）。** オブファスケーターはすべての文字列定数を `ldc "暗号文"` + `invokestatic DecryptString` に置き換えます。デオブファスケーターは命令プロファイル（XORループ、マスク63のAND、char配列構築、`charAt`）で候補の復号メソッドを見つけ、そして素朴なツールが決してしないことを行います。生成された復号メソッドのバイトコードを小さなスタックマシンインタプリタで **実際に実行** します。アルゴリズムは問いません。Allatori が生成したどんな演算（シード導出、連鎖XOR鍵、マスク）でも、インタプリタがそれを実行して平文を返します。呼び出し箇所は `ldc "平文"` + `nop` に書き換えられ、復号クラスを参照する生きている命令がなくなればそのクラスは削除されます。実行時の値に依存する復号器は推測せず「暗号化のまま残った」と報告されます。

**2. 名前の復元（`UnrenameTransformer`）。** マッピングXML（`<class old=... new=...>` と `<method old="名前(記述子)" new="n"/>`、`<field old="名前 型" new="n"/>`）に基づき、定数プールレベルの逆リネームを行います。`this_class`、`super_class`、インターフェースを復元し、すべてのメソッドとフィールドの宣言を元の名前に戻し、参照（Methodref、Fieldref、InterfaceMethodref、そのクラスオーナーと NameAndType エントリ）をすべて書き換えます。同一パス内で既に書き換え済みのオーナーも処理されます。JAR のエントリは復元されたクラス名で再キー化されるため、元のパッケージ構成が再現されます。

**3. StackMapTable の再マッピング（`StackMapRemapper`）。** 文字列の復号はコードを縮めます（`invokestatic` 3バイト → `nop` 1バイト）。StackMapTable 属性は再エンコード時にそのまま引き継がれるため、フレームが旧レイアウトを指し続け、JVM ベリファイア（や `javap`）がクラスを拒否してしまいます。リマッパーは各フレームのオフセットを、新しいレイアウトの同じ命令を指すように書き直します。`ITEM_Uninitialized` 検証エントリ内の命令オフセットも同様に処理します。

**4. スタックトレース復元（`restore-stacktrace`）。** 同じマッピングXML（メソッドエントリと、暗号化された行番号の `<lines>` リスト）を読み込み、取得済みのスタックトレースを行単位で書き直します。`at com.example.demo.h.K.K(K.java:7)` は `at com.example.demo.App.crash(App.java:31)` になります。

**正直な限界。** 名前の完全復元にはマッピングファイルが必要です。それがない場合、オブファスケーションは設計上元に戻せないため、マッピング不要の文字列復号だけが機能します。それが正しい 1:1 動作です。オブファスケーターが書いたログがあれば、変更されたすべてが復元され、なければ情報損失なしで復元できる部分だけが復元されます。

---

# プロジェクト構成

```
src/main/java/com/allatori/          オブファスケーター（オリジナルの再実装）
src/main/java/com/allatori/deobfuscate/   デオブファスケーター
  Deobfuscate.java                   CLI + パイプライン制御
  MappingData.java                   オブファスケーターのログ XML を解析
  UnrenameTransformer.java           定数プールレベルの逆リネーム
  StringDecryptorTransformer.java    デコーダー検出 + 呼び出し箇所の書き換え
  DecryptorSimulator.java            デコーダーのバイトコードを実行する小型スタックマシン
  StackMapRemapper.java              編集後の StackMapTable オフセット再マッピング
demo/                                Gradle タスクが使うデモアプリ + 設定
tools/                               診断ユーティリティ（DumpMain）
```

# トラブルシューティング

- PowerShell は `java`/`gradlew` のコンソール出力に対して `NativeCommandError` のノイズを出します。終了コードではなく `BUILD SUCCESSFUL` を確認してください。
- 値付きのシステムプロパティは引用符で囲みます: `java "-Dallatori.deobfuscate.trace=true" ...`
- 文字列復号のデバッグ診断: `-Dallatori.deobfuscate.debug=true`（概要）と `-Dallatori.deobfuscate.trace=true`（`DecryptorSimulator` のオペコード単位のトレース）。
