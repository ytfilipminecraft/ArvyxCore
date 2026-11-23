package sk.arvyx.core;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hlavná vstupná trieda pluginu ArvyxCore pre Paper 26.3 (ARVYX NETWORK).
 */
public final class ArvyxCore extends JavaPlugin {

    private CoreModule coreModule;

    @Override
    public void onEnable() {
        this.coreModule = new CoreModule(this);
        this.coreModule.initialize();
    }

    @Override
    public void onDisable() {
        if (this.coreModule != null) {
            this.coreModule.shutdown();
            this.coreModule = null;
        }
    }

    public CoreModule getCoreModule() {
        return coreModule;
    }
}
