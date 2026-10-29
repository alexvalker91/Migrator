package com.project.migration.application.pagesource;

import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.mcp.RawPageSource;
import com.project.migration.domain.model.screen.CleaningResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic, LLM-free compression of a raw MCP {@code pageSource} (ARCHITECTURE_SPEC 2.1):
 * attribute whitelist, removal of invisible noise with {@code offscreen="true"} on invisible but locatable
 * elements, semantic {@code zone}/{@code scrollable_container} markup, collapsing of attribute-less layout
 * wrappers, tag shortening and secret masking.
 * <p>
 * Invisible elements are handled bottom-up: an invisible element with its own semantics is kept and marked
 * {@code offscreen}; an invisible element without semantics is treated as a wrapper of whatever survives below
 * it; an invisible branch with no semantics anywhere is dropped.
 */
public final class XmlPageSourceCleaner {

    public static final String ROOT_TAG = "hierarchy";
    public static final String PLATFORM_ATTRIBUTE = "platform";
    public static final String ZONE_ATTRIBUTE = "zone";
    public static final String SCROLLABLE_CONTAINER_ATTRIBUTE = "scrollable_container";
    public static final String OFFSCREEN_ATTRIBUTE = "offscreen";

    private static final Pattern INVALID_TAG_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    private final TokenEstimator tokenEstimator;
    private final SecretMasker secretMasker;
    private final CleanXmlWriter writer = new CleanXmlWriter();

    public XmlPageSourceCleaner() {
        this(new TokenEstimator(), new SecretMasker());
    }

    public XmlPageSourceCleaner(TokenEstimator tokenEstimator, SecretMasker secretMasker) {
        this.tokenEstimator = Checks.notNull(tokenEstimator, "tokenEstimator");
        this.secretMasker = Checks.notNull(secretMasker, "secretMasker");
    }

    /**
     * @throws McpException if the MCP server returned XML that is not well-formed
     */
    public CleaningResult clean(RawPageSource source) {
        Checks.notNull(source, "source");
        CleaningProfile profile = CleaningProfile.forPlatform(source.platform());
        Element documentRoot = parse(source).getDocumentElement();

        Map<String, String> rootAttributes = new LinkedHashMap<>();
        rootAttributes.put(PLATFORM_ATTRIBUTE, source.platform().fileToken());
        rootAttributes.putAll(profile.rootAttributes(documentRoot));

        Walk walk = new Walk(profile, rootAttributes);
        List<CleanNode> content = profile.isDocumentWrapper(documentRoot.getTagName())
                ? walk.cleanChildren(documentRoot, false, false)
                : walk.cleanElement(documentRoot, false, false);
        CleanNode root = new CleanNode(ROOT_TAG, rootAttributes, content);

        String cleanedXml = writer.write(root);
        return CleaningResult.of(cleanedXml, tokenEstimator.estimate(source.xml()), tokenEstimator.estimate(cleanedXml));
    }

    /** One cleaning pass: the profile plus the root attributes that per-node compaction refers to. */
    private final class Walk {

        private final CleaningProfile profile;
        private final Map<String, String> rootAttributes;

        Walk(CleaningProfile profile, Map<String, String> rootAttributes) {
            this.profile = profile;
            this.rootAttributes = rootAttributes;
        }

        /**
         * Returns the nodes that replace {@code element} in its parent: none, itself, or its lifted children.
         *
         * @param listItem direct child of a scrollable container: kept even without attributes, otherwise the
         *                 texts of all rows would merge into one flat list
         */
        List<CleanNode> cleanElement(Element element, boolean ancestorHidden, boolean listItem) {
            String tag = element.getTagName();
            boolean hidden = ancestorHidden || profile.isHidden(element);
            boolean scrollableContainer = profile.isScrollableContainer(tag);
            List<CleanNode> children = cleanChildren(element, hidden, scrollableContainer);
            boolean semantic = hasSemantics(element);

            if (hidden && !semantic && children.isEmpty()) {
                return List.of();
            }

            Map<String, String> attributes = keptAttributes(tag, element);
            profile.zoneOf(tag, element).ifPresent(zone -> attributes.put(ZONE_ATTRIBUTE, zone.name()));
            if (scrollableContainer) {
                attributes.put(SCROLLABLE_CONTAINER_ATTRIBUTE, "true");
            }
            if (hidden && semantic) {
                attributes.put(OFFSCREEN_ATTRIBUTE, "true");
            }

            boolean bareWrapper = attributes.isEmpty() && !listItem
                    && (hidden || profile.isCollapsibleWrapper(tag));
            if (bareWrapper) {
                return children;
            }
            return List.of(new CleanNode(sanitizeTag(profile.shortTag(tag)), attributes, children));
        }

        List<CleanNode> cleanChildren(Element parent, boolean hidden, boolean parentIsScrollable) {
            List<CleanNode> cleaned = new ArrayList<>();
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element child) {
                    cleaned.addAll(cleanElement(child, hidden, parentIsScrollable));
                }
            }
            return cleaned;
        }

        private boolean hasSemantics(Element element) {
            return profile.semanticAttributes().stream().anyMatch(name -> !element.getAttribute(name).isBlank());
        }

        private Map<String, String> keptAttributes(String tag, Element element) {
            boolean secure = profile.isSecureField(tag, element);
            Map<String, String> attributes = new LinkedHashMap<>();
            for (String name : profile.keptAttributes()) {
                String value = element.getAttribute(name);
                if (profile.isDefaultValue(name, value)) {
                    continue;
                }
                if (profile.textAttributes().contains(name)) {
                    value = secure && isSecureValueAttribute(name) ? SecretMasker.MASK : secretMasker.mask(value);
                }
                attributes.put(name, value);
            }
            profile.compact(attributes, rootAttributes);
            return attributes;
        }
    }

    /** Typed content of a password field; its identifiers ({@code name}, {@code content-desc}) stay readable. */
    private static boolean isSecureValueAttribute(String name) {
        return "text".equals(name) || "value".equals(name);
    }

    private static String sanitizeTag(String tag) {
        String sanitized = INVALID_TAG_CHARS.matcher(tag).replaceAll("_");
        return Character.isLetter(sanitized.charAt(0)) || sanitized.charAt(0) == '_' ? sanitized : "_" + sanitized;
    }

    private static Document parse(RawPageSource source) {
        try {
            return secureBuilder().parse(new InputSource(new StringReader(source.xml())));
        } catch (SAXException | IOException e) {
            throw new McpException(source.platform(), "pageSource is not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder secureBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // fatal errors throw, nothing printed to stderr
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("JDK XML parser does not support secure processing", e);
        }
    }
}
