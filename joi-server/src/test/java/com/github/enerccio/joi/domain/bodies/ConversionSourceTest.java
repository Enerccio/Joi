package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.collections.ModelFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.enerccio.joi.domain.bodies.UnityFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unpacking an upload for a conversion: a Unity package (only what a conversion needs, by the paths that come after the
 * assets), a zip with its metas; the Unity materials with their textures by GUID, and the FBX's material remapping.
 */
class ConversionSourceTest {

    private static final String FBX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1";
    private static final String MAT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2";
    private static final String TEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa3";
    private static final String NRM = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa4";
    private static final String ANIM = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa5";
    private static final String PROP = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa6";
    private static final String HELPER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa7";

    @TempDir
    Path dir;

    private File avatarPackage() throws Exception {
        return unityPackage(dir.resolve("avatar.unitypackage").toFile(),
                new Asset(PROP, "Assets/Props/Gun.fbx", new byte[200], meta(PROP)),
                new Asset(HELPER, "Assets/GoGo/GoBone.fbx", new byte[50], humanoidMeta(HELPER, 52, Map.of())),
                new Asset(FBX, "Assets/Ryuu/FBX/Ryuu FBX.fbx", new byte[100], humanoidMeta(FBX, 50, Map.of("Body", MAT))),
                new Asset(MAT, "Assets/Ryuu/Mats/M_Body.mat", material("M_Body", TEX, NRM).getBytes(StandardCharsets.UTF_8), meta(MAT)),
                new Asset(TEX, "Assets/Ryuu/Tex/T_Body.png", new byte[]{1, 2, 3}, meta(TEX)),
                new Asset(NRM, "Assets/Ryuu/Tex/T_Body NRM.png", new byte[]{4}, meta(NRM)),
                new Asset(ANIM, "Assets/Ryuu/Anim/Wave.anim", new byte[]{5}, meta(ANIM)));
    }

    @Test
    void aPackageIsInspectedForItsHumanoidFbx() throws Exception {
        ModelFiles.Inspection inspection = ModelFiles.inspect(avatarPackage(), "Ryuu.unitypackage");
        assertThat(inspection.format()).isEqualTo(ModelFormat.FBX);
        // a humanoid wins over the larger prop, the larger humanoid over a helper rig with more bones
        assertThat(inspection.mainFile()).isEqualTo("Assets/Ryuu/FBX/Ryuu FBX.fbx");
        assertThat(inspection.fbxFiles()).containsExactly("Assets/Ryuu/FBX/Ryuu FBX.fbx", "Assets/GoGo/GoBone.fbx", "Assets/Props/Gun.fbx");
        assertThat(inspection.fileCount()).isEqualTo(7);
        assertThat(inspection.contentType()).isEqualTo(ModelFiles.UNITY_PACKAGE_CONTENT_TYPE);
    }

    @Test
    void aPackageUnpacksWhatAConversionNeedsWithItsMaterials() throws Exception {
        ConversionSource source = ConversionSource.unpack(avatarPackage(), "Ryuu.unitypackage", dir.resolve("work"));
        Path root = source.root();
        assertThat(root.resolve("Assets/Ryuu/FBX/Ryuu FBX.fbx")).hasSize(100);
        assertThat(root.resolve("Assets/Ryuu/FBX/Ryuu FBX.fbx.meta")).content().contains("humanDescription");
        assertThat(root.resolve("Assets/Ryuu/Tex/T_Body.png")).exists();
        assertThat(root.resolve("Assets/Ryuu/Anim/Wave.anim")).doesNotExist();

        Map<String, Map<String, Object>> materials = source.materials();
        assertThat(materials).containsOnlyKeys("Assets/Ryuu/Mats/M_Body.mat");
        Map<String, Object> body = materials.get("Assets/Ryuu/Mats/M_Body.mat");
        assertThat(body).containsEntry("name", "M_Body").containsEntry("file", "Assets/Ryuu/Mats/M_Body.mat");
        assertThat(body.get("textures")).isEqualTo(Map.of("_MainTex", "Assets/Ryuu/Tex/T_Body.png", "_BumpMap", "Assets/Ryuu/Tex/T_Body NRM.png"));
        assertThat(body.get("floats")).isEqualTo(Map.of("_Cull", 0.0, "_Cutoff", 0.5, "_Mode", 1.0));
        assertThat(body.get("colors")).isEqualTo(Map.of("_Color", List.of(1.0, 0.5, 0.25, 1.0)));

        assertThat(source.materialMap("Assets/Ryuu/FBX/Ryuu FBX.fbx")).isEqualTo(Map.of("Body", "Assets/Ryuu/Mats/M_Body.mat"));
        assertThat(source.materialMap("Assets/Props/Gun.fbx")).isEmpty();
    }

    @Test
    void aZipFindsTexturesThroughItsMetas() throws Exception {
        File zip = zip(dir.resolve("ryuu.zip").toFile(), files(
                "ryuu/ryuu.fbx", new byte[10],
                "ryuu/ryuu.fbx.meta", humanoidMeta(FBX, 3, Map.of("Body", MAT)),
                "ryuu/mats/M_Body.mat", material("M_Body", TEX, NRM),
                "ryuu/mats/M_Body.mat.meta", meta(MAT),
                "ryuu/tex/body.png", new byte[]{1},
                "ryuu/tex/body.png.meta", meta(TEX),
                "__MACOSX/ryuu/._ryuu.fbx", new byte[1]));
        assertThat(ModelFiles.inspect(zip, "ryuu.zip").fbxFiles()).containsExactly("ryuu/ryuu.fbx");

        ConversionSource source = ConversionSource.unpack(zip, "ryuu.zip", dir.resolve("work"));
        assertThat(source.root().resolve("__MACOSX")).doesNotExist();
        Map<String, Object> body = source.materials().get("ryuu/mats/M_Body.mat");
        // the normal map has no .meta: Unity would not know it either
        assertThat(body.get("textures")).isEqualTo(Map.of("_MainTex", "ryuu/tex/body.png"));
        assertThat(source.materialMap("ryuu/ryuu.fbx")).isEqualTo(Map.of("Body", "ryuu/mats/M_Body.mat"));
    }

    @Test
    void pathsOutsideTheFolderAreRefused() throws Exception {
        assertThat(ConversionSource.safe("../evil.png")).isNull();
        assertThat(ConversionSource.safe("a/../../evil.png")).isNull();
        assertThat(ConversionSource.safe("C:\\\\evil.png")).isNull();
        assertThat(ConversionSource.safe("/abs/ok.png")).isEqualTo("abs/ok.png");
        assertThat(ConversionSource.safe("a\\\\b.png")).isEqualTo("a/b.png");

        File zip = zip(dir.resolve("evil.zip").toFile(), files("../evil.fbx", new byte[1], "ok/model.fbx", new byte[1]));
        ConversionSource.unpack(zip, "evil.zip", dir.resolve("work"));
        assertThat(dir.resolve("evil.fbx")).doesNotExist();
        assertThat(dir.resolve("work/ok/model.fbx")).exists();
        assertThat(Files.exists(dir.resolve("work/../evil.fbx"))).isFalse();
    }
}
