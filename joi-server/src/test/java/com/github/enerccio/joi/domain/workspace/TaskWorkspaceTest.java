package com.github.enerccio.joi.domain.workspace;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.workspace.TaskWorkspace.Entry;
import com.github.enerccio.joi.domain.workspace.TaskWorkspace.RejectedException;
import com.github.enerccio.marginalia.Configuration;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The temporary workspace of an assistant: plain names only, limited in size and number, and it forgets after a day. */
class TaskWorkspaceTest extends PlatformTestBase {

    @Autowired
    private TaskWorkspace workspace;
    @Autowired
    private JoiService joiService;
    @Autowired
    private Configuration configuration;

    private Joi joi;

    @BeforeEach
    void setUp() throws Exception {
        login();
        joi = joiService.create("Mira");
    }

    private Path folderOf(Joi joi) {
        return configuration.getDataFolder().toPath().resolve("workspace").resolve(joi.getUuid());
    }

    @Test
    void aFileIsWrittenReadAndReplaced() throws Exception {
        Entry entry = workspace.write(joi, "install.sh", "#!/bin/sh\necho hi\n");
        assertThat(entry.name()).isEqualTo("install.sh");
        assertThat(entry.size()).isEqualTo(18);
        assertThat(workspace.read(joi, "install.sh")).isEqualTo("#!/bin/sh\necho hi\n");

        workspace.write(joi, "install.sh", "second");
        assertThat(workspace.read(joi, "install.sh")).isEqualTo("second");
        assertThat(workspace.list(joi)).extracting(Entry::name).containsExactly("install.sh");

        assertThat(workspace.delete(joi, "install.sh")).isTrue();
        assertThat(workspace.delete(joi, "install.sh")).isFalse();
        assertThat(workspace.list(joi)).isEmpty();
        // no half-written file is left behind
        assertThat(Files.list(folderOf(joi)).toList()).isEmpty();
    }

    @Test
    void aNameIsAPlainFileNameNothingEscapesTheFolder() {
        for (String bad : new String[]{"../secret", "a/b", "a\\b", ".hidden", "..", "", "  ", "name.", "x".repeat(81), "tab\tname", "/etc/passwd", "a..b"}) {
            assertThatThrownBy(() -> workspace.write(joi, bad, "text")).as(bad).isInstanceOf(RejectedException.class);
            assertThatThrownBy(() -> workspace.read(joi, bad)).as(bad).isInstanceOf(RejectedException.class);
        }
        assertThatThrownBy(() -> workspace.write(joi, null, "text")).isInstanceOf(RejectedException.class);
        // what is allowed: letters of any alphabet, digits, dots, dashes, underscores, spaces inside
        for (String good : new String[]{"notes.md", "Můj skript 2.sh", "a-b_c.tar.gz", "9lives"}) {
            assertThat(catching(() -> workspace.write(joi, good, "text"))).as(good).isNull();
        }
    }

    private static Throwable catching(ThrowingRunnable run) {
        try {
            run.run();
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void anEmptyOrTooLongFileIsRefusedWithAReasonTheModelCanUse() {
        assertThatThrownBy(() -> workspace.write(joi, "a.txt", "")).isInstanceOf(RejectedException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> workspace.write(joi, "a.txt", null)).isInstanceOf(RejectedException.class);
        assertThatThrownBy(() -> workspace.write(joi, "a.txt", "x".repeat(TaskWorkspace.MAX_CHARS + 1)))
                .isInstanceOf(RejectedException.class).hasMessageContaining("too long").hasMessageContaining("" + TaskWorkspace.MAX_CHARS);
        assertThatThrownBy(() -> workspace.read(joi, "missing.txt")).isInstanceOf(RejectedException.class).hasMessageContaining("no file missing.txt");
    }

    @Test
    void theOldestFilesGoWhenThereAreTooMany() throws Exception {
        for (int i = 0; i < TaskWorkspace.MAX_FILES; i++) {
            workspace.write(joi, "f" + i + ".txt", "text " + i);
            // each a minute older than the next, so the order does not depend on the clock's grain
            Files.setLastModifiedTime(folderOf(joi).resolve("f" + i + ".txt"), FileTime.from(Instant.now().minusSeconds((TaskWorkspace.MAX_FILES - i) * 60L)));
        }
        assertThat(workspace.list(joi)).hasSize(TaskWorkspace.MAX_FILES);
        // a replacement does not push anything out
        workspace.write(joi, "f0.txt", "again");
        assertThat(workspace.list(joi)).hasSize(TaskWorkspace.MAX_FILES);
        // a new one pushes out the oldest one that is left (f1: f0 was just written)
        workspace.write(joi, "new.txt", "new");
        List<String> names = workspace.list(joi).stream().map(Entry::name).toList();
        assertThat(names).hasSize(TaskWorkspace.MAX_FILES).contains("new.txt", "f0.txt").doesNotContain("f1.txt");
        assertThat(names.getFirst()).isIn("new.txt", "f0.txt");
    }

    @Test
    void whatIsADayOldIsForgotten() throws Exception {
        workspace.write(joi, "old.txt", "old");
        workspace.write(joi, "fresh.txt", "fresh");
        Files.setLastModifiedTime(folderOf(joi).resolve("old.txt"), FileTime.from(Instant.now().minus(TaskWorkspace.KEEP).minusSeconds(60)));
        assertThat(workspace.list(joi)).extracting(Entry::name).containsExactly("fresh.txt");
        assertThat(folderOf(joi).resolve("old.txt")).doesNotExist();
    }

    @Test
    void eachAssistantHasHerOwnWorkspace() throws Exception {
        Joi other = joiService.create("Other");
        workspace.write(joi, "mine.txt", "mine");
        assertThat(workspace.list(other)).isEmpty();
        assertThatThrownBy(() -> workspace.read(other, "mine.txt")).isInstanceOf(RejectedException.class);
    }
}
