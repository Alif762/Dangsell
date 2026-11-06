package my.dangsell;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

public class DangSell extends JavaPlugin implements Listener, CommandExecutor {

    private static final int SIZE = 54;
    private static final int SELL_SLOT = 53; // bucu kanan bawah

    private static class SellHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() { return null; }
    }

    private final Set<UUID> open = new HashSet<>();
    private YamlConfiguration worth;
    private Object eco;
    private Method deposit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("jual") != null) getCommand("jual").setExecutor(this);
    }

    @Override
    public void onDisable() {
        for (UUID id : new HashSet<>(open)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.closeInventory();
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String key, String def) {
        return color(getConfig().getString("messages." + key, def));
    }

    // ---------- Command ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("dangsell.reload")) return true;
            reloadConfig();
            worth = null;
            sender.sendMessage(msg("reloaded", "&aConfig dimuat semula."));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Hanya pemain boleh guna command ini.");
            return true;
        }
        if (!p.hasPermission("dangsell.use")) return true;
        openMenu(p);
        return true;
    }

    // ---------- GUI ----------
    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new SellHolder(), SIZE,
                color(getConfig().getString("gui-title", "&8Sell")));
        inv.setItem(SELL_SLOT, sellButton());
        open.add(p.getUniqueId());
        p.openInventory(inv);
    }

    private ItemStack sellButton() {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&a&lSELL"));
        meta.setLore(List.of(
                color("&7Letak item dalam menu ini,"),
                color("&7kemudian klik untuk jual.")));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        if (e.getRawSlot() == SELL_SLOT) {
            e.setCancelled(true);
            sell(p, top);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SellHolder)) return;
        if (e.getRawSlots().contains(SELL_SLOT)) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof SellHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        open.remove(p.getUniqueId());

        // Pulangkan item yang belum dijual
        for (int i = 0; i < SELL_SLOT; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack r : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), r);
            }
        }
        inv.clear();
    }

    // ---------- Jual ----------
    private void sell(Player p, Inventory inv) {
        if (!hookEconomy()) {
            p.sendMessage(msg("no-economy", "&cSistem duit (Vault) tak jumpa."));
            return;
        }

        double total = 0;
        int count = 0;
        for (int i = 0; i < SELL_SLOT; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            double price = priceOf(it);
            if (price <= 0) continue;
            total += price * it.getAmount();
            count += it.getAmount();
            inv.setItem(i, null);
        }

        if (count == 0) {
            p.sendMessage(msg("nothing", "&cTiada item yang boleh dijual dalam menu."));
            return;
        }

        try {
            deposit.invoke(eco, p, total);
        } catch (Throwable t) {
            getLogger().warning("Gagal masukkan duit: " + t.getMessage());
        }

        p.sendMessage(msg("sold", "&aBerjaya jual &f%items% &aitem dan dapat &f$%money%")
                .replace("%items%", String.valueOf(count))
                .replace("%money%", String.format(Locale.US, "%,.2f", total)));
        p.playSound(p.getLocation(), "entity.experience_orb.pickup", 1f, 1f);
    }

    // ---------- Harga ----------
    private double priceOf(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return 0;
        String key = item.getType().name();

        if (getConfig().isSet("prices." + key)) {
            return getConfig().getDouble("prices." + key);
        }
        if (getConfig().getBoolean("use-essentials-worth", true)) {
            if (worth == null) {
                File f = new File(getDataFolder().getParentFile(), "Essentials/worth.yml");
                worth = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
            }
            return worth.getDouble("worth." + key.toLowerCase(), 0);
        }
        return 0;
    }

    // ---------- Duit (Vault) ----------
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean hookEconomy() {
        if (eco != null) return true;
        try {
            Class c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return false;
            eco = rsp.getProvider();
            deposit = c.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    }
