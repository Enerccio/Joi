package com.github.enerccio.joi.domain.bodies;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small Unity packages, zips, materials and metas for the tests of the bodies. */
public final class UnityFixtures {

    private UnityFixtures() {
    }

    /** An asset of a package: its path, content and .meta. */
    public record Asset(String guid, String path, byte[] content, String meta) {
    }

    /** A package with the assets, each stored as Unity does: asset, asset.meta, then pathname. */
    public static File unityPackage(File file, Asset... assets) throws IOException {
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(new FileOutputStream(file)))) {
            for (Asset asset : assets) {
                put(tar, asset.guid() + "/asset", asset.content());
                put(tar, asset.guid() + "/asset.meta", asset.meta().getBytes(StandardCharsets.UTF_8));
                put(tar, asset.guid() + "/pathname", (asset.path() + "\n00").getBytes(StandardCharsets.UTF_8));
            }
        }
        return file;
    }

    private static void put(TarArchiveOutputStream tar, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    /** A binary glTF (a .vrm or .glb) with the JSON and no binary chunk. */
    public static File glb(File file, String json) throws IOException {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        int padded = (text.length + 3) / 4 * 4;
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(20 + padded).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46546C67).putInt(2).putInt(20 + padded).putInt(padded).putInt(0x4E4F534A).put(text);
        while (buffer.hasRemaining()) {
            buffer.put((byte) ' ');
        }
        java.nio.file.Files.write(file.toPath(), buffer.array());
        return file;
    }

    /** A zip with the files (path → content). */
    public static File zip(File file, Map<String, byte[]> files) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    public static Map<String, byte[]> files(Object... pathsAndContents) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            Object content = pathsAndContents[i + 1];
            files.put((String) pathsAndContents[i], content instanceof byte[] bytes ? bytes : content.toString().getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    public static String meta(String guid) {
        return "fileFormatVersion: 2\nguid: " + guid + "\n";
    }

    /** The .meta of an FBX Unity imports as a humanoid with the bones, its materials remapped to .mat GUIDs. */
    public static String humanoidMeta(String guid, int bones, Map<String, String> materials) {
        StringBuilder meta = new StringBuilder("fileFormatVersion: 2\nguid: " + guid + "\nModelImporter:\n  externalObjects:\n");
        materials.forEach((name, matGuid) -> meta.append("  - first:\n      type: UnityEngine:Material\n      assembly: UnityEngine.CoreModule\n")
                .append("      name: ").append(name).append("\n    second: {fileID: 2100000, guid: ").append(matGuid).append(", type: 2}\n"));
        meta.append("  humanDescription:\n    human:\n");
        for (int i = 0; i < bones; i++) {
            meta.append("    - boneName: Bone").append(i).append("\n      humanName: Hips\n");
        }
        meta.append("  animationType: 3\n");
        return meta.toString();
    }

    /** A Poiyomi-like material: a main texture, a normal map, a cut-out mode. */
    public static String material(String name, String mainTexGuid, String bumpGuid) {
        return "%YAML 1.1\n%TAG !u! tag:unity3d.com,2011:\n--- !u!21 &2100000\nMaterial:\n  serializedVersion: 8\n"
                + "  m_Name: " + name + "\n  m_SavedProperties:\n    serializedVersion: 3\n    m_TexEnvs:\n"
                + "    - _BumpMap:\n        m_Texture: {fileID: 2800000, guid: " + bumpGuid + ", type: 3}\n        m_Scale: {x: 1, y: 1}\n"
                + "    - _DecalMask:\n        m_Texture: {fileID: 0}\n        m_Scale: {x: 1, y: 1}\n"
                + "    - _MainTex:\n        m_Texture: {fileID: 2800000, guid: " + mainTexGuid + ", type: 3}\n        m_Scale: {x: 1, y: 1}\n"
                + "    m_Ints: []\n    m_Floats:\n    - _Cull: 0\n    - _Cutoff: 0.5\n    - _Mode: 1\n    - _Unrelated: 7\n"
                + "    m_Colors:\n    - _Color: {r: 1, g: 0.5, b: 0.25, a: 1}\n    - _Other: {r: 0, g: 0, b: 0, a: 0}\n";
    }
}
