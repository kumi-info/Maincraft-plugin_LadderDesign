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
 * 梯子（s2e-ladder の登る塔）の背面ブロックを、テーマ／カラフルに塗り替えるコマンドプラグイン。
 *
 *   /ladderdesign &lt;1-6|list|random|reload&gt;
 *
 * - s2e-ladder の plugins/s2e-ladder/config.yml から start 座標と height を自動取得。
 * - start の真上の柱（既定 3x3）を、上から下まで走査して塗り替える。
 * - はしご(LADDER)・ツル・空気など「登れる/通れるもの」は isSolid()==false なので塗らずに残す
 *   ＝塗り替えても登坂に影響しない（壁ブロックだけ色/素材が変わる）。
 * - カラフル系（複数ブロック指定）は band-height ごとに色を切り替えてグラデーションにする。
 */
public final class LadderDesignPlugin extends JavaPlugin implements TabExecutor, Listener {

    private final List<Pattern> patterns = new ArrayList<>();
    /** /ladder delete 時にバリア＋ゴールを自動撤去するか（config: clear-on-ladder-delete）。 */
    private boolean clearOnLadderDelete;
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
        getLogger().info("LadderDesign 有効化。/ladderdesign <1-" + patterns.size()
                + "|list|random|reload> が利用可能。"
                + "（/ladder delete 連動撤去: " + (clearOnLadderDelete ? "ON" : "OFF") + "）");
    }

    private void loadSettings() {
        reloadConfig();
        halfWidth = Math.max(0, getConfig().getInt("scan.half-width", 1));
        halfDepth = Math.max(0, getConfig().getInt("scan.half-depth", 1));
        yOffset = getConfig().getInt("scan.y-offset", 0);
        yTopExtra = getConfig().getInt("scan.y-top-extra", 0);
        bandHeight = Math.max(1, getConfig().getInt("band-height", 4));

        keep = EnumSet.noneOf(Material.class);
        for (String s : getConfig().getStringList("keep-blocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null) {
                keep.add(m);
            }
        }

        floorMaterial = Material.matchMaterial(getConfig().getString("floor.material", "RED_CONCRETE"));
        if (floorMaterial == null) {
            floorMaterial = Material.RED_CONCRETE;
        }
        floorSizeX = Math.max(1, getConfig().getInt("floor.size-x", 16));
        floorSizeZ = Math.max(1, getConfig().getInt("floor.size-z", 16));
        floorCenterXOffset = getConfig().getInt("floor.center-x-offset", 0);
        floorCenterZOffset = getConfig().getInt("floor.center-z-offset", 0);
        floorYOffset = getConfig().getInt("floor.y-offset", -1);
        floorLayers = Math.max(1, getConfig().getInt("floor.layers", 1));
        applyFloorWithDesign = getConfig().getBoolean("floor.apply-with-design", true);

        barrierMaterial = Material.matchMaterial(getConfig().getString("barrier.material", "BARRIER"));
        if (barrierMaterial == null) {
            barrierMaterial = Material.BARRIER;
        }
        barrierHeight = Math.max(1, getConfig().getInt("barrier.height", 1040));
        barrierYOffset = getConfig().getInt("barrier.y-offset", 0);
        barrierSizeX = Math.max(2, getConfig().getInt("barrier.size-x", 20));
        barrierSizeZ = Math.max(2, getConfig().getInt("barrier.size-z", 20));
        barrierCenterXOffset = getConfig().getInt("barrier.center-x-offset", 0);
        barrierCenterZOffset = getConfig().getInt("barrier.center-z-offset", 0);
        applyBarrierWithDesign = getConfig().getBoolean("barrier.apply-with-design", true);

        topMaterial = Material.matchMaterial(getConfig().getString("top.material", "RED_CONCRETE"));
        if (topMaterial == null) {
            topMaterial = Material.RED_CONCRETE;
        }
        topSizeX = Math.max(1, getConfig().getInt("top.size-x", 7));
        topSizeZ = Math.max(1, getConfig().getInt("top.size-z", 7));
        topCenterXOffset = getConfig().getInt("top.center-x-offset", -3);
        topCenterZOffset = getConfig().getInt("top.center-z-offset", 0);
        topAbsY = getConfig().getInt("top.y", 0);
        topYOffset = getConfig().getInt("top.y-offset", 0);
        topLayers = Math.max(1, getConfig().getInt("top.layers", 1));
        applyTopWithDesign = getConfig().getBoolean("top.apply-with-design", true);

        clearOnLadderDelete = getConfig().getBoolean("clear-on-ladder-delete", true);

        patterns.clear();
        for (Map<?, ?> m : getConfig().getMapList("patterns")) {
            Object nameO = m.get("name");
            Object blocksO = m.get("blocks");
            Object bandO = m.get("band");
            String name = (nameO != null) ? nameO.toString() : "?";
            int band = (bandO instanceof Number) ? ((Number) bandO).intValue() : 0;
            List<Material> blocks = new ArrayList<>();
            if (blocksO instanceof List) {
                for (Object o : (List<?>) blocksO) {
                    Material mat = Material.matchMaterial(o.toString());
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
            patterns.add(new Pattern("木", List.of(Material.OAK_PLANKS), 0));
            patterns.add(new Pattern("石英", List.of(Material.QUARTZ_BLOCK), 0));
            patterns.add(new Pattern("ネザー", List.of(Material.NETHER_BRICKS), 0));
            patterns.add(new Pattern("エンド", List.of(Material.PURPUR_BLOCK), 0));
            patterns.add(new Pattern("カラフルガラス", List.of(
                    Material.RED_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS,
                    Material.LIME_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS, Material.BLUE_STAINED_GLASS,
                    Material.PURPLE_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS), 3));
            patterns.add(new Pattern("カラフルコンクリート", List.of(
                    Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE,
                    Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE,
                    Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE), 3));
        }
    }

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
            sender.sendMessage("§e使い方: §f/ladderdesign <1-" + patterns.size() + "|list|random|reload>");
            listPatterns(sender);
            return true;
        }

        String a = args[0].toLowerCase(Locale.ROOT);
        if (a.equals("reload")) {
            loadSettings();
            sender.sendMessage("§aconfig.yml を再読み込みしました。（パターン " + patterns.size() + " 件）");
            return true;
        }
        if (a.equals("list")) {
            listPatterns(sender);
            return true;
        }
        if (a.equals("floor") || a.equals("base") || a.equals("platform") || a.equals("ashiba")) {
            LadderInfo info = readLadder();
            if (info == null) {
                sender.sendMessage("§cs2e-ladder の設定（plugins/s2e-ladder/config.yml）を読めませんでした。");
                return true;
            }
            int n = generateFloor(info);
            sender.sendMessage("§a足場（" + floorSizeX + "×" + floorSizeZ + " "
                    + floorMaterial.name() + "）を生成しました。§7(" + n + " ブロック)");
            return true;
        }
        if (a.equals("barrier") || a.equals("wall") || a.equals("cage") || a.equals("kabe")) {
            LadderInfo info = readLadder();
            if (info == null) {
                sender.sendMessage("§cs2e-ladder の設定（plugins/s2e-ladder/config.yml）を読めませんでした。");
                return true;
            }
            boolean clear = args.length >= 2 && (args[1].equalsIgnoreCase("clear")
                    || args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("remove"));
            long t = System.currentTimeMillis();
            int n = clear ? clearBarrier(info) : generateBarrier(info);
            long ms = System.currentTimeMillis() - t;
            if (clear) {
                sender.sendMessage("§a透明バリアを撤去しました。§7(" + n + " ブロック / " + ms + "ms)");
            } else {
                sender.sendMessage("§a透明バリア（外周 " + barrierSizeX + "×" + barrierSizeZ
                        + "・高さ " + barrierHeight + "）を生成しました。§7(" + n + " ブロック / " + ms + "ms)");
            }
            return true;
        }
        if (a.equals("top") || a.equals("summit") || a.equals("goal") || a.equals("chojo")) {
            LadderInfo info = readLadder();
            if (info == null) {
                sender.sendMessage("§cs2e-ladder の設定（plugins/s2e-ladder/config.yml）を読めませんでした。");
                return true;
            }
            boolean clear = args.length >= 2 && (args[1].equalsIgnoreCase("clear")
                    || args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("remove"));
            int overrideY = 0;
            // 撤去時は任意の Y を指定可能: /ladderdesign top clear <Y>
            String yArg = clear ? (args.length >= 3 ? args[2] : null)
                    : (args.length >= 2 ? args[1] : null);
            if (yArg != null) {
                try {
                    overrideY = Integer.parseInt(yArg);
                } catch (NumberFormatException ignored) {
                    // 数値でなければ無視（config 値を使用）。
                }
            }
            int gy = (overrideY > 0 ? overrideY : (topAbsY > 0 ? topAbsY : info.height)) + topYOffset;
            int n = topPlatform(info, clear, overrideY);
            if (clear) {
                sender.sendMessage("§a頂上（" + topSizeX + "×" + topSizeZ
                        + " / Y=" + gy + "）を撤去しました。§7(" + n + " ブロック)");
            } else {
                sender.sendMessage("§a頂上（" + topSizeX + "×" + topSizeZ + " "
                        + topMaterial.name() + " / Y=" + gy
                        + "）を生成しました。§7(" + n + " ブロック)");
            }
            if (n == 0) {
                sender.sendMessage("§e※ 0ブロックでした。Y=" + gy
                        + " の位置やサイズ・中心オフセットを確認してください。");
            }
            return true;
        }

        int index;
        if (a.equals("random")) {
            index = ThreadLocalRandom.current().nextInt(patterns.size());
        } else {
            Integer n = tryParseInt(a);
            if (n == null || n < 1 || n > patterns.size()) {
                sender.sendMessage("§cパターン番号は 1〜" + patterns.size()
                        + " で指定してください（/ladderdesign list で一覧）。");
                return true;
            }
            index = n - 1;
        }

        LadderInfo info = readLadder();
        if (info == null) {
            sender.sendMessage("§cs2e-ladder の設定（plugins/s2e-ladder/config.yml）を読めませんでした。"
                    + "ワールド/スタート座標が見つかりません。");
            return true;
        }

        Pattern p = patterns.get(index);
        long t0 = System.currentTimeMillis();
        int changed = applyPattern(p, info);
        String extra = "";
        if (applyFloorWithDesign) {
            int fn = generateFloor(info);
            extra += " §a＋足場 §7" + fn;
        }
        if (applyBarrierWithDesign) {
            int bn = generateBarrier(info);
            extra += " §a＋バリア §7" + bn;
        }
        if (applyTopWithDesign) {
            int tn = generateTop(info);
            extra += " §a＋頂上 §7" + tn;
        }
        long ms = System.currentTimeMillis() - t0;
        sender.sendMessage("§a梯子デザインを §e[" + (index + 1) + "] " + p.name
                + " §aに変更しました。§7(" + changed + " ブロック)" + extra + " §7/ " + ms + "ms");
        return true;
    }

    private void listPatterns(CommandSender sender) {
        sender.sendMessage("§6=== 梯子デザイン一覧 ===");
        for (int i = 0; i < patterns.size(); i++) {
            Pattern p = patterns.get(i);
            String kind = (p.blocks.size() > 1) ? "§7(カラフル " + p.blocks.size() + "色)" : "";
            sender.sendMessage("§e" + (i + 1) + " §f" + p.name + " " + kind);
        }
    }

    /** 指定パターンを梯子の柱へ適用し、塗り替えたブロック数を返す。 */
    private int applyPattern(Pattern p, LadderInfo info) {
        World w = info.world;
        int baseY = Math.max(info.y + yOffset, w.getMinHeight());
        int topY = Math.min(info.y + info.height + yTopExtra, w.getMaxHeight() - 1);
        int changed = 0;
        for (int y = baseY; y <= topY; y++) {
            int layer = y - baseY;
            Material mat = p.blockFor(layer, bandHeight);
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                for (int dz = -halfDepth; dz <= halfDepth; dz++) {
                    Block b = w.getBlockAt(info.x + dx, y, info.z + dz);
                    Material cur = b.getType();
                    if (!cur.isSolid()) {
                        continue; // 空気・はしご・ツル等は残す（登坂に影響させない）。
                    }
                    if (keep.contains(cur) || cur == mat) {
                        continue;
                    }
                    b.setType(mat, false); // 物理更新オフ。
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * 足場（床）を生成（再生成）する。start を中心に floor.size-x × floor.size-z の床を
     * floorMaterial（既定 RED_CONCRETE）で敷く。妨害配信で破壊された床の復元用。
     * @return 設置（変更）したブロック数
     */
    private int generateFloor(LadderInfo info) {
        World w = info.world;
        int cx = info.x + floorCenterXOffset;
        int cz = info.z + floorCenterZOffset;
        int halfX = (floorSizeX - 1) / 2;
        int halfZ = (floorSizeZ - 1) / 2;
        int baseY = info.y + floorYOffset;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        int placed = 0;
        for (int layer = 0; layer < floorLayers; layer++) {
            int y = baseY - layer; // 下方向へ厚みを足す。
            if (y < minY || y > maxY) {
                continue;
            }
            for (int i = 0; i < floorSizeX; i++) {
                int x = cx - halfX + i;
                for (int j = 0; j < floorSizeZ; j++) {
                    int z = cz - halfZ + j;
                    Block b = w.getBlockAt(x, y, z);
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
     * 頂上（ゴール）プラットフォームを生成する。LadderTop のゴールに合わせ、
     * 既定で start を基準に 7×7（center-x-offset=-3）の床を、
     * 絶対 Y = ladder.height + top.y-offset（既定1000）に敷く。
     * @return 設置（変更）したブロック数
     */
    private int generateTop(LadderInfo info) {
        return topPlatform(info, false, 0);
    }

    /**
     * 頂上（ゴール）プラットフォームを生成／撤去する。
     * @param clear true で空気に戻す（撤去）。false で topMaterial を敷く（生成）。
     * @param overrideY 0 以下なら config の top.y/ladder.height を使用。正値ならその絶対 Y を基準に処理。
     * @return 変更したブロック数
     */
    private int topPlatform(LadderInfo info, boolean clear, int overrideY) {
        World w = info.world;
        int cx = info.x + topCenterXOffset;
        int cz = info.z + topCenterZOffset;
        int halfX = (topSizeX - 1) / 2;
        int halfZ = (topSizeZ - 1) / 2;
        // ゴール Y は絶対値で扱う（start.y は加算しない）。overrideY 指定があればそれを優先。
        // 無ければ top.y を指定すればその絶対 Y、0 なら ladder.height。さらに top.y-offset を加算する。
        int baseY = (overrideY > 0 ? overrideY : (topAbsY > 0 ? topAbsY : info.height)) + topYOffset;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        int changed = 0;
        for (int layer = 0; layer < topLayers; layer++) {
            int y = baseY - layer;
            if (y < minY || y > maxY) {
                continue;
            }
            for (int i = 0; i < topSizeX; i++) {
                int x = cx - halfX + i;
                for (int j = 0; j < topSizeZ; j++) {
                    int z = cz - halfZ + j;
                    Block b = w.getBlockAt(x, y, z);
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
     * 透明バリア（外周の壁）を生成する。足場と同じ footprint（floor.size-x × size-z）の
     * 外周セルにのみ barrierMaterial（既定 BARRIER）を、baseY から barrierHeight 段積む。
     * 内側は空けるので登坂・梯子に影響しない。
     * @return 設置（変更）したブロック数
     */
    private int generateBarrier(LadderInfo info) {
        return fillBarrier(info, false);
    }

    /** 外周バリアを撤去（barrierMaterial のセルのみ AIR に戻す）。 */
    private int clearBarrier(LadderInfo info) {
        return fillBarrier(info, true);
    }

    private int fillBarrier(LadderInfo info, boolean clear) {
        World w = info.world;
        int cx = info.x + barrierCenterXOffset;
        int cz = info.z + barrierCenterZOffset;
        int halfX = (barrierSizeX - 1) / 2;
        int halfZ = (barrierSizeZ - 1) / 2;
        int baseY = Math.max(info.y + barrierYOffset, w.getMinHeight());
        int topY = Math.min(info.y + barrierYOffset + barrierHeight - 1, w.getMaxHeight() - 1);
        int count = 0;
        for (int i = 0; i < barrierSizeX; i++) {
            int x = cx - halfX + i;
            for (int j = 0; j < barrierSizeZ; j++) {
                boolean edge = (i == 0 || i == barrierSizeX - 1 || j == 0 || j == barrierSizeZ - 1);
                if (!edge) {
                    continue; // 外周のみ。内側は空ける。
                }
                int z = cz - halfZ + j;
                for (int y = baseY; y <= topY; y++) {
                    Block b = w.getBlockAt(x, y, z);
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        maybeHandleLadderDelete(e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        maybeHandleLadderDelete("/" + e.getCommand());
    }

    /**
     * /ladder delete を検知したら、LadderDesign が作った透明バリアと頂上ゴール床を撤去する。
     * 削除でs2e-ladder設定が消える可能性に備え、座標は今（コマンド実行前）に取得しておく。
     */
    private void maybeHandleLadderDelete(String raw) {
        if (!clearOnLadderDelete || raw == null) {
            return;
        }
        String s = raw.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        String[] a = s.trim().split("\\s+");
        if (a.length < 2) {
            return;
        }
        String cmd = a[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');           // s2e-ladder:ladder の名前空間付きにも対応
        if (colon >= 0) {
            cmd = cmd.substring(colon + 1);
        }
        if (!cmd.equals("ladder") || !a[1].equalsIgnoreCase("delete")) {
            return;
        }
        final LadderInfo info = readLadder();    // 削除前の座標を確保
        if (info == null) {
            return;
        }
        // s2e-ladder が削除処理を終えた後に撤去（捕捉した座標を使用）。
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int b = clearBarrier(info);
            int t = topPlatform(info, true, 0);
            getLogger().info("[ladderdesign] /ladder delete を検知 → 透明バリア " + b
                    + " ブロック・ゴール床 " + t + " ブロックを撤去しました。");
        }, 2L);
    }

    /** s2e-ladder の config.yml から梯子情報を読む。読めなければ null。 */
    private LadderInfo readLadder() {
        File f = new File(getDataFolder().getParentFile(), "s2e-ladder/config.yml");
        if (!f.exists()) {
            return null;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String worldName = y.getString("ladder.start.world");
        if (worldName == null) {
            return null;
        }
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            return null;
        }
        int x = (int) Math.floor(y.getDouble("ladder.start.x"));
        int sy = (int) Math.floor(y.getDouble("ladder.start.y"));
        int z = (int) Math.floor(y.getDouble("ladder.start.z"));
        int height = y.getInt("ladder.height", 1000);
        return new LadderInfo(w, x, sy, z, height);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> opts = new ArrayList<>();
            for (int i = 1; i <= patterns.size(); i++) {
                opts.add(String.valueOf(i));
            }
            opts.add("list");
            opts.add("random");
            opts.add("floor");
            opts.add("barrier");
            opts.add("top");
            opts.add("reload");
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (String o : opts) {
                if (o.toLowerCase(Locale.ROOT).startsWith(pre)) {
                    out.add(o);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("barrier")
                || args[0].equalsIgnoreCase("wall") || args[0].equalsIgnoreCase("cage")
                || args[0].equalsIgnoreCase("kabe"))) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            for (String o : new String[]{"clear", "off"}) {
                if (o.startsWith(pre)) {
                    out.add(o);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("top")
                || args[0].equalsIgnoreCase("summit") || args[0].equalsIgnoreCase("goal")
                || args[0].equalsIgnoreCase("chojo"))) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            for (String o : new String[]{"clear", "off"}) {
                if (o.startsWith(pre)) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    private Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 1パターン＝名前＋ブロックリスト（複数ならカラフル＝高さで切替）。band>0 ならそのパターン固有の色替え間隔。 */
    private static final class Pattern {
        final String name;
        final List<Material> blocks;
        final int band; // 0 = グローバルの band-height を使用。

        Pattern(String name, List<Material> blocks, int band) {
            this.name = name;
            this.blocks = blocks;
            this.band = band;
        }

        Material blockFor(int layer, int defaultBand) {
            if (blocks.size() == 1) {
                return blocks.get(0);
            }
            int b = (band > 0) ? band : defaultBand;
            int idx = Math.floorMod(layer / b, blocks.size());
            return blocks.get(idx);
        }
    }

    /** s2e-ladder の梯子情報。 */
    private static final class LadderInfo {
        final World world;
        final int x;
        final int y;
        final int z;
        final int height;

        LadderInfo(World world, int x, int y, int z, int height) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.height = height;
        }
    }
}
