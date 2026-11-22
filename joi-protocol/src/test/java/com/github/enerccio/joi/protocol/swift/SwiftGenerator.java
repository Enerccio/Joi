package com.github.enerccio.joi.protocol.swift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.github.enerccio.joi.protocol.JoiProtocol;
import com.github.enerccio.joi.protocol.ProtocolSamples;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes the Swift package of the iOS app's protocol types ({@code joi-ios/Packages/JoiProtocol}) from the JSON Schemas, the
 * source of truth of the API, and from {@code asyncapi.yaml} (which data a type of frame carries). The Java records are not
 * the source: the schemas are, so the Swift cannot drift from them.
 * <p>
 * What it writes, all under a {@code Generated} folder: a {@code Codable} struct per object schema, a string enum with an
 * {@code unknown} case per enum schema (a value of a newer server does not fail a decode), {@code Frames.swift} (the typed
 * data of a frame and a constructor per type), the constants of {@link JoiProtocol}, and the tests: a decode and an encode of each
 * sample of {@link ProtocolSamples}. The files that are written by hand ({@code Package.swift}, {@code JSONValue.swift}...) are
 * not touched. The schemas use few features; one that this does not know is an error, not a guess.
 * <p>
 * Run by {@code mvn -o -Pswift process-test-classes -pl joi-protocol}; {@code SwiftGeneratorTest} fails when the files differ.
 */
public final class SwiftGenerator {

    /** Where the generated sources and tests are, relative to the package. */
    static final String SOURCES = "Sources/JoiProtocol/Generated/";
    static final String TESTS = "Tests/JoiProtocolTests/Generated/";

    private static final String BASE = "/joi-protocol/";
    private static final Pattern PLAIN_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** Words Swift does not take as a name without backticks. */
    private static final Set<String> KEYWORDS = Set.of("associatedtype", "class", "deinit", "enum", "extension", "fileprivate",
            "func", "import", "init", "inout", "internal", "let", "open", "operator", "private", "precedencegroup", "protocol", "public",
            "rethrows", "static", "struct", "subscript", "typealias", "var", "break", "case", "catch", "continue", "default", "defer", "do",
            "else", "fallthrough", "for", "guard", "if", "in", "repeat", "return", "throw", "switch", "where", "while", "Any", "as", "await",
            "false", "is", "nil", "self", "Self", "super", "throws", "true", "try", "Type");

    private final Map<String, JsonObject> schemas;
    private final Set<String> enums = new TreeSet<>();
    private final Map<String, String> files = new LinkedHashMap<>();

    private SwiftGenerator(Map<String, JsonObject> schemas) {
        this.schemas = schemas;
    }

    /** Runs with the package folder as the argument (else {@code ../joi-ios/Packages/JoiProtocol}). */
    public static void main(String[] args) throws IOException {
        Path folder = Path.of(args.length > 0 ? args[0] : "../joi-ios/Packages/JoiProtocol");
        Map<String, String> files = generate();
        write(folder, files);
        System.out.println("Swift: " + files.size() + " files written to " + folder.toAbsolutePath().normalize());
    }

    /** The generated files: the path from the package folder, and the text. */
    public static Map<String, String> generate() {
        try {
            return new SwiftGenerator(readSchemas()).run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the files and removes the ones of the generated folders that are not generated any more. */
    static void write(Path folder, Map<String, String> files) throws IOException {
        for (String directory : List.of(SOURCES, TESTS)) {
            Path path = folder.resolve(directory);
            if (Files.isDirectory(path)) {
                try (Stream<Path> existing = Files.list(path)) {
                    for (Path file : existing.toList()) {
                        if (!files.containsKey(folder.relativize(file).toString())) {
                            Files.delete(file);
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = folder.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> run() throws IOException {
        for (Map.Entry<String, JsonObject> schema : schemas.entrySet()) {
            if (isEnum(schema.getValue())) {
                enums.add(schema.getKey());
            }
        }
        for (Map.Entry<String, JsonObject> schema : schemas.entrySet()) {
            String name = schema.getKey();
            JsonObject object = schema.getValue();
            if (enums.contains(name)) {
                files.put(SOURCES + name + ".swift", enumSource(name, object));
            } else if ("object".equals(string(object, "type")) && object.has("properties")) {
                files.put(SOURCES + name + ".swift", structSource(name, object));
            } else {
                throw new IllegalStateException(name + ": a schema of this shape is not known to the Swift generator");
            }
        }
        files.put(SOURCES + "Frames.swift", framesSource());
        files.put(SOURCES + "JoiProtocolInfo.swift", infoSource());
        files.put(TESTS + "FixtureTests.swift", fixturesSource());
        return files;
    }

    // ---------------------------------------------------------------- enums and structs

    private static boolean isEnum(JsonObject schema) {
        return "string".equals(string(schema, "type")) && schema.has("enum");
    }

    private String enumSource(String name, JsonObject schema) {
        List<String> values = new ArrayList<>();
        schema.getAsJsonArray("enum").forEach(v -> values.add(v.getAsString()));
        // a value named unknown would clash with the case of the values not known; a keyword needs its backticks
        List<String> cases = values.stream().map(SwiftGenerator::caseName)
                .map(c -> c.equals("unknown") ? "unknownValue" : c).map(SwiftGenerator::identifier).toList();
        StringBuilder out = header(name);
        doc(out, "", string(schema, "description"));
        out.append("public enum ").append(name).append(": Hashable, Sendable, RawRepresentable, Codable {\n");
        for (int i = 0; i < values.size(); i++) {
            out.append("    case ").append(cases.get(i)).append("\n");
        }
        out.append("    /// A value this version does not know: a newer server may send one (minor versions only add things).\n");
        out.append("    case unknown(String)\n\n");
        out.append("    public init(rawValue: String) {\n        switch rawValue {\n");
        for (int i = 0; i < values.size(); i++) {
            out.append("        case ").append(literal(values.get(i))).append(": self = .").append(cases.get(i)).append("\n");
        }
        out.append("        default: self = .unknown(rawValue)\n        }\n    }\n\n");
        out.append("    public var rawValue: String {\n        switch self {\n");
        for (int i = 0; i < values.size(); i++) {
            out.append("        case .").append(cases.get(i)).append(": return ").append(literal(values.get(i))).append("\n");
        }
        out.append("        case .unknown(let value): return value\n        }\n    }\n\n");
        out.append("    /// The values of this version.\n    public static let known: [").append(name).append("] = [")
                .append(String.join(", ", cases.stream().map(c -> "." + c).toList())).append("]\n\n");
        out.append("    public init(from decoder: Decoder) throws {\n");
        out.append("        self.init(rawValue: try decoder.singleValueContainer().decode(String.self))\n    }\n\n");
        out.append("    public func encode(to encoder: Encoder) throws {\n");
        out.append("        var container = encoder.singleValueContainer()\n        try container.encode(rawValue)\n    }\n}\n");
        return out.toString();
    }

    private String structSource(String name, JsonObject schema) {
        JsonObject properties = schema.getAsJsonObject("properties");
        Set<String> required = new TreeSet<>();
        if (schema.has("required")) {
            schema.getAsJsonArray("required").forEach(r -> required.add(r.getAsString()));
        }
        record Property(String json, String swift, String type, boolean optional, String description) {
        }
        List<Property> list = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            Type type = type(name + "." + entry.getKey(), entry.getValue().getAsJsonObject());
            boolean optional = type.nullable || !required.contains(entry.getKey());
            list.add(new Property(entry.getKey(), propertyName(entry.getKey()), type.swift, optional,
                    string(entry.getValue().getAsJsonObject(), "description")));
        }
        StringBuilder out = header(name);
        doc(out, "", string(schema, "description"));
        out.append("public struct ").append(name).append(": Codable, Hashable, Sendable {\n");
        for (Property p : list) {
            doc(out, "    ", p.description);
            out.append("    public var ").append(identifier(p.swift)).append(": ").append(p.type).append(p.optional ? "?" : "").append("\n");
        }
        out.append("\n    public init(");
        out.append(String.join(", ", list.stream().map(p -> identifier(p.swift) + ": " + p.type + (p.optional ? "? = nil" : "")).toList()));
        out.append(") {\n");
        for (Property p : list) {
            out.append("        self.").append(identifier(p.swift)).append(" = ").append(identifier(p.swift)).append("\n");
        }
        out.append("    }\n");
        if (list.stream().anyMatch(p -> !p.json.equals(p.swift))) {
            out.append("\n    enum CodingKeys: String, CodingKey {\n");
            for (Property p : list) {
                out.append("        case ").append(identifier(p.swift));
                if (!p.json.equals(p.swift)) {
                    out.append(" = ").append(literal(p.json));
                }
                out.append("\n");
            }
            out.append("    }\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /** A Swift type, and whether the schema lets the value be null. */
    private record Type(String swift, boolean nullable) {
    }

    private Type type(String where, JsonObject schema) {
        if (schema.has("$ref")) {
            return new Type(reference(where, schema.get("$ref").getAsString()), false);
        }
        if (schema.has("oneOf")) {
            JsonArray choices = schema.getAsJsonArray("oneOf");
            JsonObject other = null;
            boolean nullable = false;
            for (JsonElement choice : choices) {
                if ("null".equals(string(choice.getAsJsonObject(), "type"))) {
                    nullable = true;
                } else {
                    other = choice.getAsJsonObject();
                }
            }
            if (choices.size() != 2 || !nullable || other == null) {
                throw new IllegalStateException(where + ": only oneOf of null and one type is known");
            }
            return new Type(type(where, other).swift, true);
        }
        JsonElement declared = schema.get("type");
        if (declared == null) {
            throw new IllegalStateException(where + ": no type");
        }
        boolean nullable = false;
        String base = null;
        if (declared.isJsonArray()) {
            for (JsonElement t : declared.getAsJsonArray()) {
                if ("null".equals(t.getAsString())) {
                    nullable = true;
                } else if (base == null) {
                    base = t.getAsString();
                } else {
                    throw new IllegalStateException(where + ": more than one type is not known");
                }
            }
        } else {
            base = declared.getAsString();
        }
        if (base == null) {
            throw new IllegalStateException(where + ": no type but null");
        }
        String swift = switch (base) {
            case "string" -> switch (string(schema, "format") == null ? "" : string(schema, "format")) {
                case "date-time" -> "Date";
                case "" -> "base64".equals(string(schema, "contentEncoding")) ? "Data" : "String";
                default -> throw new IllegalStateException(where + ": the format " + string(schema, "format") + " is not known");
            };
            case "integer" -> "Int";
            case "number" -> "Double";
            case "boolean" -> "Bool";
            case "array" -> {
                if (!schema.has("items")) {
                    throw new IllegalStateException(where + ": an array without items");
                }
                Type item = type(where + "[]", schema.getAsJsonObject("items"));
                if (item.nullable) {
                    throw new IllegalStateException(where + ": null items are not known");
                }
                yield "[" + item.swift + "]";
            }
            case "object" -> {
                if (schema.has("properties")) {
                    throw new IllegalStateException(where + ": an object inside an object is not known (give it a schema)");
                }
                yield "[String: JSONValue]";
            }
            default -> throw new IllegalStateException(where + ": the type " + base + " is not known");
        };
        return new Type(swift, nullable);
    }

    private String reference(String where, String ref) {
        String suffix = ".schema.json";
        if (!ref.endsWith(suffix) || ref.contains("/")) {
            throw new IllegalStateException(where + ": " + ref + " is not a schema of this folder");
        }
        String name = ref.substring(0, ref.length() - suffix.length());
        if (!schemas.containsKey(name)) {
            throw new IllegalStateException(where + ": no schema " + name);
        }
        return name;
    }

    // ---------------------------------------------------------------- frames

    /** The data of each type of frame, from asyncapi.yaml: the type, and the schema (null: no data). */
    private Map<String, String> frameData() throws IOException {
        JsonNode components;
        try (InputStream in = SwiftGenerator.class.getResourceAsStream(BASE + "asyncapi.yaml")) {
            components = new YAMLMapper().readTree(in).path("components").path("schemas");
        }
        Map<String, String> data = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> schema : components.properties()) {
            if (!schema.getKey().endsWith("Frame")) {
                continue;
            }
            JsonNode properties = schema.getValue().path("allOf").path(1).path("properties");
            JsonNode type = properties.path("type");
            List<String> types = new ArrayList<>();
            if (type.has("const")) {
                types.add(type.get("const").asText());
            } else {
                type.path("enum").forEach(t -> types.add(t.asText()));
            }
            JsonNode ref = properties.path("data").path("$ref");
            String name = ref.isMissingNode() ? null : reference(schema.getKey(), ref.asText().substring(ref.asText().lastIndexOf('/') + 1));
            for (String t : types) {
                data.put(t, name);
            }
        }
        return data;
    }

    private String framesSource() throws IOException {
        Map<String, String> data = frameData();
        List<String> types = new ArrayList<>();
        schemas.get("NodeMessage").getAsJsonArray("enum").forEach(t -> types.add(t.getAsString()));
        if (!new TreeSet<>(types).equals(new TreeSet<>(data.keySet()))) {
            throw new IllegalStateException("The types of NodeMessage.schema.json and of asyncapi.yaml differ: " + types + " and " + data.keySet());
        }
        StringBuilder out = new StringBuilder(HEADER_LINE);
        out.append("// The frames of the node WebSocket: the data of each type, typed (from asyncapi.yaml).\n\n");
        out.append("import Foundation\n\n");
        out.append("/// The data of a frame, by its type. A type this version does not know is ").append("`unknown`, as the protocol says ignore it.\n");
        out.append("public enum NodeFrameData: Hashable, Sendable {\n");
        for (String type : types) {
            String schema = data.get(type);
            out.append("    case ").append(identifier(caseName(type))).append(schema == null ? "" : "(" + schema + ")").append("\n");
        }
        out.append("    case unknown(type: String, data: [String: JSONValue]?)\n}\n\n");
        out.append("extension Frame {\n");
        out.append("    /// What the type says it is: `.unknown` for a type of a newer version.\n");
        out.append("    public var messageType: NodeMessage { NodeMessage(rawValue: type) }\n\n");
        out.append("    /// The data as its type. Throws when a type that carries data has none, or it does not decode.\n");
        out.append("    public func typedData() throws -> NodeFrameData {\n        switch type {\n");
        for (String type : types) {
            String schema = data.get(type);
            String name = identifier(caseName(type));
            out.append("        case ").append(literal(type)).append(": ");
            out.append(schema == null ? "return ." + name : "return .%s(try decodeData(%s.self))".formatted(name, schema)).append("\n");
        }
        out.append("        default: return .unknown(type: type, data: data)\n        }\n    }\n");
        for (String type : types) {
            String schema = data.get(type);
            String name = identifier(caseName(type));
            out.append("\n    /// A `").append(type).append("` frame.\n");
            if (schema == null) {
                out.append("    public static func ").append(name).append("(id: String? = nil, ref: String? = nil) -> Frame {\n");
                out.append("        Frame(type: ").append(literal(type)).append(", id: id, ref: ref)\n    }\n");
            } else {
                out.append("    public static func ").append(name).append("(_ data: ").append(schema).append(", id: String? = nil, ref: String? = nil) throws -> Frame {\n");
                out.append("        Frame(type: ").append(literal(type)).append(", id: id, ref: ref, data: try encodeData(data))\n    }\n");
            }
        }
        out.append("}\n");
        return out.toString();
    }

    // ---------------------------------------------------------------- constants and tests

    private String infoSource() {
        StringBuilder out = new StringBuilder(HEADER_LINE);
        out.append("// The version and the paths of the API (JoiProtocol.java).\n\n");
        out.append("/// The version and the paths of Joi's API.\npublic enum JoiProtocolInfo {\n");
        Field[] fields = JoiProtocol.class.getFields();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    out.append("    public static let ").append(identifier(camel(field.getName()))).append(" = ")
                            .append(literal((String) field.get(null))).append("\n");
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        out.append("}\n");
        return out.toString();
    }

    private String fixturesSource() {
        StringBuilder out = new StringBuilder(HEADER_LINE);
        out.append("// A decode and an encode of each sample of ProtocolSamples (the samples the schemas are tested with).\n\n");
        out.append("import Testing\n@testable import JoiProtocol\n\n@Suite struct FixtureTests {\n");
        Map<String, Integer> counts = new TreeMap<>();
        List<Record> samples = new ArrayList<>(ProtocolSamples.http());
        samples.addAll(ProtocolSamples.node());
        for (Record sample : samples) {
            String type = sample.getClass().getSimpleName();
            int n = counts.merge(type, 1, Integer::sum);
            String json = GSON.toJson(sample);
            if (json.contains("\"\"\"#")) {
                throw new IllegalStateException("a sample with a raw string end in it");
            }
            out.append("\n    @Test func ").append(Character.toLowerCase(type.charAt(0))).append(type.substring(1)).append(n).append("() throws {\n");
            out.append("        let json = #\"\"\"\n").append(json).append("\n\"\"\"#\n");
            out.append("        try roundTrip(").append(type).append(".self, json)\n");
            if (type.equals("Frame")) {
                out.append("        try typedFrame(json)\n");
            }
            out.append("    }\n");
        }
        out.append("}\n");
        return out.toString();
    }

    // ---------------------------------------------------------------- text

    private static final String HEADER_LINE = "// Generated by SwiftGenerator (joi-protocol, src/test) from the JSON Schemas. Do not edit: change the schema and run\n"
            + "// mvn -o -Pswift process-test-classes -pl joi-protocol\n";

    private static StringBuilder header(String name) {
        StringBuilder out = new StringBuilder(HEADER_LINE);
        out.append("// From ").append(name).append(".schema.json.\n\nimport Foundation\n\n");
        return out;
    }

    private static void doc(StringBuilder out, String indent, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.strip().split("\n")) {
            out.append(indent).append("/// ").append(line.strip()).append("\n");
        }
    }

    private static String literal(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** The name in Swift of a property of the schema: as it is when it is a plain lower camel case, else camel-cased (with a coding key). */
    static String propertyName(String json) {
        return PLAIN_NAME.matcher(json).matches() ? json : camel(json);
    }

    /** The name of an enum case: {@code tool_call} is {@code toolCall}; {@code none} is {@code noneValue}, which {@code Optional.none} would hide. */
    static String caseName(String value) {
        String name = camel(value);
        return name.equals("none") || name.equals("some") ? name + "Value" : name;
    }

    /** {@code device_code}, {@code audio-out}, {@code DEVICE_CODE} to {@code deviceCode}, {@code audioOut}. */
    static String camel(String value) {
        String[] words = value.split("[^A-Za-z0-9]+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            String w = word.equals(word.toUpperCase()) ? word.toLowerCase() : word;
            out.append(out.isEmpty() ? Character.toLowerCase(w.charAt(0)) : Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        if (out.isEmpty() || Character.isDigit(out.charAt(0))) {
            out.insert(0, '_');
        }
        return out.toString();
    }

    /** A name as an identifier: with backticks when it is a keyword of Swift. */
    static String identifier(String name) {
        return KEYWORDS.contains(name) ? "`" + name + "`" : name;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static Map<String, JsonObject> readSchemas() throws IOException {
        Path folder;
        try {
            folder = Path.of(SwiftGenerator.class.getResource(BASE + "schemas").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        Map<String, JsonObject> schemas = new TreeMap<>();
        try (Stream<Path> list = Files.list(folder)) {
            for (Path file : list.filter(f -> f.getFileName().toString().endsWith(".schema.json")).toList()) {
                String name = file.getFileName().toString().replace(".schema.json", "");
                schemas.put(name, JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }
        return schemas;
    }
}
