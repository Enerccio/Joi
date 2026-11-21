package com.github.enerccio.joi.domain.bodies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Unity material ({@code .mat}, Unity's YAML) for the converter: its name, the textures of its properties
 * (resolved from GUIDs to paths), and the few colors and numbers MToon can use. Works for Poiyomi and lilToon as well
 * as the standard shader, since they share the property names that matter ({@code _MainTex}, {@code _BumpMap},
 * {@code _EmissionMap}, {@code _Mode}, {@code _Cutoff}, {@code _Cull}).
 */
final class UnityMaterial {

    /** The numbers the converter reads; a Poiyomi material has hundreds. */
    private static final Set<String> FLOATS = Set.of("_Mode", "_Cutoff", "_EnableEmission", "_EmissionStrength", "_Cull",
            "_AlphaForceOpaque");
    private static final Set<String> COLORS = Set.of("_Color", "_EmissionColor");

    private static final Pattern NAME = Pattern.compile("^ {2}m_Name: (.*)$");
    private static final Pattern PROPERTY = Pattern.compile("^ {4}- (\\w+):\\s*(.*)$");
    private static final Pattern TEXTURE = Pattern.compile("m_Texture: \\{fileID: (-?\\d+)(?:, guid: ([0-9a-f]{32}))?");
    private static final Pattern COLOR = Pattern.compile("\\{r: ([-\\d.eE]+), g: ([-\\d.eE]+), b: ([-\\d.eE]+), a: ([-\\d.eE]+)}");

    private UnityMaterial() {
    }

    /**
     * @param text    the content of the {@code .mat}
     * @param pathOf  the path of the file with a GUID, null when it is not in the source
     * @return {@code {name, textures, colors, floats}}, null when the text is not a material
     */
    static Map<String, Object> parse(String text, Function<String, String> pathOf) {
        if (!text.contains("\nMaterial:")) {
            return null;
        }
        String name = null;
        Map<String, String> textures = new LinkedHashMap<>();
        Map<String, List<Double>> colors = new LinkedHashMap<>();
        Map<String, Double> floats = new LinkedHashMap<>();
        String section = null;
        String textureProperty = null;
        for (String line : text.split("\r?\n")) {
            Matcher matcher = NAME.matcher(line);
            if (matcher.matches() && name == null) {
                name = matcher.group(1).strip();
                continue;
            }
            String trimmed = line.strip();
            if (line.startsWith("    m_") && !line.startsWith("     ")) {
                int colon = trimmed.indexOf(':');
                section = colon < 0 ? trimmed : trimmed.substring(0, colon);
                textureProperty = null;
                continue;
            }
            matcher = PROPERTY.matcher(line);
            if (matcher.matches()) {
                String property = matcher.group(1);
                String value = matcher.group(2).strip();
                textureProperty = null;
                if ("m_TexEnvs".equals(section)) {
                    textureProperty = property;
                } else if ("m_Floats".equals(section) && FLOATS.contains(property)) {
                    try {
                        floats.put(property, Double.parseDouble(value));
                    } catch (NumberFormatException e) {
                        // not a number: left out
                    }
                } else if ("m_Colors".equals(section) && COLORS.contains(property)) {
                    Matcher color = COLOR.matcher(value);
                    if (color.find()) {
                        try {
                            List<Double> rgba = new ArrayList<>();
                            for (int i = 1; i <= 4; i++) {
                                rgba.add(Double.parseDouble(color.group(i)));
                            }
                            colors.put(property, rgba);
                        } catch (NumberFormatException e) {
                            // not numbers ("1e"): left out, like a float that is not one
                        }
                    }
                }
                continue;
            }
            if (textureProperty != null) {
                matcher = TEXTURE.matcher(trimmed);
                if (matcher.find()) {
                    String path = matcher.group(2) == null || "0".equals(matcher.group(1)) ? null : pathOf.apply(matcher.group(2));
                    if (path != null) {
                        textures.put(textureProperty, path);
                    }
                    textureProperty = null;
                }
            }
        }
        if (name == null) {
            return null;
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("name", name);
        material.put("textures", textures);
        material.put("colors", colors);
        material.put("floats", floats);
        return material;
    }
}
