package sk.arvyx.core.util;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Centralizovaný logger pre ArvyxCore.
 * Funguje rovnako v prostredí Paper servera aj v izolovaných JUnit testoch.
 */
public final class Logger {

    private final java.util.logging.Logger delegate;
    private volatile boolean debugEnabled;

    public Logger(java.util.logging.Logger delegate) {
        this(delegate, false);
    }

    public Logger(java.util.logging.Logger delegate, boolean debugEnabled) {
        this.delegate = Objects.requireNonNull(delegate, "Logger delegate nesmie byť null");
        this.debugEnabled = debugEnabled;
    }

    public static Logger createFallback(String name) {
        return new Logger(java.util.logging.Logger.getLogger(name), false);
    }

    public void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void info(String message) {
        if (message != null) {
            delegate.info("[ArvyxCore] " + message);
        }
    }

    public void warn(String message) {
        if (message != null) {
            delegate.warning("[ArvyxCore] " + message);
        }
    }

    public void error(String message) {
        if (message != null) {
            delegate.severe("[ArvyxCore] " + message);
        }
    }

    public void error(String message, Throwable throwable) {
        delegate.log(Level.SEVERE, "[ArvyxCore] " + (message != null ? message : "Neočakávaná chyba"), throwable);
    }

    public void debug(String message) {
        if (debugEnabled && message != null) {
            delegate.info("[ArvyxCore:DEBUG] " + message);
        }
    }
}
