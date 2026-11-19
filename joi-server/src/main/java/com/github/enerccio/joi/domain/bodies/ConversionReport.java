package com.github.enerccio.joi.domain.bodies;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.List;

/**
 * What the converter found and did ({@code report} of {@code convert_avatar.py}), kept on the model as JSON. Read for
 * the model dialog: the meshes to choose from, how the materials matched, the expressions, what went wrong.
 */
public record ConversionReport(List<Mesh> meshes, List<Material> materials, List<Expression> expressions,
                               List<String> humanoidMissing, List<String> warnings, Integer triangles, Integer springs,
                               String faceMesh, String error) {

    private static final Gson GSON = new Gson();

    /** A mesh of the FBX, as it was imported (before any was left out). */
    public record Mesh(String name, int triangles, int shapeKeys, List<String> materials, List<String> shapeKeyNames,
                       boolean removed) {
    }

    /** A material of the FBX and the Unity material it got ({@code by} meta or name), null when none. */
    public record Material(String name, String unity, String by, boolean textured, String alpha) {
    }

    public record Expression(String name, List<String> keys) {
    }

    /** The report in the JSON, null when there is none or it can't be read. */
    public static ConversionReport parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ConversionReport report = GSON.fromJson(json, ConversionReport.class);
            return report == null ? null : report.normalized();
        } catch (JsonParseException e) {
            return null;
        }
    }

    private ConversionReport normalized() {
        return new ConversionReport(meshes == null ? List.of() : meshes, materials == null ? List.of() : materials,
                expressions == null ? List.of() : expressions, humanoidMissing == null ? List.of() : humanoidMissing,
                warnings == null ? List.of() : warnings, triangles, springs, faceMesh, error);
    }
}
