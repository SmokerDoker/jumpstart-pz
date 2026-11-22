package io.github.jumpstartpz.core;

/** A mod could not be switched on or off. When this is thrown, nothing was changed. */
public final class HotswapException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public HotswapException(String message) {
        super(message);
    }

    public HotswapException(String message, Throwable cause) {
        super(message, cause);
    }
}
