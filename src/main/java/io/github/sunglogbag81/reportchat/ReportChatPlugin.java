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
        getLogger().info("ReportChat 활성화됨.");
    }

    @Override
    public void onDisable() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
        sessions.clear();
        reports.clear();
        activeSessionByPlayer.clear();
        getLogger().info("ReportChat 비활성화됨.");
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
        prefix = color(config.getString("private-chat.prefix", "&8[&c신고상담&8]"));
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
            sender.sendMessage(color("&c신고는 플레이어만 할 수 있습니다."));
            return true;
        }

        String message = String.join(" ", args).trim();
        if (!allowEmptyReport && message.isEmpty()) {
            reporter.sendMessage(color("&c사용법: /report <내용>"));
            return true;
        }

        long now = System.currentTimeMillis();
        long last = lastReportAt.getOrDefault(reporter.getUniqueId(), 0L);
        long remainingMillis = (cooldownSeconds * 1000L) - (now - last);
        if (remainingMillis > 0) {
            long remainingSeconds = Math.max(1, (remainingMillis + 999) / 1000);
            reporter.sendMessage(color("&c다음 신고까지 " + remainingSeconds + "초 남았습니다."));
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

        reporter.sendMessage(color("&a신고가 온라인 관리자에게 전달되었습니다. 신고 번호: &f#" + reportId));
        String reportText = message.isEmpty() ? "(내용 없음)" : message;
        getLogger().info("신고 #" + reportId + " | 신고자: " + reporter.getName() + " | 내용: " + reportText + " | 전달된 관리자 수=" + admins.size());
        return true;
    }

    private void sendReportNotification(Player admin, ReportRequest report) {
        String reportText = report.message().isEmpty() ? "(내용 없음)" : report.message();
        admin.sendMessage(color("&8&m--------------------------------------------------"));
        admin.sendMessage(color("&c&l[신고 #" + report.id() + "] &f" + report.reporterName() + "&7: &f" + reportText));

        TextComponent button = new TextComponent(color("&a[개인 채팅 생성]"));
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/reportchat open " + report.id()));
        button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(color("&e클릭하면 신고 #" + report.id() + "의 개인 상담 채팅을 생성합니다.")).create()));
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
            sender.sendMessage(color("&c개인 상담 채팅은 플레이어 관리자만 생성할 수 있습니다."));
            return true;
        }
        if (args.length < 2) {
            admin.sendMessage(color("&c사용법: /reportchat open <신고번호>"));
            return true;
        }
        Integer reportId = parsePositiveInt(args[1]);
        if (reportId == null || !reports.containsKey(reportId)) {
            admin.sendMessage(color("&c존재하지 않는 신고 번호입니다."));
            return true;
        }

        ReportRequest report = reports.get(reportId);
        Player reporter = Bukkit.getPlayer(report.reporterId());
        if (reporter == null || !reporter.isOnline()) {
            admin.sendMessage(color("&c신고자가 오프라인이라 실시간 개인 상담 채팅을 생성할 수 없습니다."));
            return true;
        }

        if (oneActiveChatPerPlayer) {
            if (activeSessionByPlayer.containsKey(reporter.getUniqueId())) {
                admin.sendMessage(color("&c해당 신고자는 이미 활성화된 상담 채팅에 참여 중입니다."));
                return true;
            }
            if (activeSessionByPlayer.containsKey(admin.getUniqueId())) {
                admin.sendMessage(color("&c관리자님도 이미 활성화된 상담 채팅에 참여 중입니다. 먼저 /rc close 로 닫아주세요."));
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

        broadcastToSession(session, color(prefix + " &a개인 상담 채팅 #" + chatId + "이(가) " + admin.getName() + " 관리자에 의해 생성되었습니다."));
        broadcastToSession(session, color(prefix + " &7이제 참여자의 일반 채팅은 이 상담 채팅 참여자에게만 보입니다."));
        playConfiguredSound(reporter, "sounds.private-chat-created", Sound.BLOCK_NOTE_BLOCK_PLING);
        playConfiguredSound(admin, "sounds.private-chat-created", Sound.BLOCK_NOTE_BLOCK_PLING);
        audit("관리자 " + admin.getName() + " 이(가) 신고 #" + reportId + "(" + reporter.getName() + ")에 대한 상담 채팅 #" + chatId + "을 생성함");
        return true;
    }

    private boolean invitePlayer(CommandSender sender, String[] args) {
        if (!ensureAdmin(sender)) return true;
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(color("&c활성 상담 채팅 초대는 플레이어 관리자만 할 수 있습니다."));
            return true;
        }
        if (args.length < 2) {
            admin.sendMessage(color("&c사용법: /reportchat invite <플레이어>"));
            return true;
        }
        ReportChatSession session = activeSession(admin).orElse(null);
        if (session == null) {
            admin.sendMessage(color("&c참여 중인 활성 상담 채팅이 없습니다."));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            admin.sendMessage(color("&c플레이어를 찾을 수 없거나 오프라인입니다."));
            return true;
        }
        if (oneActiveChatPerPlayer && activeSessionByPlayer.containsKey(target.getUniqueId())) {
            admin.sendMessage(color("&c해당 플레이어는 이미 활성화된 상담 채팅에 참여 중입니다."));
            return true;
        }
        session.addParticipant(target.getUniqueId());
        activeSessionByPlayer.put(target.getUniqueId(), session.id());
        broadcastToSession(session, color(prefix + " &e" + admin.getName() + " 관리자가 " + target.getName() + " 님을 강제로 초대했습니다."));
        audit("관리자 " + admin.getName() + " 이(가) " + target.getName() + " 님을 상담 채팅 #" + session.id() + "에 강제 초대함");
        return true;
    }

    private boolean kickPlayer(CommandSender sender, String[] args) {
        if (!ensureAdmin(sender)) return true;
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(color("&c상담 채팅 내보내기는 플레이어 관리자만 할 수 있습니다."));
            return true;
        }
        if (args.length < 2) {
            admin.sendMessage(color("&c사용법: /reportchat kick <플레이어>"));
            return true;
        }
        ReportChatSession session = activeSession(admin).orElse(null);
        if (session == null) {
            admin.sendMessage(color("&c참여 중인 활성 상담 채팅이 없습니다."));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        UUID targetId = target != null ? target.getUniqueId() : findParticipantByName(session, args[1]);
        if (targetId == null || !session.participants().contains(targetId)) {
            admin.sendMessage(color("&c해당 플레이어는 이 상담 채팅에 없습니다."));
            return true;
        }
        if (targetId.equals(admin.getUniqueId())) {
            admin.sendMessage(color("&c본인을 내보내는 대신 /rc close 로 채팅을 삭제하세요."));
            return true;
        }
        session.removeParticipant(targetId);
        activeSessionByPlayer.remove(targetId);
        if (target != null) {
            target.sendMessage(color(prefix + " &c관리자 " + admin.getName() + " 이(가) 상담 채팅 #" + session.id() + "에서 당신을 내보냈습니다."));
        }
        broadcastToSession(session, color(prefix + " &e" + admin.getName() + " 관리자가 " + displayName(targetId) + " 님을 내보냈습니다."));
        audit("관리자 " + admin.getName() + " 이(가) " + displayName(targetId) + " 님을 상담 채팅 #" + session.id() + "에서 내보냄");
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
            sender.sendMessage(color("&c일치하는 상담 채팅을 찾을 수 없습니다."));
            return true;
        }
        closeSessionInternal(session, "관리자 " + sender.getName() + " 님이 닫음");
        sender.sendMessage(color("&a상담 채팅 #" + session.id() + "을(를) 닫았습니다."));
        return true;
    }

    private boolean listSessions(CommandSender sender) {
        if (!ensureAdmin(sender)) return true;
        if (sessions.isEmpty()) {
            sender.sendMessage(color("&7활성 상담 채팅이 없습니다."));
            return true;
        }
        sender.sendMessage(color("&e활성 상담 채팅 목록:"));
        for (ReportChatSession session : sessions.values()) {
            String names = session.participants().stream().map(this::displayName).collect(Collectors.joining(", "));
            sender.sendMessage(color("&7#" + session.id() + " &8(신고 #" + session.reportId() + ") &f" + names));
        }
        return true;
    }

    private boolean leaveSession(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c상담 채팅 나가기는 플레이어만 사용할 수 있습니다."));
            return true;
        }
        ReportChatSession session = activeSession(player).orElse(null);
        if (session == null) {
            player.sendMessage(color("&c참여 중인 활성 상담 채팅이 없습니다."));
            return true;
        }
        if (isAdmin(player)) {
            player.sendMessage(color("&c관리자는 /rc close 또는 /rc kick 을 사용해야 합니다. 이 채팅은 관리용 채팅입니다."));
            return true;
        }
        session.removeParticipant(player.getUniqueId());
        activeSessionByPlayer.remove(player.getUniqueId());
        player.sendMessage(color(prefix + " &c상담 채팅 #" + session.id() + "에서 나갔습니다."));
        broadcastToSession(session, color(prefix + " &e" + player.getName() + " 님이 상담 채팅 #" + session.id() + "에서 나갔습니다."));
        audit(player.getName() + " 님이 상담 채팅 #" + session.id() + "에서 나감");
        if (session.participants().size() < 2) {
            closeSessionInternal(session, "참여자가 2명 미만이 되어 자동 종료됨");
        }
        return true;
    }

    private boolean reloadPluginConfig(CommandSender sender) {
        if (!sender.hasPermission(reloadPermission) && !sender.isOp()) {
            sender.sendMessage(color("&cReportChat 설정을 다시 불러올 권한이 없습니다."));
            return true;
        }
        loadSettings();
        sender.sendMessage(color("&aReportChat 설정을 다시 불러왔습니다."));
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
                online.sendMessage(color(prefix + " &c상담 채팅 #" + session.id() + "이(가) 종료되었습니다: " + reason));
            }
        }
        audit("상담 채팅 #" + session.id() + " 종료: " + reason);
    }

    private void expireIdleSessions() {
        if (autoTimeoutMinutes <= 0) return;
        long timeoutMillis = autoTimeoutMinutes * 60_000L;
        long now = System.currentTimeMillis();
        List<ReportChatSession> expired = sessions.values().stream()
                .filter(session -> now - session.lastActivityMillis() >= timeoutMillis)
                .toList();
        for (ReportChatSession session : expired) {
            closeSessionInternal(session, "무응답 시간 초과");
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
        sender.sendMessage(color("&c상담 채팅을 관리할 권한이 없습니다."));
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
            getLogger().warning("설정 파일의 사운드 이름이 올바르지 않습니다: " + path + " = " + configured);
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
        sender.sendMessage(color("&eReportChat 명령어:"));
        sender.sendMessage(color("&7/rc open <신고번호> &8- &f개인 상담 채팅 생성"));
        sender.sendMessage(color("&7/rc invite <플레이어> &8- &f플레이어 강제 초대"));
        sender.sendMessage(color("&7/rc kick <플레이어> &8- &f플레이어 내보내기"));
        sender.sendMessage(color("&7/rc close [채팅번호] &8- &f상담 채팅 삭제"));
        sender.sendMessage(color("&7/rc list &8- &f활성 상담 채팅 목록"));
        sender.sendMessage(color("&7/rc leave &8- &f일반 참여자로 상담 채팅 나가기"));
        sender.sendMessage(color("&7/rc reload &8- &f설정 다시 불러오기"));
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

