package dev.steelaspect.areascanner.scan;

/** Match groups, in priority order: a block matching several groups is shown in the first one. */
public enum Category {
    CUSTOM("custom"),
    UNMOVABLE("unmovable"),
    LIQUID("liquid");

    public final String key;

    Category(String key) {
        this.key = key;
    }
}
