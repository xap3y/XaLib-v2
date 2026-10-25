package eu.xap3y.xalib;

import eu.xap3y.xagui.XaGui;
import eu.xap3y.xagui.XaGuiPlugin;
import eu.xap3y.xagui.commands.XaGuiCommandInjector;
import eu.xap3y.xalib.command.ReflectInvokeCommand;
import eu.xap3y.xalib.util.ConfigDb;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class XaLib extends JavaPlugin {

    @Getter
    private static XaLib instance;

    @Getter
    private boolean useComponents = false;

    private final String prefix = "§a[§bXaLib§a] §f";

    @Override
    public void onEnable() {
        instance = this;

        if (getCommand("xainvoke") != null) {
            ReflectInvokeCommand command = new ReflectInvokeCommand(this);
            getCommand("xainvoke").setExecutor(command);
            getCommand("xainvoke").setTabCompleter(command);
        }

        try {
            Class.forName("net.kyori.adventure.text.Component");
            useComponents = true;
        } catch (ClassNotFoundException e) {
            useComponents = false;
        }

        this.getServer().getConsoleSender().sendMessage(prefix + "§7Loaded XaLib v" + ConfigDb.VERSION + " (" + ConfigDb.GIT_HASH + ")");

        try {
            this.getServer().getConsoleSender().sendMessage(prefix + "§7§oRegistering XaGui as plugin..");

            XaGui xaGui = new XaGui(this);
            XaGuiPlugin.registerAsXaGui(xaGui);

            this.getServer().getConsoleSender().sendMessage(prefix + "§7§oInjecting XaGui commands..");
            XaGuiCommandInjector.injectCommand();

            this.getServer().getConsoleSender().sendMessage(prefix + "§7Finished loading XaLib!");
        } catch (Exception e) {
            this.getServer().getConsoleSender().sendMessage(prefix + "§cFailed to register XaGui commands!");
            e.printStackTrace();
        }

        int pluginId = 30763;
        new Metrics(this, pluginId);
    }

}
