package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.collections.ModelFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** What an uploaded file of a model is: a model file by its extension, or a zip and its main file. */
class ModelFilesTest {

    @TempDir
    Path dir;

    private File zip(String... names) throws Exception {
        File file = dir.resolve("model.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) {
                    out.write(name.getBytes());
                }
                out.closeEntry();
            }
        }
        return file;
    }

    @Test
    void aModelFileIsKnownByItsExtension() throws Exception {
        File file = Files.writeString(dir.resolve("x.bin"), "x").toFile();
        assertThat(ModelFiles.inspect(file, "Ryuu.VRM"))
                .isEqualTo(new ModelFiles.Inspection(ModelFormat.VRM, null, 1, "model/gltf-binary", java.util.List.of()));
        assertThat(ModelFiles.inspect(file, "Ryuu.fbx").fbxFiles()).containsExactly("Ryuu.fbx");
        assertThat(ModelFiles.inspect(file, "a.fbx").format()).isEqualTo(ModelFormat.FBX);
        assertThat(ModelFiles.inspect(file, "notes.txt")).isNull();
    }

    @Test
    void theMainFileOfAZipIsTheBestFormatThenTheLargest() throws Exception {
        File file = zip("avatar/", "avatar/textures/T_Body.png", "avatar/deep/a-longer-named.fbx", "avatar/ryuu.fbx",
                "avatar/ryuu.fbx.meta", "__MACOSX/avatar/._ryuu.fbx", "avatar/.DS_Store");
        ModelFiles.Inspection inspection = ModelFiles.inspect(file, "ryuu.zip");
        assertThat(inspection.format()).isEqualTo(ModelFormat.FBX);
        // no humanoid among them: the largest
        assertThat(inspection.mainFile()).isEqualTo("avatar/deep/a-longer-named.fbx");
        assertThat(inspection.fbxFiles()).containsExactly("avatar/deep/a-longer-named.fbx", "avatar/ryuu.fbx");
        assertThat(inspection.fileCount()).isEqualTo(4);
        assertThat(inspection.contentType()).isEqualTo(ModelFiles.ZIP_CONTENT_TYPE);

        assertThat(ModelFiles.inspect(zip("a/scene.gltf", "b/body.vrm", "c.fbx"), "m.zip").mainFile()).isEqualTo("b/body.vrm");
    }

    @Test
    void aHumanoidMetaCountsItsBones() {
        assertThat(ModelFiles.humanBones(UnityFixtures.humanoidMeta("x", 7, java.util.Map.of()))).isEqualTo(7);
        assertThat(ModelFiles.humanBones("animationType: 2\n    humanName: Hips\n")).isZero();
        assertThat(ModelFiles.humanBones(null)).isZero();
    }

    @Test
    void aZipWithoutAModelOrNotAZipIsNone() throws Exception {
        assertThat(ModelFiles.inspect(zip("readme.txt", "t.png"), "m.zip")).isNull();
        File broken = Files.writeString(dir.resolve("broken.zip"), "not a zip").toFile();
        assertThat(ModelFiles.inspect(broken, "broken.zip")).isNull();
    }

    @Test
    void anUploadFileKeepsOnlyAPlainExtension() {
        assertThat(ModelFiles.uploadFile(dir.toFile(), "My Model.VRM").getName()).matches("[0-9a-f-]{36}\\.vrm");
        assertThat(ModelFiles.uploadFile(dir.toFile(), "x.z/i..p").getName()).matches("[0-9a-f-]{36}\\.p");
        assertThat(ModelFiles.uploadFile(dir.toFile(), "noext").getName()).matches("[0-9a-f-]{36}");
        assertThat(ModelFiles.uploadFile(dir.toFile(), "a.../").getParentFile()).isEqualTo(dir.toFile());
    }
}
