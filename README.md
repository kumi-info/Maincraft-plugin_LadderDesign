# LadderDesign

**バージョン: v1.9.0**

梯子（s2e-ladder の登る塔）の**背面ブロックをテーマ／カラフルに塗り替える**コマンドプラグインです。TikTok 妨害配信の Ladder サーバー向け。配信の雰囲気を一発で変えられます。**スタート地点の 16×16 足場（赤コンクリート）の再生成**にも対応（配信で床が破壊されたとき用）。

---

## できること

- `/ladderdesign <番号>` 一発で梯子の見た目を切り替え（**10パターン**）。
- **連携先設定の `arena` を優先**して座標・高さを自動取得。無ければ s2e-ladder（`ladder.start`/`ladder.height`）へフォールバックするので、座標指定は不要（LadderTop v1.4.0 と同方式）。
- **はしご(LADDER)・ツル・空気は塗らずに残す**ので、塗り替えても登坂プレイに影響しません（壁ブロックの素材／色だけ変わります）。
- カラフル系は**高さ方向にグラデーション**で色が変わります（既定 **4マスごと**。カラフルガラス／コンクリートは **3マスごと**。パターンごとに `band` で個別指定可）。
- `/ladderdesign floor` で **16×16 の足場（赤コンクリート）を再生成**。配信で床が壊されても一発で復元。デザイン切替時にも自動で足場を敷きます（`floor.apply-with-design`）。
- `/ladderdesign barrier` で **start を中心に 20×20 の外周に透明バリア（BARRIER）の壁を高さ1040で生成**。内側は空けるので登坂に影響しません。撤去は `/ladderdesign barrier clear`。**BARRIER は爆破耐性が岩盤と同じなので TNT では壊れません。**
- `/ladderdesign top` で **頂上（ゴール）の床を生成**。既定 6×7・絶対 Y=`top.y`(既定981) に敷きます（`top.y: 0` なら `ladder.height` を使用）。
- `/ladderdesign <番号>`（デザイン適用）時に、**足場・バリア・頂上もセットで自動更新**されます（`floor` / `barrier` / `top` の `apply-with-design`）。透明バリアを見たい時は `/give @p minecraft:barrier` でバリアアイテムを持つと表示されます。

---

## コマンド

```
/ladderdesign <1-10|list|random|floor|barrier|top|reload>
```

| 引数 | 動作 |
|---|---|
| `1`〜`10` | その番号のパターンを適用 |
| `list` | パターン一覧を表示 |
| `random` | ランダムなパターンを適用 |
| `floor` | 足場（16×16 赤コンクリート）を再生成 |
| `barrier` | 透明バリア（外周20×20・高さ1040）を生成 |
| `barrier clear` | 透明バリアを撤去 |
| `top` | 頂上ゴール床（6×7・Y=981）を生成 |
| `top <Y>` | 指定した絶対 Y に頂上ゴール床を生成 |
| `top clear [Y]` | 頂上ゴール床を撤去（Y 省略時は `top.y`） |
| `reload` | `config.yml` を再読み込み |

- 権限: `ladderdesign.use`（デフォルト op）
- タブ補完: 1〜10 / list / random / floor / barrier / top / reload
- `floor` のエイリアス: `base` / `platform` / `ashiba`
- `barrier` のエイリアス: `wall` / `cage` / `kabe`
- `top` のエイリアス: `summit` / `goal` / `chojo`
- ガチャ／コンソールからも `ladderdesign 5` のように呼べます。

### デフォルトの10パターン

| 番号 | 名前 | 内容 | 色替え間隔 |
|---|---|---|---|
| 1 | 木 | オークの板材 | 単色 |
| 2 | 石英(白) | クォーツブロック | 単色 |
| 3 | ネザー(赤) | ネザーレンガ | 単色 |
| 4 | エンド(紫) | プルプァブロック | 単色 |
| 5 | カラフルガラス | 染色ガラス8色 | **3マス** |
| 6 | カラフルコンクリート | コンクリート8色 | **3マス** |
| 7 | レインボーウール | 羊毛11色 | 4マス |
| 8 | レインボーテラコッタ | テラコッタ11色 | 4マス |
| 9 | 海(プリズマリン) | プリズマリン系＋シーランタン | 4マス |
| 10 | 砂漠(砂岩) | 砂岩・赤砂岩5種 | 4マス |

> カラフル系は既定 `band-height`（4）マスごとに切り替わりますが、パターンに `band: 3` を書くとそのパターンだけ3マスごとになります（カラフルガラス／コンクリートが該当）。

---

## 仕組み

- **範囲取得**: `連携先の config.yml` の `arena`(world/x/y/z/height) を優先して読み、無ければ `plugins/s2e-ladder/config.yml` の `ladder.start`(world/x/y/z) と `ladder.height` へフォールバック。基点の真上の柱を対象にします。ゴール面の絶対 Y は 連携先 では `floor(arena.y) + arena.height - 1`、s2e-ladder では `ladder.height`（絶対 Y、従来どおり）。コマンド実行時にどちらを読んだかをサーバーログに出力します。
- **塗り替え対象**: 柱（既定 3×3）の中で `isSolid()` の固体ブロックのみを置換。`LADDER`・`VINE`・`SCAFFOLDING`・空気などは `isSolid()==false` なので自動的に除外されます。`keep-blocks`（既定 `BEDROCK`）も保護します。
- **グラデーション**: `blocks` が複数のパターンは、`band-height`（既定4ブロック）ごとに次の色へ切り替わります。各パターンに `band: N` を書くと、そのパターンだけ N マスごとに切り替えできます（カラフルガラス／コンクリートは `band: 3`）。
- **足場（床）**: `/ladderdesign floor` は start 座標を中心に `floor.size-x`×`floor.size-z`（既定 16×16）の床を `floor.material`（既定 `RED_CONCRETE`）で敷きます。高さは `floor.y-offset`（既定 -1＝スタートの1つ下）。デザイン切替時も `floor.apply-with-design: true` なら自動で敷き直します。床がズレる場合は `floor.center-x-offset` / `center-z-offset` / `y-offset` を調整して `/ladderdesign reload`。
- **透明バリア（壁）**: `/ladderdesign barrier` は start を中心に `barrier.size-x`×`barrier.size-z`（既定 20×20）の**外周セルのみ**に `barrier.material`（既定 `BARRIER`）を `barrier.height`（既定 1040）段積みます。開始高さは `barrier.y-offset`（既定 0＝start.y）。内側は空けるので梯子・登坂に影響しません。撤去は `/ladderdesign barrier clear`（外周の BARRIER だけを空気に戻す）。位置ズレは `barrier.center-x-offset` / `center-z-offset` で調整。**BARRIER は爆破耐性が岩盤と同じため TNT で破壊されません。**
- **頂上（ゴール）**: `/ladderdesign top` は `top.material`（既定 `RED_CONCRETE`）で `top.size-x`×`top.size-z`（既定 6×7）の床を、**絶対 Y=`top.y`＋`top.y-offset`（既定981）** に敷きます。`top.y: 0` のときは `ladder.height` を使用。Y は start.y を加算しない絶対値です。中心は `top.center-x-offset`（既定 -3）／`top.center-z-offset`（既定 -1）で調整。**ワールドの建築上限を超える Y は設置できません（0ブロックの場合は警告を表示）。**
- **デザインと連動**: `/ladderdesign <番号>` / `random` を実行すると、`floor` / `barrier` / `top` の `apply-with-design` が `true` の場合に**足場・バリア・頂上も同時に再生成**されます（壊された床・壁・ゴールごと一括リセット）。

---

## config.yml（抜粋）

```yaml
scan:
  half-width: 1     # 柱の太さ（1=3ブロック幅）
  half-depth: 1
  y-offset: 0       # start.y からの開始オフセット（地面を避けるなら +2 等）
  y-top-extra: 0    # 高さに足す余白
band-height: 4      # カラフル系で1色が続く高さ（3〜5 推奨）
keep-blocks: [BEDROCK]
floor:              # 足場（床）の再生成設定
  material: RED_CONCRETE
  size-x: 16
  size-z: 16
  center-x-offset: 0
  center-z-offset: 0
  y-offset: -1      # start.y からのオフセット（-1=スタートの1つ下）
  layers: 1         # 厚み（下方向へ重ねる枚数）
  apply-with-design: true  # デザイン切替時も自動で足場を敷く
barrier:            # 透明バリア（外周の壁）設定（BARRIER はTNTで壊れない）
  material: BARRIER
  size-x: 20        # バリアの外周サイズ
  size-z: 20
  center-x-offset: 0
  center-z-offset: 0
  height: 1040      # 壁の高さ（ブロック数）
  y-offset: 0       # 壁の開始高さ（start.y からのオフセット）
  apply-with-design: true  # デザイン切替時もバリアを張り直す
top:                # 頂上（ゴール）プラットフォーム設定
  material: RED_CONCRETE
  size-x: 7
  size-z: 7
  center-x-offset: -3  # ゴール中央へ寄せる（start は端）
  center-z-offset: -1  # Z方向のずらし（後ろへ1マス）
  y: 981            # ゴール床の絶対 Y（0 なら ladder.height を使用）
  y-offset: 0       # 上の絶対 Y からのオフセット
  layers: 1
  apply-with-design: true  # デザイン切替時も頂上を生成
patterns:
  - name: "木"
    blocks: [OAK_PLANKS]
  - name: "カラフルコンクリート"
    band: 3                 # このパターンだけ3マスごとに色替え
    blocks: [RED_CONCRETE, ORANGE_CONCRETE, ...]
  # ... 複数ブロックを並べるとカラフル（band か band-height ごとに色替え）
```

- パターンは自由に追加・編集できます（番号は上からの並び順）。
- `blocks` のブロック名は Minecraft の Material 名（例 `OAK_PLANKS`, `BLUE_CONCRETE`）。

---

## インストール / 反映

1. ビルドした `LadderDesign_v1.6.0.jar` を `plugins/` に配置。
2. サーバー再起動（または `/reload confirm`）で有効化。`config.yml` / `README.md` は初回起動時に自動生成。

ビルドは [minecraft-build-env] のオフライン手順。

---

## 注意

- 柱が太い梯子の場合は `scan.half-width` / `half-depth` を上げてください。逆に周囲を巻き込む場合は下げます。
- `y-offset` を 0 にすると start 直下の地面も塗り替え対象になることがあります。地面を残したい場合は `+2` 等にしてください。
- 一度塗ると元のブロックには戻せません（別パターンで上書きは可能）。

---

## 更新履歴

| バージョン | 変更点 |
|---|---|
| v1.9.0 | 連携対応強化。座標を `連携先の config.yml` の `arena` から優先取得し、無ければ s2e-ladder へフォールバック（LadderTop v1.4.0 と同方式）。`softdepend: [s2e-ladder]` を追加。`top.y: 0` 時のゴール Y は 連携先 では `floor(arena.y)+arena.height-1`。塗り替え上端をゴール面の絶対 Y 基準に変更。 |
| v1.8.0 | `/ladder delete` を検知して、LadderDesign が作った透明バリアと頂上ゴール床を自動撤去する `clear-on-ladder-delete`（既定ON）を追加。 |
| v1.7.2 | 頂上ゴールの撤去 `/ladderdesign top clear [Y]`、絶対Y指定生成 `/ladderdesign top <Y>` を追加。 |
| v1.7.1 | 頂上ゴールの既定を実ゴールに合わせて調整（6×7・絶対 Y=981・`center-z-offset` -1 で Z方向へ1マス後ろ）。 |
| v1.7.0 | 頂上（ゴール）の生成 Y を絶対値で指定できる `top.y`（既定10000・0で `ladder.height`）を追加。生成が0ブロックのとき建築上限超過の警告を表示。 |
| v1.6.0 | 透明バリアを20×20に変更。頂上（ゴール）生成 `/ladderdesign top`（既定7×7・Y=ladder.height・center-x -3）を追加し、デザイン切替時も自動生成（`top.apply-with-design`）。BARRIERはTNT耐性ありを明記。 |
| v1.5.0 | パターンを10種に整理。パターンごとに色替え間隔を指定できる `band` を追加し、カラフルガラス／コンクリートを 3マスごとの色替えに設定。 |
| v1.4.0 | バリアを足場と独立した専用サイズ（既定17×17・`barrier.size-x/z`・`center-x/z-offset`）に変更。`/ladderdesign <番号>` 実行時に足場＋バリアもセットで自動更新（`barrier.apply-with-design`）。 |
| v1.3.0 | パターンを6→18種に拡充（レインボー羊毛/テラコッタ、彩釉、ネオン、モノクロ、木材ミックス、海、砂漠、銅、アメジスト、雪氷、ネザー等）。`band-height` を 8→4 に変更し、3〜5マスごとに細かく色が変わるデザインに。 |
| v1.2.0 | 透明バリア（外周の壁）生成機能を追加。`/ladderdesign barrier` で足場と同じ16×16範囲の外周に BARRIER 壁を高さ1040で生成、`barrier clear` で撤去。`barrier.*` 設定を追加。 |
| v1.1.0 | 足場（床）再生成機能を追加。`/ladderdesign floor` で start 中心の 16×16 赤コンクリート床を復元。`floor.*` 設定を追加し、デザイン切替時の自動敷設（`apply-with-design`）にも対応。 |
| v1.0.0 | 初版。`/ladderdesign <1-6|list|random|reload>`＝s2e-ladder の柱を6パターン（木/石英/ネザー/エンド/カラフルガラス/カラフルコンクリート）で塗り替え。はしご・空気は保護。 |

---

> 📌 **メンテナンス方針**: 機能を変更してバージョンを上げたときは、必ず本 README の「バージョン」表記・コマンド表・更新履歴も合わせて更新すること。
