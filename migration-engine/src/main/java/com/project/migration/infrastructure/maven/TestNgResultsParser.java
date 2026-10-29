package com.project.migration.infrastructure.maven;

import com.project.migration.domain.model.run.TestFailure;
import com.project.migration.domain.model.run.TestOutcome;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code testng-results.xml}. A method with several invocations (data providers) fails if any
 * invocation fails. A test whose {@code @BeforeMethod}/{@code @BeforeClass} failed has no passing invocation
 * and is reported as skipped.
 */
final class TestNgResultsParser {

    /** @param classFqcn class the method belongs to */
    record MethodResult(String classFqcn, String methodName, TestOutcome outcome, Optional<TestFailure> failure) {
    }

    private TestNgResultsParser() {
    }

    static List<MethodResult> parse(Path resultsXml) throws IOException, SAXException {
        String xml = Files.readString(resultsXml, StandardCharsets.UTF_8);
        Document document;
        try {
            document = builder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("JDK XML parser does not support secure processing", e);
        }
        Map<String, List<Element>> byMethod = new LinkedHashMap<>();
        Map<String, Optional<TestFailure>> configFailureByClass = new LinkedHashMap<>();
        NodeList classes = document.getElementsByTagName("class");
        for (int i = 0; i < classes.getLength(); i++) {
            Element classElement = (Element) classes.item(i);
            String className = classElement.getAttribute("name");
            NodeList methods = classElement.getElementsByTagName("test-method");
            for (int j = 0; j < methods.getLength(); j++) {
                Element method = (Element) methods.item(j);
                if ("true".equals(method.getAttribute("is-config"))) {
                    if ("FAIL".equals(method.getAttribute("status"))) {
                        configFailureByClass.putIfAbsent(className, Optional.of(failureOf(method)));
                    }
                } else {
                    byMethod.computeIfAbsent(className + "#" + method.getAttribute("name"), key -> new ArrayList<>())
                            .add(method);
                }
            }
        }
        List<MethodResult> results = new ArrayList<>();
        byMethod.forEach((key, invocations) -> {
            String className = key.substring(0, key.indexOf('#'));
            String methodName = key.substring(key.indexOf('#') + 1);
            results.add(resultOf(className, methodName, invocations, configFailureByClass.getOrDefault(className, Optional.empty())));
        });
        return results;
    }

    private static MethodResult resultOf(String className, String methodName, List<Element> invocations,
                                         Optional<TestFailure> configFailure) {
        Optional<Element> failed = invocations.stream().filter(m -> "FAIL".equals(m.getAttribute("status"))).findFirst();
        if (failed.isPresent()) {
            return new MethodResult(className, methodName, TestOutcome.FAILED, Optional.of(failureOf(failed.get())));
        }
        boolean anyPassed = invocations.stream().anyMatch(m -> "PASS".equals(m.getAttribute("status")));
        if (anyPassed && invocations.stream().noneMatch(m -> "SKIP".equals(m.getAttribute("status")))) {
            return new MethodResult(className, methodName, TestOutcome.PASSED, Optional.empty());
        }
        Optional<TestFailure> reason = configFailure.or(() -> invocations.stream()
                .filter(m -> "SKIP".equals(m.getAttribute("status"))).findFirst().map(TestNgResultsParser::failureOf));
        return new MethodResult(className, methodName, TestOutcome.SKIPPED, reason);
    }

    /** Configuration failures (a failed {@code @BeforeMethod}) hide tests that never got to run. */
    static Optional<TestFailure> configurationFailure(Path resultsXml, String classFqcn) throws IOException, SAXException {
        String xml = Files.readString(resultsXml, StandardCharsets.UTF_8);
        try {
            Document document = builder().parse(new InputSource(new StringReader(xml)));
            NodeList classes = document.getElementsByTagName("class");
            for (int i = 0; i < classes.getLength(); i++) {
                Element classElement = (Element) classes.item(i);
                if (!classFqcn.equals(classElement.getAttribute("name"))) {
                    continue;
                }
                NodeList methods = classElement.getElementsByTagName("test-method");
                for (int j = 0; j < methods.getLength(); j++) {
                    Element method = (Element) methods.item(j);
                    if ("true".equals(method.getAttribute("is-config")) && "FAIL".equals(method.getAttribute("status"))) {
                        return Optional.of(failureOf(method));
                    }
                }
            }
            return Optional.empty();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("JDK XML parser does not support secure processing", e);
        }
    }

    private static TestFailure failureOf(Element method) {
        String message = text(method, "message");
        String stackTrace = text(method, "full-stacktrace");
        String exceptionClass = "";
        NodeList exceptions = method.getElementsByTagName("exception");
        if (exceptions.getLength() > 0) {
            exceptionClass = ((Element) exceptions.item(0)).getAttribute("class");
        }
        String shown = message.isBlank() ? (exceptionClass.isBlank() ? method.getAttribute("status") : exceptionClass)
                : message.strip();
        return new TestFailure(shown, stackTrace.isBlank() ? exceptionClass + ": " + message : stackTrace.strip());
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static DocumentBuilder builder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder;
    }
}
