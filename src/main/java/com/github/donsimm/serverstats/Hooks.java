package com.github.donsimm.serverstats;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Anbindung an optionale Plugins per Reflection: ServerStats laeuft auch, wenn keines davon installiert ist,
 * und braucht beim Bauen keine fremden Bibliotheken.
 */
final class Hooks {
    private final Logger log;
    private final java.util.Set<String> reported = new java.util.HashSet<>();

    private Object economy;
    private Method ecoBalance;

    private Method mmLevel;
    private Method mmPower;
    private Method mmLevelOnline;
    private Method mmPowerOnline;
    private Object[] mmSkills;

    private Method papiParse;

    private Object essentials;
    private Method essGetUser;

    Hooks(Logger log) {
        this.log = log;
    }

    private void report(String key, String message, Throwable t) {
        if (reported.add(key)) {
            log.warning(message + (t == null ? "" : " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")"));
        }
    }

    private static ClassLoader loaderOf(Plugin plugin) {
        return plugin.getClass().getClassLoader();
    }

    // ---------------------------------------------------------------- Vault

    boolean vault() {
        if (ecoBalance != null) {
            return true;
        }
        try {
            Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
            if (vault == null) {
                return false;
            }
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy", true, loaderOf(vault));
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp == null) {
                report("vault-noprovider", "Vault ist installiert, aber kein Wirtschafts-Plugin (z. B. EssentialsX Economy) ist registriert.", null);
                return false;
            }
            economy = rsp.getProvider();
            ecoBalance = economyClass.getMethod("getBalance", OfflinePlayer.class);
            log.info("Vault-Anbindung aktiv (" + economy.getClass().getSimpleName() + ").");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            report("vault-error", "Vault-Anbindung fehlgeschlagen", e);
            return false;
        }
    }

    double balance(OfflinePlayer player) {
        try {
            return ((Number) ecoBalance.invoke(economy, player)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Double.NaN;
        }
    }

    // ---------------------------------------------------------------- mcMMO

    boolean mcmmo() {
        if (mmLevel != null) {
            return true;
        }
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("mcMMO");
            if (plugin == null) {
                return false;
            }
            ClassLoader loader = loaderOf(plugin);
            Class<?> api = Class.forName("com.gmail.nossr50.api.ExperienceAPI", true, loader);
            Class<?> skillType = Class.forName("com.gmail.nossr50.datatypes.skills.PrimarySkillType", true, loader);
            mmLevel = api.getMethod("getLevelOffline", UUID.class, String.class);
            mmPower = api.getMethod("getPowerLevelOffline", UUID.class);
            try {
                // Fuer Spieler, die gerade online sind (ihr Profil liegt im Speicher, auch wenn es noch nicht gespeichert wurde)
                mmLevelOnline = api.getMethod("getLevel", Player.class, String.class);
                mmPowerOnline = api.getMethod("getPowerLevel", Player.class);
            } catch (NoSuchMethodException e) {
                mmLevelOnline = null;
                mmPowerOnline = null;
            }
            mmSkills = skillType.getEnumConstants();
            log.info("mcMMO-Anbindung aktiv (" + mmSkills.length + " Skills gefunden).");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            mmLevel = null;
            report("mcmmo-error", "mcMMO-Anbindung fehlgeschlagen", e);
            return false;
        }
    }

    private static boolean noProfile(Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        return cause.getClass().getSimpleName().equals("InvalidPlayerException");
    }

    /** Skill-Level aller Hauptskills, null wenn der Spieler bei mcMMO kein Profil hat oder nichts gelesen werden konnte. */
    Map<String, Integer> mcmmoLevels(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        boolean useOnline = online != null && mmLevelOnline != null;
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Object skill : mmSkills) {
            String name = ((Enum<?>) skill).name();
            if (name.equals("SALVAGE") || name.equals("SMELTING")) {
                continue; // Child-Skills ohne eigene Erfahrung
            }
            try {
                Object value = useOnline ? mmLevelOnline.invoke(null, online, name) : mmLevel.invoke(null, uuid, name);
                int level = ((Number) value).intValue();
                if (level >= 0) {
                    result.put(name, level);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (noProfile(e)) {
                    return null; // Spieler hat kein mcMMO-Profil: normal, kein Fehler
                }
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                report("mcmmo-level-" + name, "mcMMO: Skill " + name + " nicht lesbar (" + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                        + "), wird uebersprungen. Weitere Fehler dieser Art werden pro Skill nur einmal gemeldet.", null);
            }
        }
        return result.isEmpty() ? null : result;
    }

    int mcmmoPower(UUID uuid) {
        try {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && mmPowerOnline != null) {
                return ((Number) mmPowerOnline.invoke(null, online)).intValue();
            }
            return ((Number) mmPower.invoke(null, uuid)).intValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- PlaceholderAPI

    boolean papi() {
        if (papiParse != null) {
            return true;
        }
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (plugin == null) {
                return false;
            }
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, loaderOf(plugin));
            papiParse = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            report("papi-error", "PlaceholderAPI-Anbindung fehlgeschlagen", e);
            return false;
        }
    }

    boolean ajlbInstalled() {
        return Bukkit.getPluginManager().getPlugin("AJLeaderboards") != null;
    }

    String parsePlaceholders(String text) {
        try {
            return (String) papiParse.invoke(null, null, text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return text;
        }
    }

    // ---------------------------------------------------------------- EssentialsX / Vanish

    private boolean essentials() {
        if (essGetUser != null) {
            return true;
        }
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("Essentials");
            if (plugin == null) {
                return false;
            }
            essentials = plugin;
            essGetUser = plugin.getClass().getMethod("getUser", UUID.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            report("ess-error", "EssentialsX-Anbindung fehlgeschlagen", e);
            return false;
        }
    }

    private boolean essentialsFlag(UUID uuid, String method) {
        if (!essentials()) {
            return false;
        }
        try {
            Object user = essGetUser.invoke(essentials, uuid);
            return user != null && (Boolean) user.getClass().getMethod(method).invoke(user);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    boolean afk(Player player) {
        return essentialsFlag(player.getUniqueId(), "isAfk");
    }

    boolean vanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return essentialsFlag(player.getUniqueId(), "isVanished");
    }
}
