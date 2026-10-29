package com.project.migration.application.codegen;

import com.project.migration.domain.exception.CacheCorruptedException;
import com.project.migration.domain.model.Checks;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One element of a cleaned snapshot ({@code XmlPageSourceCleaner} output): short tag and the kept attributes.
 */
record XmlElement(String tag, Map<String, String> attributes) {

    XmlElement {
        Checks.notBlank(tag, "tag");
        attributes = Map.copyOf(attributes);
    }

    String attribute(String name) {
        return attributes.getOrDefault(name, "");
    }

    /** All elements of the document in document order, the root included. */
    static List<XmlElement> parse(String cleanXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(cleanXml)));
            List<XmlElement> elements = new ArrayList<>();
            collect(document.getDocumentElement(), elements);
            return elements;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new CacheCorruptedException("stored snapshot is not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static void collect(Element element, List<XmlElement> into) {
        Map<String, String> attributes = new LinkedHashMap<>();
        NamedNodeMap nodes = element.getAttributes();
        for (int i = 0; i < nodes.getLength(); i++) {
            attributes.put(nodes.item(i).getNodeName(), nodes.item(i).getNodeValue());
        }
        into.add(new XmlElement(element.getTagName(), attributes));
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement) {
                collect(childElement, into);
            }
        }
    }
}
