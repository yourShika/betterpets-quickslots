package de.yourshika.betterpets.quickslots;

/**
 * The public doorway for the mouse-wheel mixin, which lives in its own package and cannot reach the
 * package-private {@link Keybinds}.
 */
public final class ScrollHook {

    private ScrollHook() {
    }

    /** @return true if this turn of the wheel was used for the quickslots and nothing else may use it */
    public static boolean onScroll(final double vertical) {
        return Keybinds.onScroll(vertical);
    }
}
