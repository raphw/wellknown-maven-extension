package codes.rafael.wellknown;

import org.apache.maven.artifact.versioning.ComparableVersion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

public record VersionListing(String groupId,
                             String artifactId,
                             String latest,
                             String release,
                             String lastUpdated,
                             List<String> versions) {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Comparator<String> ORDER = Comparator.comparing(ComparableVersion::new);

    public VersionListing {
        versions = List.copyOf(versions);
    }

    public static VersionListing of(String groupId, String artifactId, String version) {
        return new VersionListing(groupId,
                artifactId,
                version,
                snapshot(version) ? null : version,
                ZonedDateTime.now(ZoneOffset.UTC).format(TIMESTAMP),
                List.of(version));
    }

    public static VersionListing parse(byte[] bytes) throws IOException {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            document = builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to read Maven metadata", e);
        }
        Element root = document.getDocumentElement();
        Element versioning = child(root, "versioning");
        List<String> versions = new ArrayList<>();
        Element listed = versioning == null ? null : child(versioning, "versions");
        if (listed != null) {
            NodeList nodes = listed.getChildNodes();
            for (int index = 0; index < nodes.getLength(); index++) {
                if (nodes.item(index) instanceof Element element && element.getTagName().equals("version")) {
                    versions.add(element.getTextContent().strip());
                }
            }
        }
        return new VersionListing(text(root, "groupId"),
                text(root, "artifactId"),
                versioning == null ? null : text(versioning, "latest"),
                versioning == null ? null : text(versioning, "release"),
                versioning == null ? null : text(versioning, "lastUpdated"),
                versions);
    }

    public VersionListing filter(Predicate<String> admitted) {
        List<String> kept = versions.stream().filter(admitted).toList();
        return new VersionListing(groupId,
                artifactId,
                kept.stream().max(ORDER).orElse(null),
                kept.stream().filter(version -> !snapshot(version)).max(ORDER).orElse(null),
                lastUpdated,
                kept);
    }

    public VersionListing merge(VersionListing other) {
        Set<String> merged = new LinkedHashSet<>(versions);
        merged.addAll(other.versions);
        return new VersionListing(groupId == null ? other.groupId : groupId,
                artifactId == null ? other.artifactId : artifactId,
                newest(latest, other.latest),
                newest(release, other.release),
                Stream.of(lastUpdated, other.lastUpdated).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null),
                merged.stream().sorted(ORDER).toList());
    }

    public byte[] toBytes() throws IOException {
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = document.createElement("metadata");
            document.appendChild(root);
            append(document, root, "groupId", groupId);
            append(document, root, "artifactId", artifactId);
            Element versioning = document.createElement("versioning");
            root.appendChild(versioning);
            append(document, versioning, "latest", latest);
            append(document, versioning, "release", release);
            Element listed = document.createElement("versions");
            versioning.appendChild(listed);
            for (String version : versions) {
                append(document, listed, "version", version);
            }
            append(document, versioning, "lastUpdated", lastUpdated);
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(outputStream));
            return outputStream.toByteArray();
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("Failed to write Maven metadata of " + groupId + ":" + artifactId, e);
        }
    }

    private static boolean snapshot(String version) {
        return version.endsWith("SNAPSHOT");
    }

    private static String newest(String left, String right) {
        if (left == null || right == null) {
            return left == null ? right : left;
        }
        return ORDER.compare(left, right) >= 0 ? left : right;
    }

    private static Element child(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null || element.getTextContent().isBlank() ? null : element.getTextContent().strip();
    }

    private static void append(Document document, Element parent, String name, String value) {
        if (value != null) {
            Element element = document.createElement(name);
            element.setTextContent(value);
            parent.appendChild(element);
        }
    }
}
