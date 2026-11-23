package sk.arvyx.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigManagerServerIdTest {

    @Test
    void environmentServerIdOverridesConfiguredId() {
        assertEquals("chaos-2", ConfigManager.resolveServerId("skyrush-1", "chaos-2"));
    }

    @Test
    void blankEnvironmentUsesConfiguredId() {
        assertEquals("skyrush-1", ConfigManager.resolveServerId("skyrush-1", "  "));
    }

    @Test
    void invalidEnvironmentValueCannotBecomeAConfigPath() {
        assertEquals("skyrush-1", ConfigManager.resolveServerId("skyrush-1", "../outside"));
    }

    @Test
    void missingConfiguredIdFallsBackToStandalone() {
        assertEquals("standalone", ConfigManager.resolveServerId(null, null));
    }
}
