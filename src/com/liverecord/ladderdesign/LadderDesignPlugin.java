package com.liverecord.ladderdesign;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 梯子（登る塔）の背面ブロックを、テーマ/カラフルに塗り替えるコマンドプラグイン。
 *
 * <p>コマンド: {@code /ladderdesign <1-N|list|random|floor|barrier|top|reload>}
 *
 * <ul>
 *   <li>座標ソースは 連携先設定(config.yml の arena)を優先し、
 *       無ければ s2e-ladder(plugins/s2e-ladder/config.yml の ladder.start/height)へ
 *       フォールバックする（LadderTop v1.4.0 と同方式）。</li>
 *   <li>ゴール面の絶対 Y:
 *       連携先 は {@code floor(arena.y) + arena.height - 1}、
 *       s2e-ladder は {@code ladder.height}（従来どおり絶対 Y）。</li>
 *   <li>基点の真上の柱（既定 3x3）を、上から下まで走査して塗り替える。</li>
 *   <li>はしご(LADDER)・ツル・空気など「登れる/通れるもの」は isSolid()==false なので塗らずに残す
 *       （壁ブロックだけ色/素材が変わる）。</li>
 *   <li>カラフル系（複数ブロック指定）は band-height ごとに色を切り替えてグラデーションにする。</li>
 * </ul>
 *
 * @version 1.9.0
 * @author LiveRecord
 */
public final class LadderDesignPlugin extends JavaPlugin implements TabExecutor, Listener {

    /** /ladder delete 後にバリア・ゴール床を撤去するまでの遅延 tick 数。 */
    private static final long LADDER_DELETE_DELAY_TICKS = 2L;

    /** デフォルトの梯子高さ（連携先 config に height がない場合）。 */
    private static final int DEFAULT_LADDER_HEIGHT = 1000;

    /** 連携先プラグイン名(優先)。空文字なら未使用。 */
    private static final String NEO_PLUGIN = "";

    /** 連携先プラグイン名(フォールバック): s2e-ladder。 */
    private static final String S2E_PLUGIN = "s2e-ladder";

    private final List<Pattern> patterns = new ArrayList<>();

    /** /ladder delete 時にバリア+ゴールを自動撤去するか（config: clear-on-ladder-delete）。 */
    private boolean clearOnLadderDelete;

    // 柱の走査範囲。
    private int halfWidth;
    private int halfDepth;
    private int yOffset;
    private int yTopExtra;
    private int bandHeight;
    private Set<Material> keep = EnumSet.noneOf(Material.class);

    // 足場（床）設定。
    private Material floorMaterial;
    private int floorSizeX;
    private int floorSizeZ;
    private int floorCenterXOffset;
    private int floorCenterZOffset;
    private int floorYOffset;
    private int floorLayers;
    private boolean applyFloorWithDesign;

    // 透明バリア（外周の壁）設定。
    private Material barrierMaterial;
    private int barrierHeight;
    private int barrierYOffset;
    private int barrierSizeX;
    private int barrierSizeZ;
    private int barrierCenterXOffset;
    private int barrierCenterZOffset;
    private boolean applyBarrierWithDesign;

    // 頂上（ゴール）プラットフォーム設定。
    private Material topMaterial;
    private int topSizeX;
    private int topSizeZ;
    private int topCenterXOffset;
    private int topCenterZOffset;
    private int topAbsY;
    private int topYOffset;
    private int topLayers;
    private boolean applyTopWithDesign;

    /**
     * プラグイン有効化時の初期化処理。
     * 設定読込・コマンド登録・イベントリスナー登録を行う。
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getResource("README.md") != null) {
            saveResource("README.md", true);
        }
        loadSettings();

        if (getCommand("ladderdesign") != null) {
            getCommand("ladderdesign").setExecutor(this);
            getCommand("ladderdesign").setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        removeNamespacedAliases();

        getLogger().info("LadderDesign 有効化。/ladderdesign <1-" + patterns.size()
                + "|list|random|reload> が利用可能。"
                + "（/ladder delete 連動撤去: " + (clearOnLadderDelete ? "ON" : "OFF") + "）");
    }

    /** タブ補完に出る「プラグイン名:コマンド名」形式の名前空間エイリアスをコマンドマップから削除する。 */
    @SuppressWarnings("unchecked")
    private void removeNamespacedAliases() {
        try {
            Object server = getServer();
            java.lang.reflect.Method getMap = server.getClass().getMethod("getCommandMap");
            getMap.setAccessible(true);
            Object commandMap = getMap.invoke(server);

            java.lang.reflect.Field f = null;
            for (Class<?> c = commandMap.getClass(); c != null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField("knownCommands"); break; } catch (NoSuchFieldException ignored) {}
            }
            if (f == null) {
                getLogger().warning("[alias] knownCommands フィールドが見つかりません: " + commandMap.getClass().getName());
                return;
            }
            f.setAccessible(true);
            java.util.Map<String, ?> known = (java.util.Map<String, ?>) f.get(commandMap);
            String prefix = getName().toLowerCase(java.util.Locale.ROOT) + ":";
            boolean removed = known.keySet().removeIf(k -> k.startsWith(prefix));
            getLogger().info("[alias] " + prefix + "* の名前空間エイリアス削除: " + (removed ? "成功" : "キーなし"));

            try {
                java.lang.reflect.Method sync = server.getClass().getMethod("syncCommands");
                sync.setAccessible(true);
                sync.invoke(server);
            } catch (Exception e) {
                getLogger().warning("[alias] syncCommands 失敗: " + e.getMessage());
            }
        } catch (Exception e) {
            getLogger().warning("[alias] 名前空間エイリアス削除失敗: " + e);
        }
    }

    /**
     * config.yml から全設定を読み込む。
     * onEnable 時および /ladderdesign reload 時に呼ばれる。
     */
    private void loadSettings() {
        reloadConfig();
        loadScanSettings();
        loadFloorSettings();
        loadBarrierSettings();
        loadTopSettings();
        clearOnLadderDelete = getConfig().getBoolean("clear-on-ladder-delete", true);
        loadPatterns();
    }

    /** 柱の走査範囲に関する設定を読み込む。 */
    private void loadScanSettings() {
        halfWidth = Math.max(0, getConfig().getInt("scan.half-width", 1));
        halfDepth = Math.max(0, getConfig().getInt("scan.half-depth", 1));
        yOffset = getConfig().getInt("scan.y-offset", 0);
        yTopExtra = getConfig().getInt("scan.y-top-extra", 0);
        bandHeight = Math.max(1, getConfig().getInt("band-height", 4));

        keep = EnumSet.noneOf(Material.class);
        for (final String s : getConfig().getStringList("keep-blocks")) {
            final Material m = Material.matchMaterial(s);
            if (m != null) {
                keep.add(m);
            }
        }
    }

    /** 足場（床）に関する設定を読み込む。 */
    private void loadFloorSettings() {
        floorMaterial = parseMaterialOrDefault(
                getConfig().getString("floor.material", "RED_CONCRETE"),
                Material.RED_CONCRETE);
        floorSizeX = Math.max(1, getConfig().getInt("floor.size-x", 16));
        floorSizeZ = Math.max(1, getConfig().getInt("floor.size-z", 16));
        floorCenterXOffset = getConfig().getInt("floor.center-x-offset", 0);
        floorCenterZOffset = getConfig().getInt("floor.center-z-offset", 0);
        floorYOffset = getConfig().getInt("floor.y-offset", -1);
        floorLayers = Math.max(1, getConfig().getInt("floor.layers", 1));
        applyFloorWithDesign = getConfig().getBoolean("floor.apply-with-design", true);
    }

    /** 透明バリア（外周の壁）に関する設定を読み込む。 */
    private void loadBarrierSettings() {
        barrierMaterial = parseMaterialOrDefault(
                getConfig().getString("barrier.material", "BARRIER"),
                Material.BARRIER);
        barrierHeight = Math.max(1, getConfig().getInt("barrier.height", 1040));
        barrierYOffset = getConfig().getInt("barrier.y-offset", 0);
        barrierSizeX = Math.max(2, getConfig().getInt("barrier.size-x", 20));
        barrierSizeZ = Math.max(2, getConfig().getInt("barrier.size-z", 20));
        barrierCenterXOffset = getConfig().getInt("barrier.center-x-offset", 0);
        barrierCenterZOffset = getConfig().getInt("barrier.center-z-offset", 0);
        applyBarrierWithDesign = getConfig().getBoolean("barrier.apply-with-design", true);
    }

    /** 頂上（ゴール）プラットフォームに関する設定を読み込む。 */
    private void loadTopSettings() {
        topMaterial = parseMaterialOrDefault(
                getConfig().getString("top.material", "RED_CONCRETE"),
                Material.RED_CONCRETE);
        topSizeX = Math.max(1, getConfig().getInt("top.size-x", 7));
        topSizeZ = Math.max(1, getConfig().getInt("top.size-z", 7));
        topCenterXOffset = getConfig().getInt("top.center-x-offset", -3);
        topCenterZOffset = getConfig().getInt("top.center-z-offset", 0);
        topAbsY = getConfig().getInt("top.y", 0);
        topYOffset = getConfig().getInt("top.y-offset", 0);
        topLayers = Math.max(1, getConfig().getInt("top.layers", 1));
        applyTopWithDesign = getConfig().getBoolean("top.apply-with-design", true);
    }

    /** config.yml の patterns セクションからパターンを読み込む。空の場合はデフォルトを登録。 */
    private void loadPatterns() {
        patterns.clear();
        for (final Map<?, ?> m : getConfig().getMapList("patterns")) {
            final Object nameO = m.get("name");
            final Object blocksO = m.get("blocks");
            final Object bandO = m.get("band");
            final String name = (nameO != null) ? nameO.toString() : "?";
            final int band = (bandO instanceof Number) ? ((Number) bandO).intValue() : 0;
            final List<Material> blocks = new ArrayList<>();
            if (blocksO instanceof List) {
                for (final Object o : (List<?>) blocksO) {
                    final Material mat = Material.matchMaterial(o.toString());
                    if (mat != null) {
                        blocks.add(mat);
                    } else {
                        getLogger().warning("パターン '" + name + "' の不明なブロック名: " + o);
                    }
                }
            }
            if (!blocks.isEmpty()) {
                patterns.add(new Pattern(name, blocks, band));
            }
        }
        if (patterns.isEmpty()) {
            registerDefaultPatterns();
        }
    }

    /** config.yml にパターンが無い場合に登録するデフォルトパターン。 */
    private void registerDefaultPatterns() {
        patterns.add(new Pattern("木", List.of(Material.OAK_PLANKS), 0));
        patterns.add(new Pattern("石英", List.of(Material.QUARTZ_BLOCK), 0));
        patterns.add(new Pattern("ネザー", List.of(Material.NETHER_BRICKS), 0));
        patterns.add(new Pattern("エンド", List.of(Material.PURPUR_BLOCK), 0));
        patterns.add(new Pattern("カラフルガラス", List.of(
                Material.RED_STAINED_GLASS, Material.ORANGE_STAINED_GLASS,
                Material.YELLOW_STAINED_GLASS, Material.LIME_STAINED_GLASS,
                Material.LIGHT_BLUE_STAINED_GLASS, Material.BLUE_STAINED_GLASS,
                Material.PURPLE_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS), 3));
        patterns.add(new Pattern("カラフルコンクリート", List.of(
                Material.RED_CONCRETE, Material.ORANGE_CONCRETE,
                Material.YELLOW_CONCRETE, Material.LIME_CONCRETE,
                Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE,
                Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE), 3));
    }

    /**
     * /ladderdesign コマンドのメイン処理。
     *
     * @param sender コマンド送信者
     * @param command コマンドオブジェクト
     * @param label コマンドラベル
     * @param args コマンド引数
     * @return コマンドが処理されたか
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ladderdesign")) {
            return false;
        }
        if (!sender.hasPermission("ladderdesign.use")) {
            sender.sendMessage("§cこのコマンドを使う権限がありません。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e使い方: §f/ladderdesign <1-" + patterns.size()
                    + "|list|random|reload>");
            listPatterns(sender);
            return true;
        }

        final String arg = args[0].toLowerCase(Locale.ROOT);
        switch (arg) {
            case "reload":
                return handleReload(sender);
            case "list":
                listPatterns(sender);
                return true;
            case "floor": case "base": case "platform": case "ashiba":
                return handleFloor(sender);
            case "barrier": case "wall": case "cage": case "kabe":
                return handleBarrier(sender, args);
            case "top": case "summit": case "goal": case "chojo":
                return handleTop(sender, args);
            default:
                return handleDesignApply(sender, arg);
        }
    }

    /**
     * reload サブコマンドを処理する。
     *
     * @param sender コマンド送信者
     * @return 常に true
     */
    private boolean handleReload(CommandSender sender) {
        loadSettings();
        sender.sendMessage("§aconfig.yml を再読み込みしました。（パターン "
                + patterns.size() + " 件）");
        return true;
    }

    /**
     * floor サブコマンドを処理する。
     *
     * @param sender コマンド送信者
     * @return 常に true
     */
    private boolean handleFloor(CommandSender sender) {
        final LadderInfo info = readLadder();
        if (info == null) {
            sendLadderReadError(sender);
            return true;
        }
        final int count = generateFloor(info);
        sender.sendMessage("§a足場（" + floorSizeX + "×" + floorSizeZ + " "
                + floorMaterial.name() + "）を生成しました。§7(" + count + " ブロック)");
        return true;
    }

    /**
     * barrier サブコマンドを処理する。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     * @return 常に true
     */
    private boolean handleBarrier(CommandSender sender, String[] args) {
        final LadderInfo info = readLadder();
        if (info == null) {
            sendLadderReadError(sender);
            return true;
        }
        final boolean clear = args.length >= 2 && isClearArg(args[1]);
        final long startTime = System.currentTimeMillis();
        final int count = clear ? clearBarrier(info) : generateBarrier(info);
        final long elapsed = System.currentTimeMillis() - startTime;
        if (clear) {
            sender.sendMessage("§a透明バリアを撤去しました。§7("
                    + count + " ブロック / " + elapsed + "ms)");
        } else {
            sender.sendMessage("§a透明バリア（外周 " + barrierSizeX + "×" + barrierSizeZ
                    + "・高さ " + barrierHeight + "）を生成しました。§7("
                    + count + " ブロック / " + elapsed + "ms)");
        }
        return true;
    }

    /**
     * top サブコマンドを処理する。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     * @return 常に true
     */
    private boolean handleTop(CommandSender sender, String[] args) {
        final LadderInfo info = readLadder();
        if (info == null) {
            sendLadderReadError(sender);
            return true;
        }
        final boolean clear = args.length >= 2 && isClearArg(args[1]);
        // 撤去時は第3引数、生成時は第2引数で Y を指定可能。
        final String yArg = clear ? (args.length >= 3 ? args[2] : null)
                : (args.length >= 2 ? args[1] : null);
        final int overrideY = parsePositiveIntOrZero(yArg);
        final int goalY = resolveTopY(info, overrideY);
        final int count = topPlatform(info, clear, overrideY);

        if (clear) {
            sender.sendMessage("§a頂上（" + topSizeX + "×" + topSizeZ
                    + " / Y=" + goalY + "）を撤去しました。§7(" + count + " ブロック)");
        } else {
            sender.sendMessage("§a頂上（" + topSizeX + "×" + topSizeZ + " "
                    + topMaterial.name() + " / Y=" + goalY
                    + "）を生成しました。§7(" + count + " ブロック)");
        }
        if (count == 0) {
            sender.sendMessage("§e※ 0ブロックでした。Y=" + goalY
                    + " の位置やサイズ・中心オフセットを確認してください。");
        }
        return true;
    }

    /**
     * デザインパターン適用を処理する（番号指定または random）。
     *
     * @param sender コマンド送信者
     * @param arg 第1引数（番号文字列または "random"）
     * @return 常に true
     */
    private boolean handleDesignApply(CommandSender sender, String arg) {
        final int index;
        if (arg.equals("random")) {
            index = ThreadLocalRandom.current().nextInt(patterns.size());
        } else {
            final Integer parsed = tryParseInt(arg);
            if (parsed == null || parsed < 1 || parsed > patterns.size()) {
                sender.sendMessage("§cパターン番号は 1〜" + patterns.size()
                        + " で指定してください（/ladderdesign list で一覧）。");
                return true;
            }
            index = parsed - 1;
        }

        final LadderInfo info = readLadder();
        if (info == null) {
            sendLadderReadError(sender);
            return true;
        }

        final Pattern p = patterns.get(index);
        final long startTime = System.currentTimeMillis();
        final int changed = applyPattern(p, info);
        final String extra = buildDesignExtras(info);
        final long elapsed = System.currentTimeMillis() - startTime;

        sender.sendMessage("§a梯子デザインを §e[" + (index + 1) + "] " + p.name
                + " §aに変更しました。§7(" + changed + " ブロック)" + extra
                + " §7/ " + elapsed + "ms");
        return true;
    }

    /**
     * デザイン適用時に連動生成する要素（足場・バリア・頂上）のメッセージを構築する。
     *
     * @param info 梯子情報
     * @return 連動生成の結果メッセージ（何もなければ空文字列）
     */
    private String buildDesignExtras(LadderInfo info) {
        final StringBuilder sb = new StringBuilder();
        if (applyFloorWithDesign) {
            sb.append(" §a+足場 §7").append(generateFloor(info));
        }
        if (applyBarrierWithDesign) {
            sb.append(" §a+バリア §7").append(generateBarrier(info));
        }
        if (applyTopWithDesign) {
            sb.append(" §a+頂上 §7").append(generateTop(info));
        }
        return sb.toString();
    }

    /**
     * パターン一覧を送信者に表示する。
     *
     * @param sender コマンド送信者
     */
    private void listPatterns(CommandSender sender) {
        sender.sendMessage("§6=== 梯子デザイン一覧 ===");
        for (int i = 0; i < patterns.size(); i++) {
            final Pattern p = patterns.get(i);
            final String kind = (p.blocks.size() > 1)
                    ? "§7(カラフル " + p.blocks.size() + "色)" : "";
            sender.sendMessage("§e" + (i + 1) + " §f" + p.name + " " + kind);
        }
    }

    /**
     * 指定パターンを梯子の柱へ適用し、塗り替えたブロック数を返す。
     * isSolid() == false のブロック（はしご・空気等）と keep-blocks は保護する。
     *
     * @param p 適用するパターン
     * @param info 梯子情報
     * @return 変更したブロック数
     */
    private int applyPattern(Pattern p, LadderInfo info) {
        final World w = info.world;
        final int baseY = Math.max(info.y + yOffset, w.getMinHeight());
        // ゴール面の絶対 Y まで塗る（y-top-extra で上方向の余白を追加可能）。
        final int topY = Math.min(info.goalY + yTopExtra, w.getMaxHeight() - 1);
        int changed = 0;
        for (int y = baseY; y <= topY; y++) {
            final int layer = y - baseY;
            final Material mat = p.blockFor(layer, bandHeight);
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                for (int dz = -halfDepth; dz <= halfDepth; dz++) {
                    final Block b = w.getBlockAt(info.x + dx, y, info.z + dz);
                    final Material cur = b.getType();
                    // 非固体ブロック（はしご・ツル・空気等）は登坂に必要なので残す。
                    if (!cur.isSolid()) {
                        continue;
                    }
                    if (keep.contains(cur) || cur == mat) {
                        continue;
                    }
                    b.setType(mat, false); // 物理更新オフで連鎖反応を防止。
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * 足場（床）を生成（再生成）する。start を中心に floor.size-x x floor.size-z の床を
     * floorMaterial で敷く。妨害配信で破壊された床の復元用。
     *
     * @param info 梯子情報
     * @return 設置（変更）したブロック数
     */
    private int generateFloor(LadderInfo info) {
        final World w = info.world;
        final int cx = info.x + floorCenterXOffset;
        final int cz = info.z + floorCenterZOffset;
        final int halfX = (floorSizeX - 1) / 2;
        final int halfZ = (floorSizeZ - 1) / 2;
        final int baseY = info.y + floorYOffset;
        final int minY = w.getMinHeight();
        final int maxY = w.getMaxHeight() - 1;
        int placed = 0;
        for (int layer = 0; layer < floorLayers; layer++) {
            final int y = baseY - layer; // 下方向へ厚みを足す。
            if (y < minY || y > maxY) {
                continue;
            }
            for (int i = 0; i < floorSizeX; i++) {
                final int x = cx - halfX + i;
                for (int j = 0; j < floorSizeZ; j++) {
                    final int z = cz - halfZ + j;
                    final Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != floorMaterial) {
                        b.setType(floorMaterial, false);
                        placed++;
                    }
                }
            }
        }
        return placed;
    }

    /**
     * 頂上（ゴール）プラットフォームを生成する。
     *
     * @param info 梯子情報
     * @return 設置（変更）したブロック数
     */
    private int generateTop(LadderInfo info) {
        return topPlatform(info, false, 0);
    }

    /**
     * 頂上（ゴール）プラットフォームを生成または撤去する。
     *
     * @param info 梯子情報
     * @param clear true で空気に戻す（撤去）。false で topMaterial を敷く（生成）
     * @param overrideY 0 以下なら config の top.y/ゴール面の絶対 Y を使用。正値ならその絶対 Y を基準に処理
     * @return 変更したブロック数
     */
    private int topPlatform(LadderInfo info, boolean clear, int overrideY) {
        final World w = info.world;
        final int cx = info.x + topCenterXOffset;
        final int cz = info.z + topCenterZOffset;
        final int halfX = (topSizeX - 1) / 2;
        final int halfZ = (topSizeZ - 1) / 2;
        // ゴール Y は絶対値（start.y は加算しない）。overrideY > 0 ならそれを優先。
        final int baseY = resolveTopY(info, overrideY);
        final int minY = w.getMinHeight();
        final int maxY = w.getMaxHeight() - 1;
        int changed = 0;
        for (int layer = 0; layer < topLayers; layer++) {
            final int y = baseY - layer;
            if (y < minY || y > maxY) {
                continue;
            }
            for (int i = 0; i < topSizeX; i++) {
                final int x = cx - halfX + i;
                for (int j = 0; j < topSizeZ; j++) {
                    final int z = cz - halfZ + j;
                    final Block b = w.getBlockAt(x, y, z);
                    if (clear) {
                        if (b.getType() != Material.AIR) {
                            b.setType(Material.AIR, false);
                            changed++;
                        }
                    } else if (b.getType() != topMaterial) {
                        b.setType(topMaterial, false);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /**
     * 頂上の Y 座標を解決する。
     *
     * @param info 梯子情報
     * @param overrideY 0 以下なら config 値を使用
     * @return 解決された絶対 Y 座標
     */
    private int resolveTopY(LadderInfo info, int overrideY) {
        final int baseY;
        if (overrideY > 0) {
            baseY = overrideY;
        } else if (topAbsY > 0) {
            baseY = topAbsY;
        } else {
            baseY = info.goalY;
        }
        return baseY + topYOffset;
    }

    /**
     * 透明バリア（外周の壁）を生成する。
     *
     * @param info 梯子情報
     * @return 設置（変更）したブロック数
     */
    private int generateBarrier(LadderInfo info) {
        return fillBarrier(info, false);
    }

    /**
     * 外周バリアを撤去する（barrierMaterial のセルのみ AIR に戻す）。
     *
     * @param info 梯子情報
     * @return 撤去したブロック数
     */
    private int clearBarrier(LadderInfo info) {
        return fillBarrier(info, true);
    }

    /**
     * バリアの生成または撤去の実装。外周セルのみを対象とし、内側は空ける。
     *
     * @param info 梯子情報
     * @param clear true で撤去、false で生成
     * @return 変更したブロック数
     */
    private int fillBarrier(LadderInfo info, boolean clear) {
        final World w = info.world;
        final int cx = info.x + barrierCenterXOffset;
        final int cz = info.z + barrierCenterZOffset;
        final int halfX = (barrierSizeX - 1) / 2;
        final int halfZ = (barrierSizeZ - 1) / 2;
        final int baseY = Math.max(info.y + barrierYOffset, w.getMinHeight());
        final int topBarrierY = Math.min(
                info.y + barrierYOffset + barrierHeight - 1, w.getMaxHeight() - 1);
        int count = 0;
        for (int i = 0; i < barrierSizeX; i++) {
            final int x = cx - halfX + i;
            for (int j = 0; j < barrierSizeZ; j++) {
                // 外周のみ処理。内側は空けて登坂・梯子に影響させない。
                final boolean edge = (i == 0 || i == barrierSizeX - 1
                        || j == 0 || j == barrierSizeZ - 1);
                if (!edge) {
                    continue;
                }
                final int z = cz - halfZ + j;
                for (int y = baseY; y <= topBarrierY; y++) {
                    final Block b = w.getBlockAt(x, y, z);
                    if (clear) {
                        if (b.getType() == barrierMaterial) {
                            b.setType(Material.AIR, false);
                            count++;
                        }
                    } else {
                        if (b.getType() != barrierMaterial) {
                            b.setType(barrierMaterial, false);
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    // ===================== /ladder delete 連動撤去 =====================

    /**
     * プレイヤーのコマンド実行を監視し、/ladder delete を検知する。
     *
     * @param e プレイヤーコマンドイベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        maybeHandleLadderDelete(e.getMessage());
    }

    /**
     * コンソールのコマンド実行を監視し、/ladder delete を検知する。
     *
     * @param e サーバーコマンドイベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        maybeHandleLadderDelete("/" + e.getCommand());
    }

    /**
     * /ladder delete を検知したら、LadderDesign が作った透明バリアと頂上ゴール床を撤去する。
     * 削除で連携先(s2e-ladder)の設定が消える可能性に備え、座標はコマンド実行前に取得する。
     *
     * @param raw コマンド文字列（先頭 / 付き）
     */
    private void maybeHandleLadderDelete(String raw) {
        if (!clearOnLadderDelete || raw == null) {
            return;
        }
        if (!isLadderDeleteCommand(raw)) {
            return;
        }
        // 削除前の座標を確保（連携先プラグインが設定を消す前に読み取る）。
        final LadderInfo info = readLadder();
        if (info == null) {
            return;
        }
        // 連携先プラグインが削除処理を終えた後に撤去（確保した座標を使用）。
        Bukkit.getScheduler().runTaskLater(this, () -> {
            final int barrierCount = clearBarrier(info);
            final int topCount = topPlatform(info, true, 0);
            getLogger().info("[ladderdesign] /ladder delete を検知 → 透明バリア "
                    + barrierCount + " ブロック・ゴール床 " + topCount
                    + " ブロックを撤去しました。");
        }, LADDER_DELETE_DELAY_TICKS);
    }

    /**
     * コマンド文字列が /ladder delete（名前空間付き含む）かどうか判定する。
     *
     * @param raw コマンド文字列（先頭 / 付き）
     * @return /ladder delete にマッチすれば true
     */
    private boolean isLadderDeleteCommand(String raw) {
        String s = raw.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        final String[] tokens = s.trim().split("\\s+");
        if (tokens.length < 2) {
            return false;
        }
        // "s2e-ladder:ladder" のような名前空間付きコマンドにも対応。
        String cmd = tokens[0].toLowerCase(Locale.ROOT);
        final int colon = cmd.indexOf(':');
        if (colon >= 0) {
            cmd = cmd.substring(colon + 1);
        }
        return cmd.equals("ladder") && tokens[1].equalsIgnoreCase("delete");
    }

    /**
     * 梯子情報を取得する。連携先 を優先し、
     * 取得できなければ s2e-ladder へフォールバックする。
     *
     * @return 梯子情報。両方とも取得不可なら null
     */
    private LadderInfo readLadder() {
        LadderInfo info = readPrimaryLadder();
        if (info == null) {
            info = readS2eLadder();
        }
        if (info != null) {
            getLogger().info("[ladderdesign] 座標ソース: " + info.source
                    + " (world=" + info.world.getName() + ", x=" + info.x
                    + ", y=" + info.y + ", z=" + info.z
                    + ", goalY=" + info.goalY + ")");
        }
        return info;
    }

    /**
     * 連携先 の config.yml から arena(ゴール原点・高さ)を読む。
     * arena 原点は柱(ゴール足場)の中央。ゴール面の絶対 Y は
     * floor(arena.y) + arena.height - 1（LadderTop v1.4.0 と同じ計算）。
     *
     * @return 梯子情報。取得不可なら null
     */
    private LadderInfo readPrimaryLadder() {
        if (NEO_PLUGIN.isEmpty()) {
            return null;
        }
        final File configFile = pluginConfigFile(NEO_PLUGIN);
        if (!configFile.exists()) {
            return null;
        }
        try {
            final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            final String worldName = yaml.getString("arena.world");
            if (worldName == null) {
                return null;
            }
            final World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            final int x = (int) Math.floor(yaml.getDouble("arena.x"));
            final int y = (int) Math.floor(yaml.getDouble("arena.y"));
            final int z = (int) Math.floor(yaml.getDouble("arena.z"));
            final int height = yaml.getInt("arena.height", DEFAULT_LADDER_HEIGHT);
            return new LadderInfo(NEO_PLUGIN, world, x, y, z, y + height - 1);
        } catch (final Exception e) {
            getLogger().warning("連携先 の config.yml の読み込みに失敗: " + e.getMessage());
            return null;
        }
    }

    /**
     * s2e-ladder の config.yml から ladder.start と ladder.height を読む。
     * ladder.height は絶対 Y として扱う（従来どおり）。
     *
     * @return 梯子情報。取得不可なら null
     */
    private LadderInfo readS2eLadder() {
        final File configFile = pluginConfigFile(S2E_PLUGIN);
        if (!configFile.exists()) {
            return null;
        }
        try {
            final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
            final String worldName = yaml.getString("ladder.start.world");
            if (worldName == null) {
                return null;
            }
            final World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            final int x = (int) Math.floor(yaml.getDouble("ladder.start.x"));
            final int y = (int) Math.floor(yaml.getDouble("ladder.start.y"));
            final int z = (int) Math.floor(yaml.getDouble("ladder.start.z"));
            final int height = yaml.getInt("ladder.height", DEFAULT_LADDER_HEIGHT);
            return new LadderInfo(S2E_PLUGIN, world, x, y, z, height);
        } catch (final Exception e) {
            getLogger().warning("s2e-ladder/config.yml の読み込みに失敗: " + e.getMessage());
            return null;
        }
    }

    /**
     * 連携先プラグインの config.yml のファイルパスを取得する。
     * プラグインがロード済みならそのデータフォルダ、未ロードならフォルダ名で推定する。
     *
     * @param pluginName 連携先プラグイン名
     * @return config.yml の File オブジェクト
     */
    private File pluginConfigFile(String pluginName) {
        final org.bukkit.plugin.Plugin target =
                getServer().getPluginManager().getPlugin(pluginName);
        final File dataFolder = (target != null)
                ? target.getDataFolder()
                : new File(getDataFolder().getParentFile(), pluginName);
        return new File(dataFolder, "config.yml");
    }

    /**
     * タブ補完の候補を返す。
     *
     * @param sender コマンド送信者
     * @param command コマンドオブジェクト
     * @param alias コマンドエイリアス
     * @param args 現在の引数
     * @return 補完候補リスト
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            final List<String> opts = buildFirstArgOptions();
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            for (final String o : opts) {
                if (o.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(o);
                }
            }
        } else if (args.length == 2 && isBarrierAlias(args[0])) {
            addClearOptions(out, args[1]);
        } else if (args.length == 2 && isTopAlias(args[0])) {
            addClearOptions(out, args[1]);
        }
        return out;
    }

    /** 第1引数のタブ補完候補リストを構築する。 */
    private List<String> buildFirstArgOptions() {
        final List<String> opts = new ArrayList<>();
        for (int i = 1; i <= patterns.size(); i++) {
            opts.add(String.valueOf(i));
        }
        opts.add("list");
        opts.add("random");
        opts.add("floor");
        opts.add("barrier");
        opts.add("top");
        opts.add("reload");
        return opts;
    }

    /** clear/off の補完候補をフィルタして追加する。 */
    private void addClearOptions(List<String> out, String input) {
        final String prefix = input.toLowerCase(Locale.ROOT);
        for (final String o : new String[]{"clear", "off"}) {
            if (o.startsWith(prefix)) {
                out.add(o);
            }
        }
    }

    // ===================== ユーティリティメソッド =====================

    /**
     * 引数が clear/off/remove のいずれかかどうか判定する。
     *
     * @param arg 判定対象の文字列
     * @return clear 系の引数であれば true
     */
    private static boolean isClearArg(String arg) {
        return arg.equalsIgnoreCase("clear")
                || arg.equalsIgnoreCase("off")
                || arg.equalsIgnoreCase("remove");
    }

    /** 引数が barrier 系のエイリアスかどうか判定する。 */
    private static boolean isBarrierAlias(String arg) {
        return arg.equalsIgnoreCase("barrier") || arg.equalsIgnoreCase("wall")
                || arg.equalsIgnoreCase("cage") || arg.equalsIgnoreCase("kabe");
    }

    /** 引数が top 系のエイリアスかどうか判定する。 */
    private static boolean isTopAlias(String arg) {
        return arg.equalsIgnoreCase("top") || arg.equalsIgnoreCase("summit")
                || arg.equalsIgnoreCase("goal") || arg.equalsIgnoreCase("chojo");
    }

    /**
     * 文字列を安全に整数にパースする。失敗時は null を返す。
     *
     * @param s パース対象の文字列
     * @return パース結果。失敗時は null
     */
    private static Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * 文字列を正の整数にパースする。null・パース失敗・負の値の場合は 0 を返す。
     *
     * @param s パース対象の文字列（null 許容）
     * @return パース結果（正の整数）。パース失敗時は 0
     */
    private static int parsePositiveIntOrZero(String s) {
        if (s == null) {
            return 0;
        }
        try {
            final int value = Integer.parseInt(s.trim());
            return Math.max(0, value);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Material 名をパースし、失敗時はデフォルト値を返す。
     *
     * @param name Material 名の文字列
     * @param defaultMaterial パース失敗時のデフォルト値
     * @return パースされた Material、または defaultMaterial
     */
    private static Material parseMaterialOrDefault(String name, Material defaultMaterial) {
        final Material mat = Material.matchMaterial(name);
        return (mat != null) ? mat : defaultMaterial;
    }

    /** 梯子座標の読み取り失敗のエラーメッセージを送信する。 */
    private static void sendLadderReadError(CommandSender sender) {
        sender.sendMessage("§c梯子の座標を取得できませんでした"
                + "（連携先 の arena、または s2e-ladder の ladder.start を確認）。");
    }

    // ===================== 内部データクラス =====================

    /**
     * 1 パターン = 名前 + ブロックリスト（複数ならカラフル = 高さで切替）。
     * band > 0 ならそのパターン固有の色替え間隔。
     */
    private static final class Pattern {

        final String name;
        final List<Material> blocks;
        /** パターン固有の色替え間隔。0 ならグローバルの band-height を使用。 */
        final int band;

        Pattern(String name, List<Material> blocks, int band) {
            this.name = name;
            this.blocks = blocks;
            this.band = band;
        }

        /**
         * 指定レイヤー（基底からの高さ）に対応するブロックを返す。
         * 単色パターンなら常に同じブロック。カラフルなら band 間隔でローテーション。
         *
         * @param layer 基底からの高さ（0 始まり）
         * @param defaultBand グローバルの band-height
         * @return 対応するブロック素材
         */
        Material blockFor(int layer, int defaultBand) {
            if (blocks.size() == 1) {
                return blocks.get(0);
            }
            final int effectiveBand = (band > 0) ? band : defaultBand;
            final int idx = Math.floorMod(layer / effectiveBand, blocks.size());
            return blocks.get(idx);
        }
    }

    /** 梯子情報（座標ソース・ワールド・基点座標・ゴール面の絶対 Y）。 */
    private static final class LadderInfo {

        /** 座標の取得元プラグイン名(s2e-ladder)。 */
        final String source;
        final World world;
        final int x;
        final int y;
        final int z;
        /** ゴール面の絶対 Y。 */
        final int goalY;

        LadderInfo(String source, World world, int x, int y, int z, int goalY) {
            this.source = source;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.goalY = goalY;
        }
    }
}
