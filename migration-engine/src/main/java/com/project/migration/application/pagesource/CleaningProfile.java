package com.project.migration.application.pagesource;

import com.project.migration.domain.model.Platform;
import org.w3c.dom.Element;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Platform-specific rules of {@link XmlPageSourceCleaner}; the tree walk itself is platform-neutral.
 * Tag arguments are the original (unshortened) element names.
 */
interface CleaningProfile {

    static CleaningProfile forPlatform(Platform platform) {
        return switch (platform) {
            case ANDROID -> new AndroidCleaningProfile();
            case IOS -> new IosCleaningProfile();
        };
    }

    /** Whitelisted source attributes, in output order. The tag already carries {@code class}/{@code type}. */
    List<String> keptAttributes();

    /** Kept attributes holding free text, passed through {@link SecretMasker}. */
    Set<String> textAttributes();

    /** Attributes whose non-empty value makes a node locatable (and an invisible one {@code offscreen}). */
    List<String> semanticAttributes();

    /** Root elements that only wrap the real tree ({@code hierarchy}, {@code AppiumAUT}). */
    boolean isDocumentWrapper(String tag);

    boolean isHidden(Element element);

    /** Password / PIN input whose text values are masked entirely. */
    boolean isSecureField(String tag, Element element);

    Optional<SemanticZone> zoneOf(String tag, Element element);

    boolean isScrollableContainer(String tag);

    /** Layout container removed (children lifted) when it is left without attributes. */
    boolean isCollapsibleWrapper(String tag);

    String shortTag(String tag);

    /** Screen-wide attributes put once on the output root (e.g. the Android app package). */
    default Map<String, String> rootAttributes(Element documentRoot) {
        return Map.of();
    }

    /**
     * Drops values recoverable from the root or from sibling attributes. Must stay lossless and keep at least
     * one semantic attribute of a node that had one.
     */
    default void compact(Map<String, String> attributes, Map<String, String> rootAttributes) {
    }

    /** Values equal to the platform default carry no information and are dropped. */
    default boolean isDefaultValue(String attribute, String value) {
        return value.isEmpty()
                || ("enabled".equals(attribute) && "true".equals(value))
                || ("scrollable".equals(attribute) && "false".equals(value));
    }

    static String simpleName(String tag) {
        int dot = tag.lastIndexOf('.');
        return dot < 0 ? tag : tag.substring(dot + 1);
    }
}
