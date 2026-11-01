package com.brisklauncher.mcl.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.security.SafeLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一个可视觉化编辑的设定档（JSON / TOML / properties），也是文本模式的资料来源。
 *
 * <p>设计重点：
 * <ul>
 *   <li><b>JSON</b>：用 Jackson 解析成树，把「每个子物件」当一节（Cloth Config 的 section 就是这个概念），
 *       叶节点是栏位；储存时重新 pretty-print。</li>
 *   <li><b>TOML / properties</b>：**逐行保留**方式——只改「值」那一段，注解、空行、键的顺序、
 *       甚至键名的大小写都原封不动。这样玩家自己写的注解不会被吃掉（比整档重写安全得多）。</li>
 *   <li>多行值（TOML 的跨行阵列）不参与可视化编辑，避免把格式改坏。</li>
 *   <li>存档前自动备份成 {@code <档名>.mcl-backup-<时间>}。</li>
 * </ul>
 */
public final class ConfigDocument {

    /** 支援的格式。 */
    public enum Format {
        JSON, TOML, PROPERTIES, TEXT
    }

    /** 欄位型別（決定用什麼控件編輯）。 */
    public enum ValueType {
        BOOLEAN, NUMBER, STRING, ARRAY, OTHER
    }

    /** 一個欄位。 */
    public static final class Field {
        private final String section;
        private final String key;
        private final ValueType type;
        private final String comment;
        private final int line;
        private String value;
        private boolean changed;

        Field(String section, String key, ValueType type, String comment, int line, String value) {
            this.section = section;
            this.key = key;
            this.type = type;
            this.comment = comment;
            this.line = line;
            this.value = value;
        }

        public String section() {
            return section;
        }

        public String key() {
            return key;
        }

        public ValueType type() {
            return type;
        }

        public String comment() {
            return comment;
        }

        public String value() {
            return value;
        }

        int line() {
            return line;
        }

        boolean changed() {
            return changed;
        }
    }

    /** 一節（JSON 的一個物件 / TOML 的一個 [section] / properties 的鍵前綴）。 */
    public static final class Section {
        private final String name;
        private final List<Field> fields = new ArrayList<>();

        Section(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public List<Field> fields() {
            return fields;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final Path file;
    private final Format format;
    private final List<Section> sections = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private JsonNode json;
    private String originalText = "";
    private String visualError;

    private ConfigDocument(Path file, Format format) {
        this.file = file;
        this.format = format;
    }

    // ---------- 读取 ----------

    public static ConfigDocument load(Path file) throws Exception {
        Format format = detect(file);
        ConfigDocument document = new ConfigDocument(file, format);
        document.originalText = Files.readString(file, StandardCharsets.UTF_8);
        document.parse();
        return document;
    }

    /** 依副檔名判斷格式。 */
    public static Format detect(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return Format.JSON;
        }
        if (name.endsWith(".toml")) {
            return Format.TOML;
        }
        if (name.endsWith(".properties") || name.endsWith(".cfg") || name.endsWith(".conf")
                || name.endsWith(".ini")) {
            return Format.PROPERTIES;
        }
        return Format.TEXT;
    }

    private void parse() {
        sections.clear();
        lines.clear();
        visualError = null;
        switch (format) {
            case JSON -> parseJson();
            case TOML -> parseKeyValue(true);
            case PROPERTIES -> parseKeyValue(false);
            default -> visualError = "这个格式不支援可视化编辑（只能用文本模式）";
        }
    }

    /** 重新用新的文字內容解析（文本模式改完切到可視化時用）。 */
    public void reloadFromText(String text) throws Exception {
        originalText = text;
        parse();
    }

    private void parseJson() {
        try {
            json = MAPPER.readTree(originalText);
        } catch (Exception error) {
            visualError = "JSON 解析失败：" + error.getMessage();
            return;
        }
        if (json == null || !json.isObject()) {
            visualError = "这个 JSON 的最外层不是物件，暂不支援可视化编辑";
            return;
        }
        collectJson("", (ObjectNode) json);
        if (sections.isEmpty()) {
            visualError = "这个设定档没有可编辑的栏位";
        }
    }

    /** 遞迴收集 JSON 物件：每個物件 = 一節，葉節點 = 欄位。 */
    private void collectJson(String path, ObjectNode node) {
        Section section = new Section(path.isEmpty() ? "" : path);
        var fields = node.fields();
        List<ObjectNode> children = new ArrayList<>();
        List<String> childNames = new ArrayList<>();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                children.add((ObjectNode) value);
                childNames.add(key);
            } else {
                section.fields().add(new Field(section.name(), key, jsonType(value), null, -1,
                        jsonText(value)));
            }
        }
        if (!section.fields().isEmpty()) {
            sections.add(section);
        }
        for (int index = 0; index < children.size(); index++) {
            String childPath = path.isEmpty() ? childNames.get(index)
                    : path + " › " + childNames.get(index);
            collectJson(childPath, children.get(index));
        }
    }

    private static ValueType jsonType(JsonNode node) {
        if (node.isBoolean()) {
            return ValueType.BOOLEAN;
        }
        if (node.isNumber()) {
            return ValueType.NUMBER;
        }
        if (node.isTextual()) {
            return ValueType.STRING;
        }
        if (node.isArray()) {
            return ValueType.ARRAY;
        }
        return ValueType.OTHER;
    }

    private static String jsonText(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    /**
     * 逐行解析 key = value（TOML）或 key=value（properties），保留注解与顺序。
     */
    private void parseKeyValue(boolean toml) {
        String currentSection = "";
        Section section = new Section(currentSection);
        sections.add(section);
        String[] rawLines = originalText.split("\\R", -1);
        for (int index = 0; index < rawLines.length; index++) {
            String raw = rawLines[index];
            lines.add(raw);
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")
                    || trimmed.startsWith("//")) {
                continue;
            }
            if (toml && trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
                currentSection = trimmed.substring(2, trimmed.length() - 2).trim();
                section = new Section(currentSection);
                sections.add(section);
                continue;
            }
            if (toml && trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
                section = new Section(currentSection);
                sections.add(section);
                continue;
            }
            int split = indexOfDelimiter(raw);
            if (split <= 0) {
                continue;
            }
            String key = raw.substring(0, split).trim();
            String rest = raw.substring(split + 1);
            // 值与行尾注解分开（只处理单行值；跨行阵列留给文本模式）
            String value = rest.trim();
            String comment = null;
            int hash = indexOfComment(value);
            if (hash >= 0) {
                comment = value.substring(hash).trim();
                value = value.substring(0, hash).trim();
            }
            if (looksMultiline(value)) {
                continue;
            }
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            section.fields().add(new Field(currentSection, key, valueType(value, toml), comment,
                    index, value));
        }
        // 去掉没有栏位的空节（保留第一个「一般」节的顺序）
        sections.removeIf(sectionItem -> sectionItem.fields().isEmpty() && sections.size() > 1);
        if (sections.isEmpty()) {
            visualError = "这个设定档没有可编辑的栏位";
        }
    }

    /** key 與 value 之間的分隔符位置（= 或 :，忽略引號內）。 */
    private static int indexOfDelimiter(String line) {
        boolean inQuote = false;
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (inQuote) {
                if (ch == quote) {
                    inQuote = false;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                inQuote = true;
                quote = ch;
                continue;
            }
            if (ch == '=' || ch == ':') {
                return index;
            }
        }
        return -1;
    }

    /** 行尾註解位置（忽略引號內）。 */
    private static int indexOfComment(String value) {
        boolean inQuote = false;
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (inQuote) {
                if (ch == quote) {
                    inQuote = false;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                inQuote = true;
                quote = ch;
            } else if (ch == '#') {
                return index;
            }
        }
        return -1;
    }

    /** 跨行的值（例如 TOML 多行陣列）不參與可視化編輯。 */
    private static boolean looksMultiline(String value) {
        if (value.startsWith("[") && !value.endsWith("]")) {
            return true;
        }
        if (value.startsWith("{") && !value.endsWith("}")) {
            return true;
        }
        return value.startsWith("\"\"\"");
    }

    private static ValueType valueType(String value, boolean toml) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("true") || lower.equals("false")) {
            return ValueType.BOOLEAN;
        }
        if (value.startsWith("[") || value.startsWith("{")) {
            return ValueType.ARRAY;
        }
        if (value.startsWith("\"") || value.startsWith("'")) {
            return ValueType.STRING;
        }
        if (value.matches("[-+]?\\d+([.eE][-+]?\\d+)?") || value.matches("[-+]?\\d*\\.\\d+")) {
            return ValueType.NUMBER;
        }
        return toml ? ValueType.STRING : ValueType.STRING;
    }

    // ---------- 修改 ----------

    /** 可視化模式改一個欄位的值（只記在記憶體，按儲存才寫檔）。 */
    public void setValue(Field field, String rawValue) {
        if (field == null) {
            return;
        }
        field.value = rawValue;
        field.changed = true;
        if (format == Format.JSON) {
            applyJsonValue(field, rawValue);
        } else {
            applyLineValue(field, rawValue);
        }
    }

    private void applyJsonValue(Field field, String rawValue) {
        if (json == null || !json.isObject()) {
            return;
        }
        String[] path = field.section().isEmpty() ? new String[0] : field.section().split(" › ");
        ObjectNode parent = (ObjectNode) json;
        for (String segment : path) {
            JsonNode child = parent.get(segment);
            if (!(child instanceof ObjectNode objectNode)) {
                return;
            }
            parent = objectNode;
        }
        switch (field.type()) {
            case BOOLEAN -> parent.put(field.key(), Boolean.parseBoolean(rawValue.trim()));
            case NUMBER -> {
                String trimmed = rawValue.trim();
                try {
                    if (trimmed.contains(".") || trimmed.contains("e") || trimmed.contains("E")) {
                        parent.put(field.key(), Double.parseDouble(trimmed));
                    } else {
                        parent.put(field.key(), Long.parseLong(trimmed));
                    }
                } catch (NumberFormatException error) {
                    // 数字写坏就先保留原值（储存时会再校验）
                }
            }
            case STRING -> parent.put(field.key(), rawValue);
            case ARRAY -> {
                try {
                    parent.set(field.key(), MAPPER.readTree(rawValue));
                } catch (Exception ignored) {
                    // 阵列格式不正确时保留原值
                }
            }
            default -> parent.put(field.key(), rawValue);
        }
    }

    private void applyLineValue(Field field, String rawValue) {
        int line = field.line();
        if (line < 0 || line >= lines.size()) {
            return;
        }
        String original = lines.get(line);
        int split = indexOfDelimiter(original);
        if (split <= 0) {
            return;
        }
        String left = original.substring(0, split).stripTrailing();
        String comment = field.comment();
        // TOML 的字串要加引号（原本有引号就沿用引号种类）
        String rendered = rawValue;
        if (field.type() == ValueType.STRING) {
            String trimmed = rawValue.trim();
            if (!(trimmed.startsWith("\"") || trimmed.startsWith("'"))) {
                rendered = "\"" + rawValue.replace("\"", "\\\"") + "\"";
            }
        }
        lines.set(line, left + " = " + rendered + (comment == null ? "" : " " + comment));
    }

    // ---------- 储存 ----------

    /** 產生要寫入的完整內容（不寫檔）。 */
    public String render() throws Exception {
        if (format == Format.JSON && json != null) {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        }
        if (lines.isEmpty()) {
            return originalText;
        }
        return String.join(System.lineSeparator(), lines);
    }

    /** 儲存（先備份原檔）。回傳備份檔路徑（可能為 null）。 */
    public Path save() throws Exception {
        Path backup = writeText(file, render());
        originalText = Files.readString(file, StandardCharsets.UTF_8);
        parse();
        return backup;
    }

    /**
     * 把内容写回档案（先备份、再原子替换）。文本模式直接用这个。
     *
     * @return 备份档路径；原本没有档案时回传 null
     */
    public static Path writeText(Path file, String content) throws Exception {
        Path backup = null;
        if (Files.isRegularFile(file)) {
            backup = file.resolveSibling(file.getFileName() + ".mcl-backup-"
                    + LocalDateTime.now().format(STAMP));
            try {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception error) {
                SafeLog.warn("备份设定档失败：" + error.getClass().getSimpleName());
                backup = null;
            }
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, "mcl-config-", ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicFailure) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return backup;
    }

    // ---------- 唯读属性 ----------

    public Path file() {
        return file;
    }

    public Format format() {
        return format;
    }

    public List<Section> sections() {
        return sections;
    }

    public String text() {
        return originalText;
    }

    public boolean visualSupported() {
        return format != Format.TEXT && visualError == null;
    }

    public String visualError() {
        return visualError;
    }
}
