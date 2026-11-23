package com.github.enerccio.joi.domain.bodies.joim;

import com.github.enerccio.joi.domain.collections.ModelFormat;
import com.github.enerccio.joi.domain.collections.ProposalStatus;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata of a {@code .joim} ({@link JoimFile#META}): what was decided on a model, as JSON. Out of it are the
 * snapshots and the user's images (transient, the importer makes their own), the model's files and resources, and
 * what a request or a conversion is doing now.
 * <pre>
 * { "model": { "name", "notes", "appearance", "partShown", "partHidden", "shownWords", "hiddenWords", "describeToggles", "autoDescribe", "describeTogglesChosen",
 *              "describedHash", "capabilityNames", "defaultState", "toggleGroups", "toggleParents", "groupSentences",
 *              "springChains", "appliedSpringChains", "capabilities", "proposal" },
 *   "conversion": { "sourceFile", "fbx", "removeMeshes", "dropShapeKeys", "bakeShapeKeys", "maxTextureSize", "report" },
 *   "vrm": { "format", "size" },
 *   "exportedAt" }
 * </pre>
 * The values that are JSON on the model (the capabilities, the groups...) are JSON in it, not text.
 */
public final class JoimMetadata {

    private JoimMetadata() {
    }

    /**
     * The metadata of a model, without the time it is written ({@code exportedAt}), so that it is the same while
     * nothing changes.
     *
     * @param format the format of the model's file in the {@code .joim} (VRM or GLB)
     * @param size   its size in bytes
     */
    public static JsonObject of(Model model, ModelFormat format, long size) {
        JsonObject data = new JsonObject();
        put(data, "name", model.getName());
        put(data, "notes", model.getNotes());
        put(data, "appearance", model.getAppearance());
        data.add("partShown", map(model.getPartShown()));
        data.add("partHidden", map(model.getPartHidden()));
        data.add("shownWords", map(model.getShownWords()));
        data.add("hiddenWords", map(model.getHiddenWords()));
        data.add("describeToggles", list(model.getDescribeToggles()));
        data.add("autoDescribe", list(model.getAutoDescribe()));
        if (model.getDescribeTogglesChosen() != null) {
            data.addProperty("describeTogglesChosen", model.getDescribeTogglesChosen());
        }
        put(data, "describedHash", model.getDescribedHash());
        data.add("capabilityNames", map(model.getCapabilityNames()));
        json(data, "defaultState", model.getDefaultState());
        json(data, "toggleGroups", model.getToggleGroups());
        json(data, "toggleParents", model.getToggleParents());
        data.add("groupSentences", map(model.getGroupSentences()));
        json(data, "springChains", model.getSpringChains());
        json(data, "appliedSpringChains", model.getAppliedSpringChains());
        json(data, "capabilities", model.getCapabilities());
        if (model.getProposalStatus() == ProposalStatus.DONE) {
            json(data, "proposal", model.getProposal());
        }

        JsonObject conversion = new JsonObject();
        put(conversion, "sourceFile", model.getFileName());
        put(conversion, "fbx", model.chosenFbx());
        conversion.add("removeMeshes", list(model.getRemoveMeshes()));
        conversion.add("dropShapeKeys", list(model.getDropShapeKeys()));
        conversion.add("bakeShapeKeys", list(model.getBakeShapeKeys()));
        if (model.getMaxTextureSize() != null) {
            conversion.addProperty("maxTextureSize", model.getMaxTextureSize());
        }
        json(conversion, "report", model.getConversionReport());

        JsonObject vrm = new JsonObject();
        vrm.addProperty("format", format.name());
        vrm.addProperty("size", size);

        JsonObject meta = new JsonObject();
        meta.add("model", data);
        meta.add("conversion", conversion);
        meta.add("vrm", vrm);
        return meta;
    }

    /**
     * Puts what the metadata says onto the model (its name only when it has none). What the metadata leaves out stays
     * as the model has it.
     */
    public static void apply(JsonObject meta, Model model) {
        JsonObject data = object(meta, "model");
        if (model.getName() == null || model.getName().isBlank()) {
            model.setName(text(data, "name"));
        }
        model.setNotes(text(data, "notes"));
        model.setAppearance(text(data, "appearance"));
        model.setPartShown(map(data, "partShown"));
        model.setPartHidden(map(data, "partHidden"));
        model.setShownWords(map(data, "shownWords"));
        model.setHiddenWords(map(data, "hiddenWords"));
        model.setDescribeToggles(list(data, "describeToggles"));
        model.setAutoDescribe(list(data, "autoDescribe"));
        JsonElement chosen = data.get("describeTogglesChosen");
        model.setDescribeTogglesChosen(chosen == null || chosen.isJsonNull() ? null : chosen.getAsBoolean());
        model.setDescribedHash(text(data, "describedHash"));
        model.setCapabilityNames(map(data, "capabilityNames"));
        model.setDefaultState(json(data, "defaultState"));
        model.setToggleGroups(json(data, "toggleGroups"));
        model.setToggleParents(json(data, "toggleParents"));
        model.setGroupSentences(map(data, "groupSentences"));
        model.setSpringChains(json(data, "springChains"));
        model.setAppliedSpringChains(json(data, "appliedSpringChains"));
        String proposal = json(data, "proposal");
        model.setProposal(proposal);
        model.setProposalStatus(proposal == null ? null : ProposalStatus.DONE);
        model.setProposalError(null);
        model.setProposalProgress(null);

        JsonObject conversion = object(meta, "conversion");
        model.setRemoveMeshes(list(conversion, "removeMeshes"));
        model.setDropShapeKeys(list(conversion, "dropShapeKeys"));
        model.setBakeShapeKeys(list(conversion, "bakeShapeKeys"));
        JsonElement size = conversion.get("maxTextureSize");
        model.setMaxTextureSize(size == null || size.isJsonNull() ? null : size.getAsInt());
        model.setConversionReport(json(conversion, "report"));
        model.setConversionError(null);
        model.setConversionLog(null);
    }

    /** The name of a model's {@code .joim}: its name, as a file name. */
    public static String fileName(String modelName) {
        String name = modelName == null ? "" : modelName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").strip();
        return (name.isEmpty() ? "model" : name) + "." + com.github.enerccio.joi.protocol.joim.JoimFile.EXTENSION;
    }

    /** The model's name in the metadata, null for none. */
    public static String name(JsonObject meta) {
        return text(object(meta, "model"), "name");
    }

    private static void put(JsonObject object, String key, String value) {
        if (value != null) {
            object.addProperty(key, value);
        }
    }

    /** A value the model keeps as JSON text, as JSON (as text when it is no JSON). */
    private static void json(JsonObject object, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            object.add(key, JsonParser.parseString(value));
        } catch (RuntimeException e) {
            object.addProperty(key, value);
        }
    }

    private static JsonObject map(Map<String, String> map) {
        JsonObject object = new JsonObject();
        if (map != null) {
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    object.addProperty(key, value);
                }
            });
        }
        return object;
    }

    private static JsonArray list(Collection<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    /** A JSON value as the model keeps it: as JSON text (a text value as it is). */
    private static String json(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : element.toString();
    }

    private static Map<String, String> map(JsonObject object, String key) {
        Map<String, String> map = new LinkedHashMap<>();
        JsonElement element = object.get(key);
        if (element != null && element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(entry -> {
                if (entry.getValue().isJsonPrimitive()) {
                    map.put(entry.getKey(), entry.getValue().getAsString());
                }
            });
        }
        return map;
    }

    private static List<String> list(JsonObject object, String key) {
        List<String> list = new ArrayList<>();
        JsonElement element = object.get(key);
        if (element != null && element.isJsonArray()) {
            element.getAsJsonArray().forEach(item -> {
                if (item.isJsonPrimitive()) {
                    list.add(item.getAsString());
                }
            });
        }
        return list;
    }
}
