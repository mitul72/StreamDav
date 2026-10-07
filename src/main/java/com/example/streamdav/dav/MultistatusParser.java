package com.example.streamdav.dav;

import com.example.streamdav.library.RemoteFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Turns a PROPFIND {@code 207 Multi-Status} body into the members of the requested folder. */
final class MultistatusParser {
    private static final Logger log = LogManager.getLogger(MultistatusParser.class);
    private static final String DAV = "DAV:";

    private MultistatusParser() {
    }

    /**
     * @param requestUri the folder that was listed; hrefs are resolved against it and its own entry is dropped
     */
    static List<RemoteFile> parse(InputStream xml, URI requestUri) throws IOException {
        Document document;
        try {
            document = newDocumentBuilder().parse(xml);
        } catch (SAXException e) {
            throw new IOException("The server sent an invalid folder listing.", e);
        }
        String folderPath = Uris.withoutTrailingSlash(requestUri.getPath());
        List<RemoteFile> files = new ArrayList<>();
        for (Element response : children(document.getDocumentElement(), "response")) {
            toRemoteFile(response, requestUri)
                    .filter(file -> !Uris.withoutTrailingSlash(file.uri().getPath()).equals(folderPath))
                    .ifPresent(files::add);
        }
        return files;
    }

    private static Optional<RemoteFile> toRemoteFile(Element response, URI requestUri) {
        String href = child(response, "href").map(Element::getTextContent).map(String::strip).orElse("");
        URI uri;
        try {
            // Use only the path: some servers behind proxies report the wrong scheme or host.
            String path = href.isEmpty() ? null : Uris.lenient(href).getRawPath();
            if (path == null || path.isEmpty()) {
                return Optional.empty();
            }
            uri = requestUri.resolve(path);
        } catch (URISyntaxException | IllegalArgumentException e) {
            log.warn("Skipping entry with unusable href '{}'", href);
            return Optional.empty();
        }

        Map<String, Element> props = foundProperties(response);
        Element resourceType = props.get("resourcetype");
        boolean directory = resourceType != null
                ? child(resourceType, "collection").isPresent()
                : uri.getRawPath().endsWith("/");
        if (directory) {
            uri = Uris.withTrailingSlash(uri);
        }
        long size = directory ? -1 : parseSize(text(props.get("getcontentlength")));
        Instant modified = parseDate(text(props.get("getlastmodified")));
        return Optional.of(new RemoteFile(uri, Uris.lastSegment(uri), directory, size, modified));
    }

    /** Properties from every propstat whose status is 200; the server lists the ones it lacks under 404. */
    private static Map<String, Element> foundProperties(Element response) {
        Map<String, Element> props = new HashMap<>();
        for (Element propstat : children(response, "propstat")) {
            String status = child(propstat, "status").map(Element::getTextContent).orElse("");
            if (!status.isBlank() && !status.contains(" 200")) {
                continue;
            }
            child(propstat, "prop").ifPresent(prop -> {
                for (Element property : children(prop, null)) {
                    props.put(property.getLocalName(), property);
                }
            });
        }
        return props;
    }

    private static long parseSize(String text) {
        try {
            return text == null ? -1 : Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Instant parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(text.strip(), Instant::from);
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text.strip());
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String text(Element element) {
        return element == null ? null : element.getTextContent();
    }

    private static Optional<Element> child(Element parent, String localName) {
        return children(parent, localName).stream().findFirst();
    }

    /** Direct child elements in the DAV: namespace, optionally only those with the given local name. */
    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && DAV.equals(element.getNamespaceURI())
                    && (localName == null || localName.equals(element.getLocalName()))) {
                result.add(element);
            }
        }
        return result;
    }

    private static DocumentBuilder newDocumentBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Server responses are untrusted: no DTDs, no external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }
}
