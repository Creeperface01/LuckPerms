/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.bukkit;

import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.LuckPermsApi;
import me.lucko.luckperms.bukkit.calculators.BukkitCalculatorFactory;
import me.lucko.luckperms.bukkit.contexts.BukkitContextManager;
import me.lucko.luckperms.bukkit.contexts.WorldCalculator;
import me.lucko.luckperms.bukkit.listeners.BukkitConnectionListener;
import me.lucko.luckperms.bukkit.listeners.BukkitPlatformListener;
import me.lucko.luckperms.bukkit.messaging.BukkitMessagingFactory;
import me.lucko.luckperms.bukkit.model.permissible.LPPermissible;
import me.lucko.luckperms.bukkit.model.permissible.PermissibleInjector;
import me.lucko.luckperms.bukkit.model.permissible.PermissibleMonitoringInjector;
import me.lucko.luckperms.bukkit.model.server.InjectorDefaultsMap;
import me.lucko.luckperms.bukkit.model.server.InjectorPermissionMap;
import me.lucko.luckperms.bukkit.model.server.InjectorSubscriptionMap;
import me.lucko.luckperms.bukkit.model.server.LPDefaultsMap;
import me.lucko.luckperms.bukkit.model.server.LPPermissionMap;
import me.lucko.luckperms.bukkit.model.server.LPSubscriptionMap;
import me.lucko.luckperms.bukkit.vault.VaultHookManager;
import me.lucko.luckperms.common.calculators.PlatformCalculatorFactory;
import me.lucko.luckperms.common.command.access.CommandPermission;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.config.adapter.ConfigurationAdapter;
import me.lucko.luckperms.common.contexts.ContextManager;
import me.lucko.luckperms.common.listener.ConnectionListener;
import me.lucko.luckperms.common.managers.group.StandardGroupManager;
import me.lucko.luckperms.common.managers.track.StandardTrackManager;
import me.lucko.luckperms.common.managers.user.StandardUserManager;
import me.lucko.luckperms.common.messaging.MessagingFactory;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.plugin.AbstractLuckPermsPlugin;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.tasks.CacheHousekeepingTask;
import me.lucko.luckperms.common.tasks.ExpireTemporaryTask;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;

import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * LuckPerms implementation for the Bukkit API.
 */
public class LPBukkitPlugin extends AbstractLuckPermsPlugin {
    private final LPBukkitBootstrap bootstrap;

    private BukkitSenderFactory senderFactory;
    private BukkitConnectionListener connectionListener;
    private BukkitCommandExecutor commandManager;
    private StandardUserManager userManager;
    private StandardGroupManager groupManager;
    private StandardTrackManager trackManager;
    private ContextManager<Player> contextManager;
    private LPSubscriptionMap subscriptionMap;
    private LPPermissionMap permissionMap;
    private LPDefaultsMap defaultPermissionMap;
    private VaultHookManager vaultHookManager = null;
    
    public LPBukkitPlugin(LPBukkitBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public LPBukkitBootstrap getBootstrap() {
        return this.bootstrap;
    }

    @Override
    protected void setupSenderFactory() {
        this.senderFactory = new BukkitSenderFactory(this);
    }

    @Override
    protected ConfigurationAdapter provideConfigurationAdapter() {
        return new BukkitConfigAdapter(this, resolveConfig());
    }

    @Override
    protected void registerPlatformListeners() {
        this.connectionListener = new BukkitConnectionListener(this);
        this.bootstrap.getServer().getPluginManager().registerEvents(this.connectionListener, this.bootstrap);
        this.bootstrap.getServer().getPluginManager().registerEvents(new BukkitPlatformListener(this), this.bootstrap);
    }

    @Override
    protected MessagingFactory<?> provideMessagingFactory() {
        return new BukkitMessagingFactory(this);
    }

    @Override
    protected void registerCommands() {
        this.commandManager = new BukkitCommandExecutor(this);
        PluginCommand main = this.bootstrap.getServer().getPluginCommand("luckperms");
        main.setExecutor(this.commandManager);
        main.setTabCompleter(this.commandManager);
        main.setDescription("Manage permissions");
        main.setAliases(Arrays.asList("lp", "perm", "perms", "permission", "permissions"));
    }

    @Override
    protected void setupManagers() {
        this.userManager = new StandardUserManager(this);
        this.groupManager = new StandardGroupManager(this);
        this.trackManager = new StandardTrackManager(this);
    }

    @Override
    protected PlatformCalculatorFactory provideCalculatorFactory() {
        return new BukkitCalculatorFactory(this);
    }

    @Override
    protected void setupContextManager() {
        this.contextManager = new BukkitContextManager(this);
        this.contextManager.registerCalculator(new WorldCalculator(this));
    }

    @Override
    protected void setupPlatformHooks() {
        // inject our own custom permission maps
        Runnable[] injectors = new Runnable[]{
                new InjectorSubscriptionMap(this),
                new InjectorPermissionMap(this),
                new InjectorDefaultsMap(this),
                new PermissibleMonitoringInjector(this)
        };

        for (Runnable injector : injectors) {
            injector.run();

            // schedule another injection after all plugins have loaded
            // the entire pluginmanager instance is replaced by some plugins :(
            this.bootstrap.getScheduler().asyncLater(injector, 1L);
        }

        // Provide vault support
        tryVaultHook(false);
    }

    public void tryVaultHook(boolean force) {
        if (this.vaultHookManager != null) {
            return; // already hooked
        }

        try {
            if (force || this.bootstrap.getServer().getPluginManager().isPluginEnabled("Vault")) {
                this.vaultHookManager = new VaultHookManager();
                this.vaultHookManager.hook(this);
                getLogger().info("Registered Vault permission & chat hook.");
            }
        } catch (Exception e) {
            this.vaultHookManager = null;
            getLogger().severe("Error occurred whilst hooking into Vault.");
            e.printStackTrace();
        }
    }

    @Override
    protected void registerApiOnPlatform(LuckPermsApi api) {
        this.bootstrap.getServer().getServicesManager().register(LuckPermsApi.class, api, this.bootstrap, ServicePriority.Normal);
    }

    @Override
    protected void registerHousekeepingTasks() {
        this.bootstrap.getScheduler().asyncRepeating(new ExpireTemporaryTask(this), 60L);
        this.bootstrap.getScheduler().asyncRepeating(new CacheHousekeepingTask(this), 2400L);
    }

    @Override
    protected void performFinalSetup() {
        // register permissions
        try {
            PluginManager pm = this.bootstrap.getServer().getPluginManager();
            PermissionDefault permDefault = getConfiguration().get(ConfigKeys.COMMANDS_ALLOW_OP) ? PermissionDefault.OP : PermissionDefault.FALSE;

            for (CommandPermission p : CommandPermission.values()) {
                pm.addPermission(new Permission(p.getPermission(), permDefault));
            }
        } catch (Exception e) {
            // this throws an exception if the plugin is /reloaded, grr
        }

        if (!getConfiguration().get(ConfigKeys.OPS_ENABLED)) {
            this.bootstrap.getScheduler().doSync(() -> this.bootstrap.getServer().getOperators().forEach(o -> o.setOp(false)));
        }

        // replace the temporary executor when the Bukkit one starts
        this.bootstrap.getServer().getScheduler().runTaskAsynchronously(this.bootstrap, () -> this.bootstrap.getScheduler().setUseFallback(false));

        // Load any online users (in the case of a reload)
        for (Player player : this.bootstrap.getServer().getOnlinePlayers()) {
            this.bootstrap.getScheduler().doAsync(() -> {
                try {
                    User user = this.connectionListener.loadUser(player.getUniqueId(), player.getName());
                    if (user != null) {
                        this.bootstrap.getScheduler().doSync(() -> {
                            try {
                                LPPermissible lpPermissible = new LPPermissible(player, user, this);
                                PermissibleInjector.inject(player, lpPermissible);
                            } catch (Throwable t) {
                                t.printStackTrace();
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    @Override
    protected void performEarlyDisableTasks() {
        // Switch back to the fallback executor, the bukkit one won't allow new tasks
        this.bootstrap.getScheduler().setUseFallback(true);
    }

    @Override
    protected void removePlatformHooks() {
        // uninject from players
        for (Player player : this.bootstrap.getServer().getOnlinePlayers()) {
            try {
                PermissibleInjector.unInject(player, false);
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (getConfiguration().get(ConfigKeys.AUTO_OP)) {
                player.setOp(false);
            }

            final User user = getUserManager().getIfLoaded(player.getUniqueId());
            if (user != null) {
                user.getCachedData().invalidateCaches();
                getUserManager().unload(user);
            }
        }

        // uninject custom maps
        InjectorSubscriptionMap.uninject();
        InjectorPermissionMap.uninject();
        InjectorDefaultsMap.uninject();

        // unhook vault
        if (this.vaultHookManager != null) {
            this.vaultHookManager.unhook(this);
        }
    }

    public void refreshAutoOp(User user, Player player) {
        if (user == null) {
            return;
        }

        if (getConfiguration().get(ConfigKeys.AUTO_OP)) {
            Map<String, Boolean> backing = user.getCachedData().getPermissionData(this.contextManager.getApplicableContexts(player)).getImmutableBacking();
            boolean op = Optional.ofNullable(backing.get("luckperms.autoop")).orElse(false);
            player.setOp(op);
        }
    }

    private File resolveConfig() {
        File configFile = new File(this.bootstrap.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            this.bootstrap.getDataFolder().mkdirs();
            this.bootstrap.saveResource("config.yml", false);
        }
        return configFile;
    }

    @Override
    public Optional<Contexts> getContextForUser(User user) {
        Player player = this.bootstrap.getPlayer(user.getUuid());
        if (player == null) {
            return Optional.empty();
        }
        return Optional.of(this.contextManager.getApplicableContexts(player));
    }

    @Override
    public Stream<Sender> getOnlineSenders() {
        return Stream.concat(
                Stream.of(getConsoleSender()),
                this.bootstrap.getServer().getOnlinePlayers().stream().map(p -> getSenderFactory().wrap(p))
        );
    }

    @Override
    public Sender getConsoleSender() {
        return getSenderFactory().wrap(this.bootstrap.getConsole());
    }

    public BukkitSenderFactory getSenderFactory() {
        return this.senderFactory;
    }

    @Override
    public ConnectionListener getConnectionListener() {
        return this.connectionListener;
    }

    @Override
    public BukkitCommandExecutor getCommandManager() {
        return this.commandManager;
    }

    @Override
    public StandardUserManager getUserManager() {
        return this.userManager;
    }

    @Override
    public StandardGroupManager getGroupManager() {
        return this.groupManager;
    }

    @Override
    public StandardTrackManager getTrackManager() {
        return this.trackManager;
    }

    @Override
    public ContextManager<Player> getContextManager() {
        return this.contextManager;
    }

    public LPSubscriptionMap getSubscriptionMap() {
        return this.subscriptionMap;
    }

    public void setSubscriptionMap(LPSubscriptionMap subscriptionMap) {
        this.subscriptionMap = subscriptionMap;
    }

    public LPPermissionMap getPermissionMap() {
        return this.permissionMap;
    }

    public void setPermissionMap(LPPermissionMap permissionMap) {
        this.permissionMap = permissionMap;
    }

    public LPDefaultsMap getDefaultPermissionMap() {
        return this.defaultPermissionMap;
    }

    public void setDefaultPermissionMap(LPDefaultsMap defaultPermissionMap) {
        this.defaultPermissionMap = defaultPermissionMap;
    }

}
