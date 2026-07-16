package org.legacy.aegis.check;

import org.legacy.aegis.config.ConfigManager;

// base class for all checks, handles config/enabled/vl stuff
public abstract class AbstractCheck implements Check {

    protected final String name;
    protected final CheckType type;
    protected final String configPath;
    protected final ConfigManager config;

    public AbstractCheck(String name, CheckType type, String configPath, ConfigManager config) {
        this.name = name;
        this.type = type;
        this.configPath = configPath;
        this.config = config;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CheckType getType() {
        return type;
    }

    @Override
    public String getConfigPath() {
        return configPath;
    }

    @Override
    public boolean isEnabled() {
        return config.isCheckEnabled(configPath);
    }

    @Override
    public int getMaxVl() {
        return config.getCheckMaxVl(configPath);
    }

    @Override
    public String getPunishment() {
        return config.getCheckPunishment(configPath);
    }
}
