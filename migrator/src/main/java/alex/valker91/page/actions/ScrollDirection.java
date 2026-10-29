package alex.valker91.page.actions;

import java.util.Locale;

/**
 * Direction the content is scrolled (not the finger): {@code DOWN} reveals what is below.
 */
public enum ScrollDirection {
    UP, DOWN, LEFT, RIGHT;

    /** Value expected by {@code mobile: scroll} / {@code mobile: scrollGesture}. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ScrollDirection from(String direction) {
        if (direction != null) {
            for (ScrollDirection value : values()) {
                if (value.name().equalsIgnoreCase(direction.trim())) {
                    return value;
                }
            }
        }
        throw new IllegalArgumentException("Unknown scroll direction '" + direction + "', expected up|down|left|right");
    }
}
