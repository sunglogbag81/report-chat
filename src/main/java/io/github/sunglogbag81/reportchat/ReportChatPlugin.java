package io.github.sunglogbag81.reportchat;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class ReportChatPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final AtomicInteger reportIds = new AtomicInteger(0);
    private final AtomicInteger chatIds = new AtomicInteger(0);
    private final Map<Integer, ReportRequest> reports = new ConcurrentHashMap<>();
    private final Map<Integer, ReportChatSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> activeSessionByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastReportAt = new ConcurrentHashMap<>();
    private BukkitTask timeoutTask;

    private String adminPermission;
    private String reloadPermission;
    private boolean notifyOp;
    private boolean allowEmptyReport;
    private boolean oneActiveChatPerPlayer;
    private boolean logToConsole;
    private int cooldownSeconds;
    private int autoTimeoutMinutes;
    private String prefix;
    private List<String> configuredAdmins;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("report").setExecutor(this);
        getCommand("reportchat").setExecutor(this);
        getCommand("reportchat").setTabCompleter(this);

        timeoutTask = Bukkit.getScheduler().runTaskTimer(this, this::expireIdleSessions, 20L * 60, 20L * 60);
        getLogger().info("ReportChat enabled.");
    }

    @Override
    public void onDisable() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
        sessions.clear();
        reports.clear();
        activeSessionByPlayer.clear();
        getLogger().info("ReportChat disabled.");
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();
        configuredAdmins = config.getStringList("admins").stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
        notifyOp = config.getBoolean("notify-op", true);
        adminPermission = config.getString("permissions.admin-node", "report.admin");
        reloadPermission = config.getString("permissions.reload-node", "report.reload");
        allowEmptyReport = config.getBoolean("report.allow-empty-report", true);
        cooldownSeconds = Math.max(0, config.getInt("report.cooldown-seconds", 30));
        oneActiveChatPerPlayer = config.getBoolean("private-chat.one-active-chat-per-player", true);
        autoTimeoutMinutes = Math.max(0, config.getInt("private-chat.auto-timeout-minutes", 30));
        logToConsole = config.getBoolean("private-chat.log-to-console", true);
        prefix = color(config.getString("private-chat.prefix", "&8[&cReportChat&8]"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("report")) {
            return handleReport(sender, args);
        }
        if (command.getName().equalsIgnoreCase("reportchat")) {
            return handleReportChat(sender, args);
        }
        return false;
    }

    private boolean handleReport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player reporter)) {
            sender.sendMessage(color("&cOnly players can create reports."));
            return true;
        }

        String message = String.join(" ", args).trim();
        if (!allowEmptyReport && message.isEmpty()) {
            reporter.sendMessage(color("&cUsage: /report <message>"));
            return true;
        }

        long now = System.currentTimeMillis();
        long last = lastReportAt.getOrDefault(reporter.getUniqueId(), 0L);
        long remainingMillis = (cooldownSeconds * 1000L) - (now - last);
        if (remainingMillis > 0) {
            long remainingSeconds = Math.max(1, (remainingMillis + 999) / 1000);
            reporter.sendMessage(color("&cYou can send another report in " + remainingSeconds + " seconds."));
            return true;
        }
        lastReportAt.put(reporter.getUniqueId(), now);

        int reportId = reportIds.incrementAndGet();
        ReportRequest report = new ReportRequest(reportId, reporter.getUniqueId(), reporter.getName(), message, Instant.now());
        reports.put(reportId, report);

        Collection<Player> admins = onlineAdmins();
        for (Player admin : admins) {
            sendReportNotification(admin, report);
            playConfiguredSound(admin, "sounds.report-received", Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        }

        reporter.sendMessage(color("&aYour report has been sent to online staff. Report ID: &f#" + reportId));
        String reportText = message.isEmpty() ? "(empty report)" : message;
        getLogger().info("Report #" + reportId + " from " + reporter.getName() + ": " + reportText + " | notified=" + admins.size());
        return true;
    }

    private void sendReportNotification(Player admin, ReportRequest report) {
        String reportText = report.message().isEmpty() ? "(empty report)" : report.message();
        admin.sendMessage(color("&8&m--------------------------------------------------"));
        admin.sendMessage(color("&c&l[REPORT #" + report.id() + "] &f" + report.reporterName() + "&7: &f" + reportText));

        TextComponent button = new TextComponent(color("&a[개인 채팅 생성]"));
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/reportchat open " + report.id()));
        button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(color("&eClick to create a private report chat for #" + report.id())).create()));
        admin.spigot().sendMessage(button);
        admin.sendMessage(color("&8&m--------------------------------------------------"));
    }

    private boolean handleReportChat(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendReportChatHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> {
                return openReportChat(sender, args);
            }
            case "invite" -> {
                return invitePlayer(sender, args);
            }
            case "kick", "remove" -> {
                return kickPlayer(sender, args);
            }
            case "close", "delete" -> {
                return closeSession(sender, args);
            }
            case "list" -> {
                return listSessions(sender);
            }
            case "leave" -> {
                return leaveSession(sender);
            }
            case "reload" -> {
                return reloadPluginConfig(sender);
            }
            default -> {
                sendReportChatHelp(sender);
                return true;
            }
        }
    }

    private boolean openReportChat(CommandSender sender, String[] args) {
        if (!ensureAdmin(sender)) return true;
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(color("&cOnly players can open a private report chat."));
            return true;
        }
        if (args.length < 2) {
            admin.sendMessage(color("&cUsage: /reportchat open <reportId>"));
            return true;
        }
        Integer reportId = parsePositiveInt(args[1]);
        if (reportId == null || !reports.containsKey(reportId)) {
            admin.sendMessage(color("&cUnknown report ID."));
            return true;
        }

        ReportRequest report = reports.get(reportId);
        Player reporter = Bukkit.getPlayer(report.reporterId());
        if (reporter == null || !reporter.isOnline()) {
            admin.sendMessage(color("&cThe reporter is offline; cannot create a live private chat."));
            return true;
        }

        if (oneActiveChatPerPlayer) {
            if (activeSessionByPlayer.containsKey(reporter.getUniqueId())) {
                admin.sendMessage(color("&cThat reporter already has an active report chat."));
                return true;
            }
            if (activeSessionByPlayer.containsKey(admin.getUniqueId())) {
                admin.sendMessage(color("&cYou already have an active report chat. Close it first with /rc close."));
                return true;
            }
        }

        int chatId = chatIds.incrementAndGet();
        ReportChatSession session = new ReportChatSession(chatId, reportId, admin.getUniqueId(), Instant.now());
        session.addParticipant(reporter.getUniqueId());
        session.addParticipant(admin.getUniqueId());
        sessions.put(chatId, session);
        activeSessionByPlayer.put(reporter.getUniqueId(), chatId);
        activeSessionByPlayer.put(admin.getUniqueId(), chatId);

        broadcastToSession(session, color(prefix + " &aPrivate report chat #" + chatId + " created by " + admin.getName() + "."));
        broadcastToSession(session, color(prefix + " &7All normal chat messages from participants are now visible only in this report chat."));
        playConfiguredSound(reporter, "sounds.private-chat-created", Sound.BLOCK_NOTE_BLOCK_PLING);
        playConfiguredSound(admin, "sounds.private-chat-created", Sound.BLOCK_NOTE_BLOCK_PLING);
        audit("Admin " + admin.getName() + " opened report chat #" + chatId + " for report #" + reportId + " from " + reporter.getName());
        return true;
    }

    private boolean invitePlayer(CommandSender sender, String[] args) {
        if (!ensureAdmin(sender)) return true;
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(color("&cOnly players can invite to their active report chat."));
            return true;
        }
        if (args.length < 2) {
            admin.sendMessage(color("&cUsage: /reportchat invite <player>"));
            return true;
        }
        ReportChatSession session = activeSession(admin).orElse(null);
        if (session == null) {
            admin.sendMessage(color("&cYou do not have an active report chat."));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            admin.sendMessage(color("&cPlayer not found or offline."));
            return true;
        }
        if (oneActiveChatPerPlayer && activeSessionByPlayer.containsKey(target.getUniqueId())) {
            admin.sendMessage(color("&cThat player already has an active report chat."));
            return true;
        }
        session.addParticipant(target.getUniqueId());
        activeSessionByPlayer.put(target.getUniqueId(), session.id());
        broadcastToSession(session, color(prefix + " &e" + target.getName() + " was forcibly invited by " + admin.getName() + "."));
        audit("Admin " + admin.getName() + " invited " + target.getName() + " to report chat #" + session.id());
        return true;
    }

    private boolean kickPlayer(CommandSender sender, String[] args) {
        if (!ensureAdmin(sender)) return true;
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(color("&cOnly players can kick from their active report chat."));
            return true;
        }
        if (args.length < 2) {
            admin.sendMessage(color("&cUsage: /reportchat kick <player>"));
            return true;
        }
        ReportChatSession session = activeSession(admin).orElse(null);
        if (session == null) {
            admin.sendMessage(color("&cYou do not have an active report chat."));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        UUID targetId = target != null ? target.getUniqueId() : findParticipantByName(session, args[1]);
        if (targetId == null || !session.participants().contains(targetId)) {
            admin.sendMessage(color("&cThat player is not in this report chat."));
            return true;
        }
        if (targetId.equals(admin.getUniqueId())) {
            admin.sendMessage(color("&cUse /rc close to delete the chat instead of kicking yourself."));
            return true;
        }
        session.removeParticipant(targetId);
        activeSessionByPlayer.remove(targetId);
        if (target != null) {
            target.sendMessage(color(prefix + " &cYou were removed from report chat #" + session.id() + " by " + admin.getName() + "."));
        }
        broadcastToSession(session, color(prefix + " &e" + displayName(targetId) + " was removed by " + admin.getName() + "."));
        audit("Admin " + admin.getName() + " removed " + displayName(targetId) + " from report chat #" + session.id());
        return true;
    }

    private boolean closeSession(CommandSender sender, String[] args) {
        if (!ensureAdmin(sender)) return true;
        ReportChatSession session = null;
        if (args.length >= 2) {
            Integer id = parsePositiveInt(args[1]);
            if (id != null) session = sessions.get(id);
        } else if (sender instanceof Player player) {
            session = activeSession(player).orElse(null);
        }
        if (session == null) {
            sender.sendMessage(color("&cNo matching report chat found."));
            return true;
        }
        closeSessionInternal(session, "closed by " + sender.getName());
        sender.sendMessage(color("&aReport chat #" + session.id() + " closed."));
        return true;
    }

    private boolean listSessions(CommandSender sender) {
        if (!ensureAdmin(sender)) return true;
        if (sessions.isEmpty()) {
            sender.sendMessage(color("&7No active report chats."));
            return true;
        }
        sender.sendMessage(color("&eActive report chats:"));
        for (ReportChatSession session : sessions.values()) {
            String names = session.participants().stream().map(this::displayName).collect(Collectors.joining(", "));
            sender.sendMessage(color("&7#" + session.id() + " &8(report #" + session.reportId() + ") &f" + names));
        }
        return true;
    }

    private boolean leaveSession(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can leave report chats."));
            return true;
        }
        ReportChatSession session = activeSession(player).orElse(null);
        if (session == null) {
            player.sendMessage(color("&cYou do not have an active report chat."));
            return true;
        }
        if (isAdmin(player)) {
            player.sendMessage(color("&cAdmins must use /rc close or /rc kick. This is a management chat."));
            return true;
        }
        session.removeParticipant(player.getUniqueId());
        activeSessionByPlayer.remove(player.getUniqueId());
        player.sendMessage(color(prefix + " &cYou left report chat #" + session.id() + "."));
        broadcastToSession(session, color(prefix + " &e" + player.getName() + " left report chat #" + session.id() + "."));
        audit(player.getName() + " left report chat #" + session.id());
        if (session.participants().size() < 2) {
            closeSessionInternal(session, "closed automatically because fewer than two participants remained");
        }
        return true;
    }

    private boolean reloadPluginConfig(CommandSender sender) {
        if (!sender.hasPermission(reloadPermission) && !sender.isOp()) {
            sender.sendMessage(color("&cYou do not have permission to reload ReportChat."));
            return true;
        }
        loadSettings();
        sender.sendMessage(color("&aReportChat configuration reloaded."));
        return true;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Integer sessionId = activeSessionByPlayer.get(event.getPlayer().getUniqueId());
        if (sessionId == null) {
            return;
        }
        ReportChatSession session = sessions.get(sessionId);
        if (session == null) {
            activeSessionByPlayer.remove(event.getPlayer().getUniqueId());
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage();
        Player sender = event.getPlayer();
        session.touch();
        Bukkit.getScheduler().runTask(this, () -> {
            String formatted = color(prefix + " &7#" + session.id() + " &f" + sender.getName() + "&7: &f" + message);
            broadcastToSession(session, formatted);
            playSessionSound(session);
        });
    }

    private Optional<ReportChatSession> activeSession(Player player) {
        Integer sessionId = activeSessionByPlayer.get(player.getUniqueId());
        if (sessionId == null) return Optional.empty();
        return Optional.ofNullable(sessions.get(sessionId));
    }

    private void broadcastToSession(ReportChatSession session, String message) {
        for (UUID participant : session.participants()) {
            Player online = Bukkit.getPlayer(participant);
            if (online != null && online.isOnline()) {
                online.sendMessage(message);
            }
        }
    }

    private void playSessionSound(ReportChatSession session) {
        for (UUID participant : session.participants()) {
            Player player = Bukkit.getPlayer(participant);
            if (player != null && player.isOnline()) {
                playConfiguredSound(player, "sounds.chat-message", Sound.UI_BUTTON_CLICK);
            }
        }
    }

    private void closeSessionInternal(ReportChatSession session, String reason) {
        sessions.remove(session.id());
        for (UUID participant : session.participants()) {
            activeSessionByPlayer.remove(participant);
            Player online = Bukkit.getPlayer(participant);
            if (online != null && online.isOnline()) {
                online.sendMessage(color(prefix + " &cReport chat #" + session.id() + " was closed: " + reason + "."));
            }
        }
        audit("Report chat #" + session.id() + " " + reason);
    }

    private void expireIdleSessions() {
        if (autoTimeoutMinutes <= 0) return;
        long timeoutMillis = autoTimeoutMinutes * 60_000L;
        long now = System.currentTimeMillis();
        List<ReportChatSession> expired = sessions.values().stream()
                .filter(session -> now - session.lastActivityMillis() >= timeoutMillis)
                .toList();
        for (ReportChatSession session : expired) {
            closeSessionInternal(session, "idle timeout");
        }
    }

    private Collection<Player> onlineAdmins() {
        return Bukkit.getOnlinePlayers().stream()
                .filter(this::isAdmin)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean ensureAdmin(CommandSender sender) {
        if (sender instanceof Player player) {
            if (isAdmin(player)) return true;
        } else if (sender.hasPermission(adminPermission) || sender.isOp()) {
            return true;
        }
        sender.sendMessage(color("&cYou do not have permission to manage report chats."));
        return false;
    }

    private boolean isAdmin(Player player) {
        if (player.hasPermission(adminPermission)) return true;
        if (notifyOp && player.isOp()) return true;
        String name = player.getName().toLowerCase(Locale.ROOT);
        String uuid = player.getUniqueId().toString().toLowerCase(Locale.ROOT);
        return configuredAdmins.contains(name) || configuredAdmins.contains(uuid);
    }

    private void playConfiguredSound(Player player, String path, Sound fallback) {
        if (!getConfig().getBoolean("sounds.enabled", true)) return;
        String configured = getConfig().getString(path, fallback.name());
        Sound sound = fallback;
        try {
            sound = Sound.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            getLogger().warning("Invalid sound in config at " + path + ": " + configured);
        }
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    private UUID findParticipantByName(ReportChatSession session, String name) {
        for (UUID participant : session.participants()) {
            if (displayName(participant).equalsIgnoreCase(name)) {
                return participant;
            }
        }
        return null;
    }

    private String displayName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        return Bukkit.getOfflinePlayer(uuid).getName() != null ? Bukkit.getOfflinePlayer(uuid).getName() : uuid.toString();
    }

    private Integer parsePositiveInt(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sendReportChatHelp(CommandSender sender) {
        sender.sendMessage(color("&eReportChat commands:"));
        sender.sendMessage(color("&7/rc open <reportId> &8- &fCreate a private report chat"));
        sender.sendMessage(color("&7/rc invite <player> &8- &fForcibly invite a player"));
        sender.sendMessage(color("&7/rc kick <player> &8- &fRemove a player"));
        sender.sendMessage(color("&7/rc close [chatId] &8- &fDelete a report chat"));
        sender.sendMessage(color("&7/rc list &8- &fList active report chats"));
        sender.sendMessage(color("&7/rc leave &8- &fLeave as non-admin participant"));
        sender.sendMessage(color("&7/rc reload &8- &fReload config"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("reportchat")) return Collections.emptyList();
        if (args.length == 1) {
            return filterPrefix(Arrays.asList("open", "invite", "kick", "close", "list", "leave", "reload"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("open")) {
                return filterPrefix(reports.keySet().stream().sorted().map(String::valueOf).toList(), args[1]);
            }
            if (sub.equals("close")) {
                return filterPrefix(sessions.keySet().stream().sorted().map(String::valueOf).toList(), args[1]);
            }
            if (sub.equals("invite") || sub.equals("kick")) {
                return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filterPrefix(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .collect(Collectors.toList());
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private void audit(String message) {
        if (logToConsole) {
            getLogger().info(message);
        }
    }

    private record ReportRequest(int id, UUID reporterId, String reporterName, String message, Instant createdAt) {
    }

    private static final class ReportChatSession {
        private final int id;
        private final int reportId;
        private final UUID ownerAdmin;
        private final Instant createdAt;
        private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
        private volatile long lastActivityMillis = System.currentTimeMillis();

        private ReportChatSession(int id, int reportId, UUID ownerAdmin, Instant createdAt) {
            this.id = id;
            this.reportId = reportId;
            this.ownerAdmin = ownerAdmin;
            this.createdAt = createdAt;
        }

        int id() {
            return id;
        }

        int reportId() {
            return reportId;
        }

        Set<UUID> participants() {
            return participants;
        }

        long lastActivityMillis() {
            return lastActivityMillis;
        }

        void touch() {
            lastActivityMillis = System.currentTimeMillis();
        }

        void addParticipant(UUID uuid) {
            participants.add(uuid);
            touch();
        }

        void removeParticipant(UUID uuid) {
            participants.remove(uuid);
            touch();
        }
    }
}
