package alex.valker91.testmigrator;

import javax.xml.stream.*;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Set;

public class XmlSanitizer {

    // Оставляем только нужные для поиска локаторов атрибуты
    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            // Android & общие
            "resource-id", "content-desc", "text", "class",
            // iOS XCUITest
            "name", "label", "value", "type",
            // Статусы
            "enabled", "visible", "accessible", "displayed"
    );

    public static String sanitize(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }

        // 1. Извлекаем чистый XML: убираем BOM и находим первый '<' и последний '>'
        String cleanXml = extractPureXml(rawInput);
        if (cleanXml.isEmpty()) {
            return rawInput;
        }

        try {
            XMLInputFactory inputFactory = XMLInputFactory.newInstance();
            inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

            XMLStreamReader reader = inputFactory.createXMLStreamReader(new StringReader(cleanXml));

            StringWriter stringWriter = new StringWriter();
            XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
            XMLStreamWriter writer = outputFactory.createXMLStreamWriter(stringWriter);

            while (reader.hasNext()) {
                int event = reader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        String tagName = reader.getLocalName();
                        writer.writeStartElement(tagName);

                        // Сохраняем ТОЛЬКО разрешенные атрибуты (выкидывая bounds, x, y, width, height, index и т.д.)
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String attrName = reader.getAttributeLocalName(i);
                            String attrValue = reader.getAttributeValue(i);

                            if (ALLOWED_ATTRIBUTES.contains(attrName.toLowerCase())) {
                                if (attrValue != null && !attrValue.isBlank()) {
                                    writer.writeAttribute(attrName, attrValue);
                                }
                            }
                        }
                        break;

                    case XMLStreamConstants.CHARACTERS:
                        String text = reader.getText().trim();
                        if (!text.isEmpty()) {
                            writer.writeCharacters(text);
                        }
                        break;

                    case XMLStreamConstants.END_ELEMENT:
                        writer.writeEndElement();
                        break;
                }
            }

            writer.flush();
            writer.close();
            reader.close();

            return stringWriter.toString();

        } catch (Exception e) {
            System.err.println("[XmlSanitizer Warning] Ошибка парсинга XML, используем исходный: " + e.getMessage());
            return cleanXml;
        }
    }

    private static String extractPureXml(String text) {
        // Убираем UTF-8 BOM если есть
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        // Если в строке есть обертка от MCP вроде [TextContent[text=...]]
        int startXml = text.indexOf('<');
        int endXml = text.lastIndexOf('>');

        if (startXml != -1 && endXml != -1 && endXml > startXml) {
            return text.substring(startXml, endXml + 1);
        }

        return text.trim();
    }
}